package com.example.ocrclicker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()

// =====================================================================================
// Ícones desenhados no código (sem imagens): play, +, deslize, −, engrenagem, mover...
// =====================================================================================
class IconView(ctx: Context, t: Int, private val tint: Int? = null) : View(ctx) {
    companion object {
        const val PLAY = 0
        const val STOP = 1
        const val PLUS = 2
        const val MINUS = 3
        const val SWIPE = 4
        const val GEAR = 5
        const val MOVE = 6
        const val CLOSE = 7
        const val INFO = 8
        const val HELP = 9
        const val WRENCH = 10
        const val TARGET = 11
        const val BOX = 12
    }

    var type: Int = t
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun tri(c: Canvas, a: Float, b: Float, d: Float, e: Float, f: Float, g: Float) {
        val p = Path()
        p.moveTo(a, b); p.lineTo(d, e); p.lineTo(f, g); p.close()
        c.drawPath(p, fill)
    }

    override fun onDraw(c: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val s = minOf(w, h).toFloat()
        if (s <= 0f) return
        c.save()
        c.translate(paddingLeft + (w - s) / 2f, paddingTop + (h - s) / 2f)
        c.scale(s / 24f, s / 24f)
        when (type) {
            PLAY -> {
                fill.color = tint ?: 0xFF2979FF.toInt()
                tri(c, 4f, 2f, 4f, 22f, 21f, 12f)
            }
            STOP -> {
                fill.color = tint ?: 0xFF2979FF.toInt()
                c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 2f, 2f, fill)
            }
            PLUS -> {
                fill.color = tint ?: 0xFF4CAF50.toInt()
                c.drawRect(10f, 2f, 14f, 22f, fill)
                c.drawRect(2f, 10f, 22f, 14f, fill)
            }
            MINUS -> {
                fill.color = tint ?: 0xFFD84315.toInt()
                c.drawRoundRect(RectF(2f, 10f, 22f, 14f), 2f, 2f, fill)
            }
            SWIPE -> {
                val col = tint ?: 0xFFFFA726.toInt()
                line.color = col; line.strokeWidth = 3.2f
                val p = Path()
                p.moveTo(4f, 21f); p.quadTo(4f, 9f, 15f, 9f)
                c.drawPath(p, line)
                fill.color = col
                tri(c, 13f, 3f, 22f, 9f, 13f, 15f)
            }
            GEAR -> {
                val col = tint ?: 0xFF78909C.toInt()
                fill.color = col
                for (k in 0 until 8) {
                    c.save()
                    c.rotate(k * 45f, 12f, 12f)
                    c.drawRect(10.2f, 1.5f, 13.8f, 6.5f, fill)
                    c.restore()
                }
                line.color = col; line.strokeWidth = 4f
                c.drawCircle(12f, 12f, 5.5f, line)
            }
            MOVE -> {
                val col = tint ?: 0xFFB0B0B0.toInt()
                line.color = col; line.strokeWidth = 1.8f
                c.drawLine(12f, 4f, 12f, 20f, line)
                c.drawLine(4f, 12f, 20f, 12f, line)
                fill.color = col
                tri(c, 12f, 1.5f, 8.5f, 5.5f, 15.5f, 5.5f)
                tri(c, 12f, 22.5f, 8.5f, 18.5f, 15.5f, 18.5f)
                tri(c, 1.5f, 12f, 5.5f, 8.5f, 5.5f, 15.5f)
                tri(c, 22.5f, 12f, 18.5f, 8.5f, 18.5f, 15.5f)
            }
            CLOSE -> {
                line.color = tint ?: 0xFFCFCFCF.toInt(); line.strokeWidth = 3f
                c.drawLine(5f, 5f, 19f, 19f, line)
                c.drawLine(19f, 5f, 5f, 19f, line)
            }
            INFO -> {
                fill.color = tint ?: 0xFF9E9E9E.toInt()
                c.drawCircle(12f, 12f, 10f, fill)
                fill.color = 0xFF212121.toInt()
                c.drawCircle(12f, 7.6f, 1.5f, fill)
                c.drawRect(10.8f, 10.4f, 13.2f, 18f, fill)
            }
            HELP -> {
                fill.color = tint ?: 0xFF9E9E9E.toInt()
                c.drawCircle(12f, 12f, 10f, fill)
                txt.color = 0xFF212121.toInt(); txt.textSize = 16f
                c.drawText("?", 12f, 17.5f, txt)
            }
            WRENCH -> {
                line.color = tint ?: 0xFF9E9E9E.toInt(); line.strokeWidth = 3.6f
                c.drawLine(5f, 19f, 14f, 10f, line)
                line.strokeWidth = 2.6f
                c.drawCircle(16.5f, 7.5f, 4.2f, line)
            }
            TARGET -> {
                line.color = 0xFF2979FF.toInt(); line.strokeWidth = 2.2f
                c.drawCircle(12f, 12f, 8.5f, line)
                fill.color = 0xFFFF1744.toInt()
                c.drawCircle(12f, 12f, 1.8f, fill)
            }
            BOX -> {
                line.color = 0xFF00E676.toInt(); line.strokeWidth = 2f
                c.drawRect(3f, 5f, 21f, 19f, line)
                fill.color = 0xFF00E676.toInt()
                c.drawCircle(21f, 19f, 2.6f, fill)
            }
        }
        c.restore()
    }
}

