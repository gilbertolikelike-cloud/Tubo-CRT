package com.example.ocrclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Motor de cliques. Roda numa thread própria.
 *  - box == null  -> modo simples: clica no alvo a cada N ms (tempo absoluto, sem drift).
 *  - box != null  -> modo OCR: captura a tela, lê só a área da caixa, clica se achar a palavra,
 *                    espera o intervalo e lê de novo.
 */
class ClickEngine(
    private val svc: AccessibilityService,
    private val cfg: Cfg,
    private val status: (String) -> Unit,   // chamado da thread de trabalho
    private val onFinished: () -> Unit      // chamado da thread de trabalho
) {
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun isRunning() = running.get()

    fun start(target: Point, box: Rect?) {
        if (!running.compareAndSet(false, true)) return
        val t = Thread {
            try {
                if (box == null) runPlain(target) else runOcr(target, box)
            } catch (e: Exception) {
                status("erro: ${e.javaClass.simpleName}")
            } finally {
                running.set(false)
                onFinished()
            }
        }
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        thread?.let { LockSupport.unpark(it) }
    }

    fun release() {
        stop()
        try { recognizer.close() } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ modo simples
    private fun runPlain(target: Point) {
        val interval = cfg.clickIntervalMs.coerceAtLeast(20L)
        val gesture = tap(target)                       // gesto criado UMA vez (cache)
        val period = interval * 1_000_000L
        var next = System.nanoTime()
        var n = 0L
        while (running.get()) {
            try {
                svc.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                status("serviço parou")
                break
            }
            n++
            if (interval >= 250 || n % 10L == 0L) status("cliques: $n")
            next += period                              // tempo ABSOLUTO: não acumula atraso
            if (next < System.nanoTime()) next = System.nanoTime()
            waitUntil(next)
        }
    }

    // ------------------------------------------------------------------ modo OCR
    private fun runOcr(fixed: Point, box: Rect) {
        val word = cfg.word
        val ignoreCase = cfg.ignoreCase
        val everyNs = cfg.ocrIntervalMs.coerceAtLeast(500L) * 1_000_000L
        val where = cfg.clickWhere
        val stopAfter = cfg.stopAfterClick
        var clicks = 0

        while (running.get()) {
            status("lendo…")
            var clicked = false
            val shot = capture(box)
            if (!running.get()) {
                shot?.bmp?.recycle()
                break
            }
            if (shot == null) {
                status("sem imagem: ligue a captura")
            } else {
                val text = read(shot.bmp)
                shot.bmp.recycle()
                if (text == null) {
                    status("falha no OCR")
                } else {
                    val hit = find(text, word, ignoreCase)
                    if (hit != null) {
                        val p = when (where) {
                            0 -> Point(
                                shot.ox + (hit.centerX() / shot.scale).toInt(),
                                shot.oy + (hit.centerY() / shot.scale).toInt()
                            )
                            1 -> Point(box.centerX(), box.centerY())
                            else -> fixed
                        }
                        try { svc.dispatchGesture(tap(p), null, null) } catch (e: Exception) { }
                        clicks++
                        clicked = true
                        status("✓ achou! cliques: $clicks")
                    } else {
                        status("✗ li: " + snippet(text.text))
                    }
                }
            }
            if (clicked && stopAfter) break
            waitUntil(System.nanoTime() + everyNs)      // espera o intervalo e lê de novo
        }
    }

    // ------------------------------------------------------------------ captura + OCR
    private class Shot(val bmp: Bitmap, val scale: Float, val ox: Int, val oy: Int)

    private fun capture(box: Rect): Shot? {
        try {
            val fr = ScreenCapture.grab(box) ?: return null
            val crop = fr.bmp
            // texto pequeno lê melhor ampliado
            if (crop.height < 120) {
                val s = 2f
                val big = Bitmap.createScaledBitmap(crop, (crop.width * s).toInt(), (crop.height * s).toInt(), true)
                if (big !== crop) crop.recycle()
                return Shot(big, s, fr.ox, fr.oy)
            }
            return Shot(crop, 1f, fr.ox, fr.oy)
        } catch (e: Exception) {
            return null
        }
    }

    private fun read(bmp: Bitmap): Text? = try {
        Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)
    } catch (e: Exception) {
        null
    }

    /** Procura a palavra/frase no resultado do OCR e devolve o retângulo (no bitmap lido). */
    private fun find(t: Text, word: String, ignoreCase: Boolean): Rect? {
        val w = norm(word, ignoreCase)
        if (w.isEmpty()) return null
        for (b in t.textBlocks) {
            for (line in b.lines) {
                if (!norm(line.text, ignoreCase).contains(w)) continue
                if (!w.contains(' ')) {
                    for (el in line.elements) {
                        if (norm(el.text, ignoreCase).contains(w)) {
                            el.boundingBox?.let { return it }
                        }
                    }
                }
                line.boundingBox?.let { return it }
            }
        }
        return null
    }

    private fun norm(s: String, ignoreCase: Boolean): String {
        val x = s.trim().replace(Regex("\\s+"), " ")
        return if (ignoreCase) x.lowercase(Locale.ROOT) else x
    }

    private fun snippet(s: String): String =
        s.replace('\n', ' ').trim().take(22).ifEmpty { "(nada)" }

    // ------------------------------------------------------------------ utilidades
    private fun tap(p: Point): GestureDescription {
        val path = Path().apply { moveTo(p.x.toFloat(), p.y.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
    }

    /** Dorme até o instante absoluto (System.nanoTime) ou até alguém chamar stop(). */
    private fun waitUntil(deadlineNs: Long) {
        while (running.get()) {
            val w = deadlineNs - System.nanoTime()
            if (w <= 0) return
            LockSupport.parkNanos(w)
        }
    }
}
