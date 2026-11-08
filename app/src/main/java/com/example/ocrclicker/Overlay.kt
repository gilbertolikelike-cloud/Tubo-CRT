package com.example.ocrclicker

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Tudo que aparece por cima de outros apps:
 *  - barra flutuante (▶/■, mover, configurações, fechar + texto de status)
 *  - alvo (círculo arrastável)               -> modo simples, ou OCR com "ponto fixo"
 *  - caixa de leitura OCR (arrastar/redimensionar pelo canto)
 */
class Overlay(private val svc: AutoClickService, private val cfg: Cfg) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = svc.resources.displayMetrics.density
    private val scrW = svc.resources.displayMetrics.widthPixels
    private val scrH = svc.resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * density).toInt()
    private val pad = dp(16)

    private val ocrMode = cfg.modeOcr
    private val showTarget = !ocrMode || cfg.clickWhere == 2

    // ---------------------------------------------------------------- barra
    private val playBtn = glyph("▶", 0xFF2196F3.toInt())
    private val moveBtn = glyph("✥", 0xFFBBBBBB.toInt())
    private val settingsBtn = glyph("⚙", 0xFF90A4AE.toInt())
    private val closeBtn = glyph("✕", 0xFFFF5252.toInt())
    private val statusTv = TextView(svc).apply {
        textSize = 10f
        setTextColor(Color.WHITE)
        maxLines = 3
        maxWidth = dp(84)
        gravity = Gravity.CENTER
        setPadding(dp(4), 0, dp(4), dp(6))
    }
    private val bar = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xDD202020.toInt())
            cornerRadius = dp(12).toFloat()
        }
        addView(playBtn)
        addView(moveBtn)
        addView(settingsBtn)
        addView(closeBtn)
        addView(statusTv)
    }
    private val barLp = params(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (cfg.barX >= 0) cfg.barX else 0,
        if (cfg.barY >= 0) cfg.barY else scrH / 4
    )

    // ---------------------------------------------------------------- alvo
    private val targetSize = dp(56)
    private val targetView = TargetView(svc)
    private val targetLp = params(
        targetSize, targetSize,
        if (cfg.targetX >= 0) cfg.targetX else scrW / 2 - targetSize / 2,
        if (cfg.targetY >= 0) cfg.targetY else scrH / 2
    )

    // ---------------------------------------------------------------- caixa OCR
    private val boxLp = params(
        if (cfg.boxW > 0) cfg.boxW else dp(240),
        if (cfg.boxH > 0) cfg.boxH else dp(110),
        if (cfg.boxX >= 0) cfg.boxX else scrW / 2 - dp(120),
        if (cfg.boxY >= 0) cfg.boxY else scrH / 3
    )
    private val boxView = BoxView(svc, wm, boxLp, pad, dp(48), dp(40) + 2 * pad) { savePositions() }

    // ---------------------------------------------------------------- motor
    private val engine = ClickEngine(
        svc, cfg,
        status = { s -> main.post { statusTv.text = s } },
        onFinished = { main.post { setRunningUi(false) } }
    )

    init {
        playBtn.setOnClickListener { toggle() }
        settingsBtn.setOnClickListener {
            try {
                svc.startActivity(
                    Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Toast.makeText(svc, "Abra o app para mudar as configurações", Toast.LENGTH_SHORT).show()
            }
        }
        closeBtn.setOnClickListener { svc.hideOverlay(); CaptureService.stop(svc) }
        drag(moveBtn, bar, barLp)
        drag(targetView, targetView, targetLp)
    }

    fun show() {
        try {
            if (ocrMode) wm.addView(boxView, boxLp)
            if (showTarget) wm.addView(targetView, targetLp)
            wm.addView(bar, barLp)                 // por último = fica por cima
        } catch (e: Exception) {
            Toast.makeText(svc, "Não consegui mostrar a barra: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun destroy() {
        engine.release()
        for (v in listOf<View>(bar, targetView, boxView)) {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- executar / parar
    private fun toggle() {
        if (engine.isRunning()) engine.stop() else startRun()
    }

    private fun startRun() {
        val loc = IntArray(2)
        targetView.getLocationOnScreen(loc)
        val target = Point(loc[0] + targetSize / 2, loc[1] + targetSize / 2)
        val region = if (ocrMode) region() else null
        setRunningUi(true)
        engine.start(target, region)
    }

    /** Área lida pelo OCR, em coordenadas de tela (sem a borda e sem a alça). */
    private fun region(): Rect {
        val loc = IntArray(2)
        boxView.getLocationOnScreen(loc)
        val i = pad + dp(3)
        return Rect(loc[0] + i, loc[1] + i, loc[0] + boxView.width - i, loc[1] + boxView.height - i)
    }

    private fun setRunningUi(on: Boolean) {
        playBtn.text = if (on) "■" else "▶"
        // enquanto roda, alvo e caixa deixam o toque passar (senão o clique bateria neles)
        setTouchable(boxView, boxLp, !on)
        setTouchable(targetView, targetLp, !on)
        barLp.flags = if (on) {
            barLp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            barLp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try { wm.updateViewLayout(bar, barLp) } catch (e: Exception) { }
    }

    private fun setTouchable(v: View, lp: WindowManager.LayoutParams, touchable: Boolean) {
        if (!v.isAttachedToWindow) return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- utilidades
    private fun savePositions() {
        cfg.barX = barLp.x; cfg.barY = barLp.y
        cfg.targetX = targetLp.x; cfg.targetY = targetLp.y
        cfg.boxX = boxLp.x; cfg.boxY = boxLp.y; cfg.boxW = boxLp.width; cfg.boxH = boxLp.height
    }

    private fun params(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        return lp
    }

    private fun glyph(t: String, color: Int) = TextView(svc).apply {
        text = t
        textSize = 24f
        setTextColor(color)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }

    private fun drag(handle: View, win: View, lp: WindowManager.LayoutParams) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    try { wm.updateViewLayout(win, lp) } catch (e: Exception) { }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePositions()
            }
            true
        }
    }
}

/** Círculo azul com ponto vermelho no centro. */
class TargetView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFF2196F3.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332196F3 }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 4 * d
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, 3 * d, dot)
    }
}

/**
 * Caixa de leitura OCR. A janela tem uma margem (pad) em volta; a área lida é só o retângulo
 * de dentro. A bolinha no canto inferior direito (na margem) redimensiona; o resto move.
 */
class BoxView(
    ctx: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val pad: Int,
    private val handle: Int,
    private val minSize: Int,
    private val onDone: () -> Unit
) : View(ctx) {

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
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - sx).toInt()
                val dy = (e.rawY - sy).toInt()
                if (mode == 0) {
                    lp.x = ox + dx
                    lp.y = oy + dy
                } else {
                    lp.width = maxOf(minSize, ow + dx)
                    lp.height = maxOf(minSize, oh + dy)
                }
                try { wm.updateViewLayout(this, lp) } catch (e: Exception) { }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDone()
        }
        return true
    }
}