// =====================================================================================
// Visual comum
// =====================================================================================
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val BG = 0xFF212121.toInt()
    val CARD = 0xFF2B2B2B.toInt()
    val TEXT = 0xFFEEEEEE.toInt()
    val TEXT2 = 0xFFB0B0B0.toInt()
    val PINK = 0xFFE91E63.toInt()
    val BLUE = 0xFF2196F3.toInt()
    val WARN = 0xFFFFA000.toInt()
    val LINE = 0xFF444444.toInt()
    /** Cor do painel flutuante (mesmo azul-índigo escuro do Reddit). */
    val BAR = 0xF2040017.toInt()

    fun text(ctx: Context, s: String, sp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = ctx.dp(6).toFloat()
        }
        c.setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(12))
        c.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) }
        return c
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(LINE)
        v.layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(1)).apply {
            topMargin = ctx.dp(10); bottomMargin = ctx.dp(10)
        }
        return v
    }

    fun row(ctx: Context, icon: Int, label: String, enabled: Boolean, onClick: () -> Unit): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, ctx.dp(10), 0, ctx.dp(10))
        val ic = IconView(ctx, icon, 0xFF9E9E9E.toInt())
        ic.layoutParams = LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { rightMargin = ctx.dp(14) }
        r.addView(ic)
        r.addView(text(ctx, label, 15f))
        r.alpha = if (enabled) 1f else 0.4f
        if (enabled) r.setOnClickListener { onClick() }
        return r
    }

    fun bigButton(ctx: Context, label: String, color: Int, enabled: Boolean, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = true
        b.setTextColor(Color.WHITE)
        b.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = ctx.dp(3).toFloat()
        }
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(46)).apply { topMargin = ctx.dp(6) }
        return b
    }

    fun editLp() = LinearLayout.LayoutParams(MATCH, WRAP)

    fun hms(sec: Int) = String.format("%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)

    // ------------------------------------------------------------ diálogos
    fun dctx(base: Context): Context = ContextThemeWrapper(base, R.style.DlgTheme)

    fun scrollPad(ctx: Context, v: View): View {
        val s = ScrollView(ctx)
        s.setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(4))
        s.addView(v)
        return s
    }

    /** overlay = true quando o diálogo abre a partir do serviço (por cima de outros apps). */
    fun alert(
        base: Context, overlay: Boolean, title: String?, view: View?,
        ok: String?, cancel: String?, onOk: () -> Unit = {}
    ): AlertDialog {
        val b = AlertDialog.Builder(dctx(base))
        if (title != null) b.setTitle(title)
        if (view != null) b.setView(view)
        if (ok != null) b.setPositiveButton(ok) { _, _ -> onOk() }
        if (cancel != null) b.setNegativeButton(cancel, null)
        val d = b.create()
        if (overlay) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
        return d
    }

    fun input(base: Context, overlay: Boolean, title: String, initial: String, onOk: (String) -> Unit) {
        val c = dctx(base)
        val et = EditText(c)
        et.setText(initial)
        et.setSingleLine()
        et.setSelectAllOnFocus(true)
        val box = FrameLayout(c)
        box.setPadding(c.dp(20), c.dp(8), c.dp(20), 0)
        box.addView(et)
        alert(base, overlay, title, box, "SALVAR", "CANCELAR") { onOk(et.text.toString().trim()) }
    }

    fun timeDialog(base: Context, overlay: Boolean, cur: Int, onOk: (Int) -> Unit) {
        val c = dctx(base)
        fun np(max: Int, v: Int): NumberPicker {
            val n = NumberPicker(c)
            n.minValue = 0; n.maxValue = max; n.value = v
            return n
        }
        val h = np(99, cur / 3600)
        val m = np(59, cur / 60 % 60)
        val s = np(59, cur % 60)
        val box = LinearLayout(c)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.setPadding(0, c.dp(16), 0, 0)
        box.addView(h); box.addView(text(c, " h  ", 14f, TEXT2))
        box.addView(m); box.addView(text(c, " min  ", 14f, TEXT2))
        box.addView(s); box.addView(text(c, " s", 14f, TEXT2))
        alert(base, overlay, "Quantidade de tempo", box, "SALVAR", "CANCELAR") {
            onOk((h.value * 3600 + m.value * 60 + s.value).coerceAtLeast(1))
        }
    }

    /** "Gerenciador de configurações": NOVA CONFIGURAÇÃO / OU / lista. Toque longo = excluir. */
    fun configList(
        base: Context, overlay: Boolean, store: Store,
        onPick: (Int) -> Unit, onNew: () -> Unit
    ) {
        val c = dctx(base)
        val list = store.configs()
        var dlg: AlertDialog? = null
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(c.dp(20), c.dp(10), c.dp(20), 0)

        val nb = Button(c)
        nb.text = "NOVA CONFIGURAÇÃO"
        nb.setTextColor(PINK)
        nb.setOnClickListener { dlg?.dismiss(); onNew() }
        col.addView(nb)

        val ou = text(c, "OU", 12f, TEXT2)
        ou.gravity = Gravity.CENTER
        ou.setPadding(0, c.dp(10), 0, c.dp(6))
        col.addView(ou, LinearLayout.LayoutParams(MATCH, WRAP))
        val sel = text(c, "Selecione uma configuração", 18f, TEXT)
        sel.gravity = Gravity.CENTER
        sel.setPadding(0, 0, 0, c.dp(6))
        col.addView(sel, LinearLayout.LayoutParams(MATCH, WRAP))

        list.forEachIndexed { idx, cfg ->
            val t = text(c, "•  " + cfg.name, 15f)
            t.setPadding(c.dp(6), c.dp(10), c.dp(6), c.dp(10))
            t.setOnClickListener { dlg?.dismiss(); onPick(idx) }
            t.setOnLongClickListener {
                alert(base, overlay, "Excluir \"" + cfg.name + "\"?", null, "EXCLUIR", "CANCELAR") {
                    list.removeAt(idx)
                    store.saveConfigs(list)
                    store.activeConfig = 0
                    dlg?.dismiss()
                }
                true
            }
            col.addView(t)
        }
        col.addView(text(c, "Toque longo em uma configuração para excluir", 11f, TEXT2))
        dlg = alert(base, overlay, null, ScrollView(c).apply { addView(col) }, null, "CANCELAR")
    }
}

