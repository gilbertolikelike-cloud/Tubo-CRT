package com.example.ocrclicker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tudo que aparece por cima de outros apps, para um modo (único / multi / OCR):
 *  barra flutuante, alvos (numerados no multi, com páginas de 12), deslizes com linha,
 *  paginador "‹ 1/2 ›" e a caixa verde do OCR.
 */
class Overlay(
    private val svc: AutoClickService,
    private val store: Store,
    private val mode: Int,
    private val withOcr: Boolean
) {
    private val PAGE = 12
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(svc).scaledTouchSlop

    private fun dp(v: Number) = svc.dp(v)
    private fun sw() = svc.resources.displayMetrics.widthPixels
    private fun sh() = svc.resources.displayMetrics.heightPixels

    private val barScale = 0.6f + store.barPct / 100f * 0.8f
    private val btn = dp(46 * barScale)
    private val tSize = dp(30 + store.targetPct * 0.6f)
    private val pad = dp(16)

    /** OCR ligado: sozinho (Mode.OCR) ou junto com o modo único / multi alvos. */
    private val ocrOn = mode == Mode.OCR || withOcr

    // ------------------------------------------------------------------ janelas
    private fun lp(w: Int, h: Int, x: Int, y: Int, touchable: Boolean = true): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, f, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        return p
    }

    private fun setTouchable(v: View, p: WindowManager.LayoutParams, touchable: Boolean) {
        if (!v.isAttachedToWindow) return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ barra
    private fun icon(type: Int): IconView {
        val v = IconView(svc, type)
        v.layoutParams = LinearLayout.LayoutParams(btn, btn)
        v.setPadding(btn / 6, btn / 6, btn / 6, btn / 6)
        return v
    }

    private val playIcon = icon(IconView.PLAY)
    private val plusIcon = icon(IconView.PLUS)
    private val swipeIcon = icon(IconView.SWIPE)
    private val minusIcon = icon(IconView.MINUS)
    private val gearIcon = icon(IconView.GEAR)
    private val closeIcon = icon(IconView.CLOSE)
    private val moveIcon = icon(IconView.MOVE)

    private val statusTv = TextView(svc).apply {
        textSize = 10f
        setTextColor(0xFFFFFFFF.toInt())
        maxLines = 3
        maxWidth = btn * 2
        gravity = Gravity.CENTER
        setPadding(dp(4), 0, dp(4), dp(4))
        visibility = View.GONE
    }

    private val bar = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Ui.BAR)
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }

    private val barLp = lp(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (store.barX >= 0) store.barX else 0,
        if (store.barY >= 0) store.barY else sh() / 4
    )

    // ------------------------------------------------------------------ motores
    // engine = modo único / multi; ocrEngine = leitura OCR (os dois podem rodar juntos)
    private var stPrimary = ""
    private var stOcr = ""

    private fun refreshStatus() {
        val t = listOf(stPrimary, stOcr).filter { it.isNotEmpty() }.joinToString("\n")
        statusTv.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        statusTv.text = t
    }

    private val engine = ClickEngine(
        svc,
        status = { s -> main.post { stPrimary = s; refreshStatus() } },
        onFinished = { main.post { if (!anyRunning()) setRunningUi(false) } }
    )
    private val ocrEngine = ClickEngine(
        svc,
        status = { s -> main.post { stOcr = if (mode == Mode.OCR) s else "OCR: $s"; refreshStatus() } },
        onFinished = { main.post { if (!anyRunning()) setRunningUi(false) } }
    )

    private fun anyRunning() = engine.isRunning() || ocrEngine.isRunning()

    // ------------------------------------------------------------------ alvo único / ponto fixo do OCR
    private val showMain = mode == Mode.SINGLE
    private var mainView: TargetView? = null
    private var mainLp: WindowManager.LayoutParams? = null

    // alvo fixo do OCR (anel verde), só quando "Onde clicar" = alvo
    private val showOcrTarget = ocrOn && store.where == 2
    private var ocrTargetView: TargetView? = null
    private var ocrTargetLp: WindowManager.LayoutParams? = null

    // ------------------------------------------------------------------ caixa OCR
    private val boxLp = lp(
        if (store.boxW > 0) store.boxW else dp(240),
        if (store.boxH > 0) store.boxH else dp(110),
        if (store.boxX >= 0) store.boxX else sw() / 2 - dp(120),
        if (store.boxY >= 0) store.boxY else sh() / 3
    )
    private val boxView = BoxView(svc, wm, boxLp, pad, dp(48), dp(40) + 2 * pad, { ocrDialog() }) { saveBox() }

    // ------------------------------------------------------------------ multi alvos
    private val configs: MutableList<Config> = if (mode == Mode.MULTI) store.configs() else mutableListOf()
    private var cIndex = store.activeConfig.coerceIn(0, maxOf(0, configs.size - 1))
    private val config: Config? get() = configs.getOrNull(cIndex)
    private var page = 0
    /** Janelas de um alvo da página atual: início e (se for deslize) fim. */
    private class MSet(
        val a: View, val ap: WindowManager.LayoutParams,
        val b: View?, val bp: WindowManager.LayoutParams?
    )
    private val pageSets = ArrayList<MSet>()

    private val lines = LinesView(svc) {
        val c = config
        if (c == null) emptyList() else c.targets.subList(
            minOf(page * PAGE, c.targets.size), minOf(c.targets.size, page * PAGE + PAGE)
        )
    }
    private val linesLp = lp(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, 0, 0, false
    )

    private val prevTv = pagerText("‹")
    private val pageTv = pagerText("1/1")
    private val nextTv = pagerText("›")
    private val pager = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Ui.BAR)
            cornerRadius = dp(8).toFloat()
        }
        addView(prevTv)
        addView(pageTv)
        addView(nextTv)
    }
    private val pagerLp = lp(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        0, sh() - dp(170)
    )
    private var pagerAttached = false

    private fun pagerText(t: String): TextView {
        val v = TextView(svc)
        v.text = t
        v.textSize = 22f
        v.setTextColor(0xFFFFFFFF.toInt())
        v.gravity = Gravity.CENTER
        v.setPadding(dp(12), dp(6), dp(12), dp(6))
        return v
    }

    // ------------------------------------------------------------------ montagem
    private var collapsed = store.barCollapsed

    /** Barra expandida: ▶ + ↷ − ⚙ ✥.  Encolhida (toque no ✥): só ▶ ✕ ✥ (como no app original). */
    private fun buildBar() {
        bar.removeAllViews()
        bar.addView(playIcon)
        if (collapsed) {
            bar.addView(closeIcon)
        } else {
            if (mode == Mode.MULTI) {
                bar.addView(plusIcon)
                bar.addView(swipeIcon)
                bar.addView(minusIcon)
            }
            bar.addView(gearIcon)
        }
        bar.addView(moveIcon)
        bar.addView(statusTv)
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        store.barCollapsed = collapsed
        buildBar()
        bar.post {
            clampBar()
            upd(bar, barLp, true)
            store.barX = barLp.x; store.barY = barLp.y
        }
    }

    init {
        buildBar()
        playIcon.setOnClickListener { toggle() }
        plusIcon.setOnClickListener { addTarget(false) }
        swipeIcon.setOnClickListener { addTarget(true) }
        minusIcon.setOnClickListener { removeLast() }
        gearIcon.setOnClickListener { gear() }
        closeIcon.setOnClickListener { svc.disable() }
        dragBar()

        prevTv.setOnClickListener { changePage(-1) }
        nextTv.setOnClickListener { changePage(+1) }
    }

    fun show() {
        try {
            if (mode == Mode.MULTI) wm.addView(lines, linesLp)
            if (ocrOn) {
                boxLp.x = boxLp.x.coerceIn(0, maxOf(0, sw() - boxLp.width))
                boxLp.y = boxLp.y.coerceIn(0, maxOf(0, sh() - boxLp.height))
                wm.addView(boxView, boxLp)
            }
            if (showMain) {
                val m = marker(
                    0, tSize, if (store.sX >= 0) store.sX else sw() / 2, if (store.sY >= 0) store.sY else sh() / 2,
                    false, true, onMove = { _, _ -> }, onDrop = { saveMain() }, onTap = { }
                )
                mainView = m.first
                mainLp = m.second
                wm.addView(m.first, m.second)
            }
            if (showOcrTarget) {
                val m = marker(
                    0, tSize, if (store.oTx >= 0) store.oTx else sw() / 2, if (store.oTy >= 0) store.oTy else sh() / 2 + dp(80),
                    false, true, onMove = { _, _ -> }, onDrop = { saveOcrTarget() }, onTap = { },
                    tint = 0xFF00E676.toInt()
                )
                ocrTargetView = m.first
                ocrTargetLp = m.second
                wm.addView(m.first, m.second)
            }
            if (mode == Mode.MULTI) renderPage()
            barLp.x = barLp.x.coerceIn(0, maxOf(0, sw() - btn))
            barLp.y = barLp.y.coerceIn(0, maxOf(0, sh() - btn * 3))
            wm.addView(bar, barLp)
            bar.post { clampBar(); upd(bar, barLp, true) }
        } catch (e: Exception) {
            Toast.makeText(svc, "Não consegui mostrar a barra: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun destroy() {
        engine.release()
        ocrEngine.release()
        val all = ArrayList<View>()
        all.add(bar); all.add(boxView); all.add(lines); all.add(pager)
        mainView?.let { all.add(it) }
        ocrTargetView?.let { all.add(it) }
        for (m in pageSets) {
            all.add(m.a)
            m.b?.let { all.add(it) }
        }
        for (v in all) {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
        pageSets.clear()
    }

    // ------------------------------------------------------------------ marcador arrastável
    private var lastUpd = 0L

    /** updateViewLayout é uma chamada ao sistema: durante o arrastar, no máximo ~70 por segundo. */
    private fun upd(v: View, p: WindowManager.LayoutParams, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (force || now - lastUpd >= 14) {
            lastUpd = now
            try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
        }
    }

    /** Cria um alvo (anel azul). Arrastar move (sem sair da tela); só tocar chama [onTap]. */
    private fun marker(
        number: Int, size: Int, cx: Int, cy: Int, end: Boolean, touchable: Boolean,
        onMove: (Int, Int) -> Unit, onDrop: () -> Unit, onTap: () -> Unit,
        tint: Int? = null
    ): Pair<TargetView, WindowManager.LayoutParams> {
        val v = TargetView(svc, number, end, tint)
        val p = lp(
            size, size,
            (cx - size / 2).coerceIn(0, maxOf(0, sw() - size)),
            (cy - size / 2).coerceIn(0, maxOf(0, sh() - size)),
            touchable
        )
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; ox = p.x; oy = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - sx).toInt()
                    val dy = (ev.rawY - sy).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        p.x = (ox + dx).coerceIn(0, maxOf(0, sw() - size))
                        p.y = (oy + dy).coerceIn(0, maxOf(0, sh() - size))
                        upd(v, p)
                        onMove(p.x + size / 2, p.y + size / 2)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        upd(v, p, true)
                        onDrop()
                    } else {
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (moved) {
                    upd(v, p, true)
                    onDrop()
                }
            }
            true
        }
        return Pair(v, p)
    }

    private fun saveMain() {
        val p = mainLp ?: return
        store.sX = p.x + tSize / 2
        store.sY = p.y + tSize / 2
    }

    private fun saveOcrTarget() {
        val p = ocrTargetLp ?: return
        store.oTx = p.x + tSize / 2
        store.oTy = p.y + tSize / 2
    }

    private fun saveBox() {
        store.boxX = boxLp.x; store.boxY = boxLp.y; store.boxW = boxLp.width; store.boxH = boxLp.height
    }

    /** Mantém a barra SEMPRE inteira dentro da tela (assim o ✥ nunca fica fora de alcance). */
    private fun clampBar() {
        val w = if (bar.width > 0) bar.width else btn + dp(8)
        val h = if (bar.height > 0) bar.height else btn * 3
        barLp.x = barLp.x.coerceIn(0, maxOf(0, sw() - w))
        barLp.y = barLp.y.coerceIn(0, maxOf(0, sh() - h))
    }

    private fun dragBar() {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        moveIcon.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = barLp.x; oy = barLp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - sx).toInt()
                    val dy = (ev.rawY - sy).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        barLp.x = ox + dx
                        barLp.y = oy + dy
                        clampBar()
                        upd(bar, barLp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        clampBar()
                        upd(bar, barLp, true)
                        store.barX = barLp.x; store.barY = barLp.y
                    } else {
                        toggleCollapse()          // só tocou no ✥: encolhe / expande a barra
                    }
                }
                MotionEvent.ACTION_CANCEL -> { store.barX = barLp.x; store.barY = barLp.y }
            }
            true
        }
    }

    // ------------------------------------------------------------------ multi: páginas e alvos
    private fun saveConfigs() {
        store.saveConfigs(configs)
        store.activeConfig = cIndex
    }

    private fun removeSet(m: MSet) {
        try { wm.removeView(m.a) } catch (e: Exception) { }
        m.b?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
    }

    /** Cria as janelas do alvo [i] (só dele) e guarda em pageSets. */
    private fun addTargetWindows(i: Int) {
        val cfg = config ?: return
        val t = cfg.targets[i]
        val touch = !anyRunning()
        val m = marker(
            i + 1, tSize, t.x, t.y, false, touch,
            onMove = { cx, cy -> t.x = cx; t.y = cy; lines.invalidate() },
            onDrop = { saveConfigs() },
            onTap = { targetDialog(i) }
        )
        try { wm.addView(m.first, m.second) } catch (e: Exception) { }
        var bv: View? = null
        var bp: WindowManager.LayoutParams? = null
        if (t.type == 1) {
            val es = (tSize * 0.7f).toInt()
            val en = marker(
                i + 1, es, t.x2, t.y2, true, touch,
                onMove = { cx, cy -> t.x2 = cx; t.y2 = cy; lines.invalidate() },
                onDrop = { saveConfigs() },
                onTap = { targetDialog(i) }
            )
            try { wm.addView(en.first, en.second) } catch (e: Exception) { }
            bv = en.first
            bp = en.second
        }
        pageSets.add(MSet(m.first, m.second, bv, bp))
    }

    /** Refaz TODAS as janelas da página. Só para trocar de página / carregar configuração. */
    private fun renderPage() {
        for (m in pageSets) removeSet(m)
        pageSets.clear()
        val cfg = config ?: return
        val from = page * PAGE
        val to = minOf(cfg.targets.size, from + PAGE)
        for (i in from until to) addTargetWindows(i)
        lines.invalidate()
        updatePager()
    }

    private fun totalPages(): Int {
        val n = config?.targets?.size ?: 0
        return maxOf(1, (n + PAGE - 1) / PAGE)
    }

    private fun updatePager() {
        val total = totalPages()
        page = page.coerceIn(0, total - 1)
        pageTv.text = "${page + 1}/$total"
        if (total > 1 && !pagerAttached) {
            try { wm.addView(pager, pagerLp); pagerAttached = true } catch (e: Exception) { }
        } else if (total <= 1 && pagerAttached) {
            try { wm.removeView(pager) } catch (e: Exception) { }
            pagerAttached = false
        }
    }

    private fun changePage(d: Int) {
        if (anyRunning()) return
        val np = (page + d).coerceIn(0, totalPages() - 1)
        if (np != page) {
            page = np
            renderPage()
        }
    }

    /** Mantém a barra por cima dos alvos recém criados. */
    private fun bringBarToFront() {
        try {
            wm.removeViewImmediate(bar)
            wm.addView(bar, barLp)
            if (pagerAttached) {
                wm.removeViewImmediate(pager)
                wm.addView(pager, pagerLp)
            }
        } catch (e: Exception) { }
    }

    private fun addTarget(swipe: Boolean) {
        if (anyRunning()) return
        val cfg = config ?: return
        val k = cfg.targets.size
        val cx = sw() / 2 + (k % 5 - 2) * dp(34)
        val cy = sh() / 2 + (k % 7 - 3) * dp(34)
        val t = Target(if (swipe) 1 else 0, cx, cy, cx, cy, store.mDelayMs, store.mSwipeMs)
        if (swipe) {
            t.x2 = (cx + dp(150)).coerceAtMost(sw() - tSize)
            t.y2 = cy
        }
        cfg.targets.add(t)
        saveConfigs()
        if (k / PAGE != page) {
            page = k / PAGE
            renderPage()                       // mudou de página: refaz só desta vez
        } else {
            addTargetWindows(k)                // normal: cria SÓ a bolinha nova (nada de refazer tudo)
            lines.invalidate()
            updatePager()
        }
        // a barra só volta para cima se a bolinha nova caiu em cima dela
        val m = pageSets.lastOrNull() ?: return
        val barR = Rect(barLp.x, barLp.y, barLp.x + bar.width, barLp.y + bar.height)
        val tR = Rect(m.ap.x, m.ap.y, m.ap.x + tSize, m.ap.y + tSize)
        if (Rect.intersects(barR, tR)) main.post { bringBarToFront() }
    }

    private fun removeLast() {
        if (anyRunning()) return
        val cfg = config ?: return
        if (cfg.targets.isEmpty()) return
        val idx = cfg.targets.size - 1
        val oldPage = page
        cfg.targets.removeAt(idx)
        saveConfigs()
        val newPage = if (cfg.targets.isEmpty()) 0 else (cfg.targets.size - 1) / PAGE
        if (idx / PAGE == oldPage && newPage == oldPage && pageSets.isNotEmpty()) {
            removeSet(pageSets.removeAt(pageSets.size - 1))      // tira SÓ a última bolinha
            lines.invalidate()
            updatePager()
        } else {
            page = newPage
            renderPage()
        }
    }

    private fun loadConfigAt(i: Int) {
        configs.clear()
        configs.addAll(store.configs())
        cIndex = i.coerceIn(0, configs.size - 1)
        store.activeConfig = cIndex
        page = 0
        renderPage()
    }

    private fun newConfig() {
        val list = store.configs()
        list.add(Config("Config ${list.size + 1}"))
        store.saveConfigs(list)
        loadConfigAt(list.size - 1)
    }

    // ------------------------------------------------------------------ executar / parar
    private fun toggle() {
        if (anyRunning()) {
            engine.stop()
            ocrEngine.stop()
        } else {
            startRun()
        }
    }

    private fun startRun() {
        setRunningUi(true)
        when (mode) {
            Mode.SINGLE -> {
                val p = mainLp
                if (p != null) {
                    engine.startSingle(
                        Point(p.x + tSize / 2, p.y + tSize / 2), store.sIntervalMs,
                        Stop(store.sStopMode, store.sStopSeconds.toLong(), store.sStopCount), store.sAnti
                    )
                }
            }
            Mode.MULTI -> {
                val c = config
                if (c != null) {
                    engine.startMulti(
                        c.targets.map { it.copy() },
                        Stop(c.stopMode, c.stopSeconds.toLong(), c.stopCount), c.anti
                    )
                }
            }
        }
        if (ocrOn) startOcr()
    }

    private fun startOcr() {
        val loc = IntArray(2)
        boxView.getLocationOnScreen(loc)
        val i = pad + dp(3)
        val region = Rect(
            loc[0] + i, loc[1] + i, loc[0] + boxView.width - i, loc[1] + boxView.height - i
        )
        val tp = ocrTargetLp
        val fixed = if (tp != null) Point(tp.x + tSize / 2, tp.y + tSize / 2)
        else Point(region.centerX(), region.centerY())
        // OCR junto com único/multi: viu o texto -> pausa os outros cliques; texto sumiu -> eles voltam
        var gate: ((Boolean) -> Unit)? = null
        if (mode != Mode.OCR) gate = { on -> if (on) engine.pause() else engine.resume() }
        ocrEngine.startOcr(
            fixed, region,
            OcrParams(store.word, store.ignoreCase, store.ocrIntervalMs, store.where),
            Stop(store.oStopMode, store.oStopSeconds.toLong(), store.oStopCount), store.oAnti, gate
        )
    }

    private fun setRunningUi(on: Boolean) {
        playIcon.type = if (on) IconView.STOP else IconView.PLAY
        val touch = !on
        setTouchable(boxView, boxLp, touch)
        mainView?.let { v -> mainLp?.let { setTouchable(v, it, touch) } }
        ocrTargetView?.let { v -> ocrTargetLp?.let { setTouchable(v, it, touch) } }
        for (m in pageSets) {
            setTouchable(m.a, m.ap, touch)
            val bv = m.b
            val bp = m.bp
            if (bv != null && bp != null) setTouchable(bv, bp, touch)
        }
        barLp.flags = if (on) {
            barLp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            barLp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try { wm.updateViewLayout(bar, barLp) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ diálogos da engrenagem
    private fun gear() {
        if (anyRunning()) return
        when (mode) {
            Mode.SINGLE -> singleDialog()
            Mode.OCR -> ocrDialog()
            else -> multiDialog()
        }
    }

    private fun ocrButton(c: Context) =
        Ui.bigButton(c, "CONFIGURAÇÕES DO OCR", 0xFF424242.toInt(), true) { ocrDialog() }
            .also { it.setTextColor(Ui.PINK) }

    private fun singleDialog() {
        val c = Ui.dctx(svc)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val panel = SinglePanel(c, true)
        panel.load(store)
        col.addView(panel)
        if (ocrOn) {
            col.addView(Ui.divider(c))
            col.addView(ocrButton(c))
        }
        Ui.alert(svc, true, null, Ui.scrollPad(c, col), "SALVAR", "CANCELAR") { panel.save(store) }
    }

    private fun ocrDialog() {
        if (anyRunning()) return
        val c = Ui.dctx(svc)
        val panel = OcrPanel(c, true)
        panel.load(store)
        Ui.alert(svc, true, null, Ui.scrollPad(c, panel), "SALVAR", "CANCELAR") {
            panel.save(store)
            main.post { svc.enable(mode, withOcr) }      // recria a barra com as novas opções
        }
    }

    private fun multiDialog() {
        val cfg = config ?: return
        val c = Ui.dctx(svc)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val nameTv = Ui.text(c, cfg.name, 18f, Ui.BLUE)
        head.addView(nameTv, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val pencil = Ui.text(c, "✎", 22f, Ui.TEXT)
        pencil.setPadding(dp(10), 0, dp(4), 0)
        head.addView(pencil)
        col.addView(head)
        pencil.setOnClickListener {
            Ui.input(svc, true, "Nome da configuração", cfg.name) { n ->
                if (n.isNotEmpty()) {
                    cfg.name = n
                    nameTv.text = n
                    saveConfigs()
                }
            }
        }

        var dlg: AlertDialog? = null
        val load = Ui.bigButton(c, "CARREGAR CONFIGURAÇÃO", 0xFF424242.toInt(), true) {
            dlg?.dismiss()
            Ui.configList(svc, true, store, { loadConfigAt(it) }, { newConfig() })
        }
        load.setTextColor(Ui.PINK)
        col.addView(load)
        col.addView(Ui.divider(c))

        val stop = StopPanel(c, true, "Número de ciclos:")
        stop.load(cfg.stopMode, cfg.stopSeconds, cfg.stopCount, cfg.anti)
        col.addView(stop)
        if (ocrOn) {
            col.addView(Ui.divider(c))
            col.addView(ocrButton(c))
        }

        dlg = Ui.alert(svc, true, null, Ui.scrollPad(c, col), "SALVAR", "CANCELAR") {
            cfg.stopMode = stop.stopMode
            cfg.stopSeconds = stop.stopSeconds
            cfg.stopCount = stop.stopCount
            cfg.anti = stop.antiOn
            saveConfigs()
        }
    }

    /** Toque em um alvo: atraso (e duração do deslize). */
    private fun targetDialog(i: Int) {
        if (anyRunning()) return
        val t = config?.targets?.getOrNull(i) ?: return
        val c = Ui.dctx(svc)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(c, "Atraso", 18f, Ui.TEXT, true))
        col.addView(Ui.text(c, "O tempo de atraso antes do próximo clique", 14f, Ui.TEXT2))
        col.addView(Ui.text(c, "O tempo abaixo de 40 milissegundos pode travar seu dispositivo", 11f, Ui.WARN))
        val delayF = IntervalField(c)
        delayF.set(t.delayMs)
        col.addView(delayF)

        val swipeEt = EditText(c)
        swipeEt.inputType = InputType.TYPE_CLASS_NUMBER
        swipeEt.gravity = Gravity.CENTER
        swipeEt.setText(t.swipeMs.toString())
        if (t.type == 1) {
            col.addView(Ui.divider(c))
            col.addView(Ui.text(c, "Duração de deslize (ms)", 18f, Ui.TEXT, true))
            col.addView(Ui.text(c, "A duração deve ser superior a 300ms", 14f, Ui.TEXT2))
            col.addView(swipeEt)
        }
        Ui.alert(svc, true, "Alvo ${i + 1}", Ui.scrollPad(c, col), "SALVAR", "CANCELAR") {
            t.delayMs = delayF.ms().coerceAtLeast(1L)
            if (t.type == 1) t.swipeMs = (swipeEt.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(300L)
            saveConfigs()
        }
    }
}

// =====================================================================================
// Views
// =====================================================================================

/** Anel azul (número em laranja no multi). [end] = ponta final de um deslize. [tint] = cor própria (anel verde do OCR). */
class TargetView(
    ctx: Context,
    private val number: Int,
    private val end: Boolean,
    private val tint: Int? = null
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val base: Int = tint ?: (if (end) 0xFFFF9800.toInt() else 0xFF2979FF.toInt())
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = base
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (base and 0x00FFFFFF) or 0x33000000
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (end) 0xFF2979FF.toInt() else 0xFFFF9800.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 2 * d
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        if (number > 0) {
            txt.textSize = r * 1.1f
            c.drawText(number.toString(), cx, cy + txt.textSize * 0.35f, txt)
        } else {
            c.drawCircle(cx, cy, 3 * d, dot)
        }
    }
}

/** Linhas tracejadas (início → fim) dos deslizes da página atual. */
class LinesView(ctx: Context, private val provider: () -> List<Target>) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = 0xCCFF9800.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 6 * d), 0f)
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF9800.toInt()
    }

    override fun onDraw(c: Canvas) {
        for (t in provider()) {
            if (t.type != 1) continue
            c.drawLine(t.x.toFloat(), t.y.toFloat(), t.x2.toFloat(), t.y2.toFloat(), line)
            val a = atan2((t.y2 - t.y).toDouble(), (t.x2 - t.x).toDouble())
            val l = 12 * d
            val p = Path()
            p.moveTo(t.x2.toFloat(), t.y2.toFloat())
            p.lineTo(t.x2 - l * cos(a - 0.5).toFloat(), t.y2 - l * sin(a - 0.5).toFloat())
            p.lineTo(t.x2 - l * cos(a + 0.5).toFloat(), t.y2 - l * sin(a + 0.5).toFloat())
            p.close()
            c.drawPath(p, head)
        }
    }
}