// =====================================================================================
// Campo "número + unidade (ms / s / min)"
// =====================================================================================
class IntervalField(ctx: Context) : LinearLayout(ctx) {
    private val mult = longArrayOf(1L, 1000L, 60000L)
    private val et = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSelectAllOnFocus(true)
        gravity = Gravity.CENTER
        minEms = 4
    }
    private val unit = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, arrayOf("ms", "s", "min"))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(et)
        addView(unit)
    }

    fun set(ms: Long) {
        val idx = when {
            ms > 0 && ms % 60000L == 0L -> 2
            ms % 1000L == 0L -> 1
            else -> 0
        }
        unit.setSelection(idx)
        et.setText((ms / mult[idx]).toString())
    }

    fun ms(): Long {
        val v = et.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        return (v * mult[unit.selectedItemPosition]).toLong()
    }
}

// =====================================================================================
// "Parar após" + Anti-Detecção
// =====================================================================================
class StopPanel(ctx: Context, private val overlay: Boolean, countLabel: String) : LinearLayout(ctx) {
    private val radios = ArrayList<RadioButton>()
    private var secs = 300
    private val timeTv = Ui.text(ctx, "00:05:00", 14f, Ui.BLUE)
    private val countEt = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText("1")
        gravity = Gravity.CENTER
        minEms = 3
    }
    private val antiCb = CheckBox(ctx).apply { text = "Anti-Detecção" }

    init {
        orientation = VERTICAL
        addView(Ui.text(ctx, "Parar após", 18f, Ui.TEXT, true))
        addView(Ui.text(ctx, "Escolha uma condição para parar", 14f, Ui.TEXT2))
        addView(rowOf(ctx, "Executar indefinidamente", null))
        addView(rowOf(ctx, "Quantidade de tempo:", timeTv))
        addView(rowOf(ctx, countLabel, countEt))
        addView(Ui.divider(ctx))
        addView(antiCb)
        timeTv.setOnClickListener {
            Ui.timeDialog(ctx, overlay, secs) {
                secs = it
                timeTv.text = Ui.hms(it)
                select(1)
            }
        }
        select(0)
    }

    private fun rowOf(ctx: Context, label: String, extra: View?): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val rb = RadioButton(ctx)
        rb.text = label
        val idx = radios.size
        rb.setOnClickListener { select(idx) }
        radios.add(rb)
        r.addView(rb)
        if (extra != null) {
            val lp = LayoutParams(Ui.WRAP, Ui.WRAP)
            lp.leftMargin = ctx.dp(8)
            r.addView(extra, lp)
        }
        return r
    }

    private fun select(i: Int) {
        for (j in radios.indices) radios[j].isChecked = (j == i)
    }

    fun load(mode: Int, seconds: Int, count: Int, anti: Boolean) {
        secs = seconds
        timeTv.text = Ui.hms(seconds)
        countEt.setText(count.toString())
        antiCb.isChecked = anti
        select(mode.coerceIn(0, 2))
    }

    val stopMode: Int get() = radios.indexOfFirst { it.isChecked }.coerceAtLeast(0)
    val stopSeconds: Int get() = secs
    val stopCount: Int get() = (countEt.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1)
    val antiOn: Boolean get() = antiCb.isChecked
}

// =====================================================================================
// Painéis de configuração (usados na tela do app E nos diálogos da barra flutuante)
// =====================================================================================
abstract class SettingsPanel(ctx: Context) : LinearLayout(ctx) {
    abstract fun load(s: Store)
    abstract fun save(s: Store)
}

class SinglePanel(ctx: Context, overlay: Boolean) : SettingsPanel(ctx) {
    private val interval = IntervalField(ctx)
    private val stop = StopPanel(ctx, overlay, "Número de ciclos:")

    init {
        orientation = VERTICAL
        addView(Ui.text(ctx, "Intervalo dos cliques", 18f, Ui.TEXT, true))
        addView(Ui.text(ctx, "O tempo de intervalo entre cada clique", 14f, Ui.TEXT2))
        addView(Ui.text(ctx, "O tempo abaixo de 40 milissegundos pode travar seu dispositivo", 11f, Ui.WARN))
        addView(interval)
        addView(Ui.divider(ctx))
        addView(stop)
    }

    override fun load(s: Store) {
        interval.set(s.sIntervalMs)
        stop.load(s.sStopMode, s.sStopSeconds, s.sStopCount, s.sAnti)
    }

    override fun save(s: Store) {
        s.sIntervalMs = interval.ms().coerceAtLeast(20L)
        s.sStopMode = stop.stopMode
        s.sStopSeconds = stop.stopSeconds
        s.sStopCount = stop.stopCount
        s.sAnti = stop.antiOn
    }
}