/**
 * Caixa de leitura OCR. A janela tem uma margem (pad); a área lida é só o retângulo de dentro.
 * A bolinha no canto inferior direito (na margem) redimensiona; o resto move.
 */
class BoxView(
    ctx: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val pad: Int,
    private val handle: Int,
    private val minSize: Int,
    private val onTap: () -> Unit,
    private val onDone: () -> Unit
) : View(ctx) {

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var moved = false
    private var lastUpd = 0L

    private fun push(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (force || now - lastUpd >= 14) {
            lastUpd = now
            try { wm.updateViewLayout(this, lp) } catch (ex: Exception) { }
        }
    }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = pad / 5f; color = 0xFF00E676.toInt()
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt() }
    private var mode = 0
    private var sx = 0f
    private var sy = 0f
    private var ox = 0
    private var oy = 0
    private var ow = 0
    private var oh = 0

    override fun onDraw(c: Canvas) {
        c.drawRect(pad.toFloat(), pad.toFloat(), (width - pad).toFloat(), (height - pad).toFloat(), border)
        c.drawCircle(width - pad / 2f, height - pad / 2f, pad / 2f, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = if (e.x > width - handle && e.y > height - handle) 1 else 0
                sx = e.rawX; sy = e.rawY
                ox = lp.x; oy = lp.y; ow = lp.width; oh = lp.height
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - sx).toInt()
                val dy = (e.rawY - sy).toInt()
                if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                if (moved) {
                    val dm = context.resources.displayMetrics
                    if (mode == 0) {
                        lp.x = (ox + dx).coerceIn(0, maxOf(0, dm.widthPixels - lp.width))
                        lp.y = (oy + dy).coerceIn(0, maxOf(0, dm.heightPixels - lp.height))
                    } else {
                        lp.width = (ow + dx).coerceIn(minSize, maxOf(minSize, dm.widthPixels - lp.x))
                        lp.height = (oh + dy).coerceIn(minSize, maxOf(minSize, dm.heightPixels - lp.y))
                    }
                    push(false)
                }
            }
            MotionEvent.ACTION_UP -> if (moved) { push(true); onDone() } else if (mode == 0) onTap()
            MotionEvent.ACTION_CANCEL -> if (moved) onDone()
        }
        return true
    }
}