class MultiDefaultsPanel(ctx: Context) : SettingsPanel(ctx) {
    private val delay = IntervalField(ctx)
    private val swipe = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        gravity = Gravity.CENTER
        minEms = 4
    }

    init {
        orientation = VERTICAL
        addView(Ui.text(ctx, "Configurações padrão para cada destino", 14f, Ui.BLUE))
        addView(Ui.text(ctx, "Atraso", 18f, Ui.TEXT, true).apply { setPadding(0, ctx.dp(10), 0, 0) })
        addView(Ui.text(ctx, "O tempo de atraso antes do próximo clique", 14f, Ui.TEXT2))
        addView(Ui.text(ctx, "O tempo abaixo de 40 milissegundos pode travar seu dispositivo", 11f, Ui.WARN))
        addView(delay)
        addView(Ui.divider(ctx))
        addView(Ui.text(ctx, "Duração de deslize", 18f, Ui.TEXT, true))
        addView(Ui.text(ctx, "A duração deve ser superior a 300ms", 14f, Ui.TEXT2))
        val r = LinearLayout(ctx)
        r.gravity = Gravity.CENTER
        r.addView(swipe)
        r.addView(Ui.text(ctx, " Milissegundo(s)", 14f, Ui.TEXT2))
        addView(r)
    }

    override fun load(s: Store) {
        delay.set(s.mDelayMs)
        swipe.setText(s.mSwipeMs.toString())
    }

    override fun save(s: Store) {
        s.mDelayMs = delay.ms().coerceAtLeast(1L)
        s.mSwipeMs = (swipe.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(300L)
    }
}

class OcrPanel(ctx: Context, overlay: Boolean) : SettingsPanel(ctx) {
    private val wordEt = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        hint = "Ex.: YES"
        setSingleLine()
        layoutParams = Ui.editLp()
    }
    private val caseCb = CheckBox(ctx).apply { text = "Ignorar maiúsculas/minúsculas" }
    private val interval = IntervalField(ctx)
    private val whereG = RadioGroup(ctx)
    private val stop = StopPanel(ctx, overlay, "Número de cliques:")

    init {
        orientation = VERTICAL
        addView(Ui.text(ctx, "Texto que o app procura na caixa", 18f, Ui.TEXT, true))
        addView(Ui.text(ctx, "Se o texto estiver dentro da caixa verde, ele clica", 14f, Ui.TEXT2))
        addView(wordEt)
        addView(caseCb)
        addView(Ui.divider(ctx))
        addView(Ui.text(ctx, "Tempo para ler de novo", 18f, Ui.TEXT, true))
        addView(Ui.text(ctx, "Se não achar o texto, espera esse tempo e lê outra vez", 14f, Ui.TEXT2))
        addView(Ui.text(ctx, "Mínimo: 500 milissegundos", 11f, Ui.WARN))
        addView(interval)
        addView(Ui.divider(ctx))
        addView(Ui.text(ctx, "Onde clicar quando achar", 18f, Ui.TEXT, true))
        for (l in listOf("No texto encontrado", "No centro da caixa", "No alvo (ponto fixo)")) {
            val rb = RadioButton(ctx)
            rb.text = l
            rb.id = View.generateViewId()
            whereG.addView(rb)
        }
        addView(whereG)
        addView(Ui.divider(ctx))
        addView(stop)
    }

    override fun load(s: Store) {
        wordEt.setText(s.word)
        caseCb.isChecked = s.ignoreCase
        interval.set(s.ocrIntervalMs)
        whereG.check(whereG.getChildAt(s.where.coerceIn(0, 2)).id)
        stop.load(s.oStopMode, s.oStopSeconds, s.oStopCount, s.oAnti)
    }

    override fun save(s: Store) {
        s.word = wordEt.text.toString().trim()
        s.ignoreCase = caseCb.isChecked
        s.ocrIntervalMs = interval.ms().coerceAtLeast(500L)
        s.where = whereG.indexOfChild(whereG.findViewById(whereG.checkedRadioButtonId)).coerceAtLeast(0)
        s.oStopMode = stop.stopMode
        s.oStopSeconds = stop.stopSeconds
        s.oStopCount = stop.stopCount
        s.oAnti = stop.antiOn
    }
}
