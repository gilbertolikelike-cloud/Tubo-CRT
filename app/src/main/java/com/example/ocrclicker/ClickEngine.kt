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
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Condição de parada: mode 0 = nunca, 1 = depois de [seconds] s, 2 = depois de [count] ciclos/cliques. */
class Stop(val mode: Int, val seconds: Long, val count: Int)

class OcrParams(val word: String, val ignoreCase: Boolean, val everyMs: Long, val where: Int)

/**
 * Motor de cliques (thread própria). Três modos:
 *  - único: clica no alvo a cada N ms (tempo absoluto, sem acumular atraso)
 *  - multi: percorre a lista de alvos (toques e deslizes), cada um com seu atraso
 *  - OCR: lê a caixa, clica se achar o texto, espera o intervalo e lê de novo
 */
class ClickEngine(
    private val svc: AccessibilityService,
    private val status: (String) -> Unit,   // chamado da thread de trabalho
    private val onFinished: () -> Unit      // idem
) {
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val rnd = Random()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var startNs = 0L
    private var limitNs = 0L

    fun isRunning() = running.get()

    fun startSingle(target: Point, intervalMs: Long, stop: Stop, anti: Boolean) =
        launch(stop, anti) { runSingle(target, intervalMs.coerceAtLeast(20L), stop, anti) }

    fun startMulti(targets: List<Target>, stop: Stop, anti: Boolean) =
        launch(stop, anti) { runMulti(targets, stop, anti) }

    fun startOcr(fixed: Point, box: Rect, p: OcrParams, stop: Stop, anti: Boolean) =
        launch(stop, anti) { runOcr(fixed, box, p, stop, anti) }

    fun stop() {
        running.set(false)
        thread?.let { LockSupport.unpark(it) }
    }

    fun release() {
        stop()
        try { recognizer.close() } catch (e: Exception) { }
    }

    private fun launch(stop: Stop, anti: Boolean, body: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        antiOn = anti
        startNs = System.nanoTime()
        limitNs = if (stop.mode == 1) stop.seconds * 1_000_000_000L else 0L
        val t = Thread {
            try {
                body()
            } catch (e: Exception) {
                status("erro: ${e.javaClass.simpleName}")
            } finally {
                val natural = running.get()      // true = terminou sozinho (condição de parada)
                running.set(false)
                if (natural) status("✓ concluído")
                onFinished()
            }
        }
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    private fun timeUp() = limitNs > 0 && System.nanoTime() - startNs >= limitNs

    // ------------------------------------------------------------------ alvo único
    private fun runSingle(target: Point, interval: Long, stop: Stop, anti: Boolean) {
        val fixedGesture = tap(target.x, target.y)           // sem anti-detecção: gesto criado UMA vez
        var next = System.nanoTime()
        var n = 0
        while (running.get() && !timeUp()) {
            val g = if (anti) tap(jit(target.x), jit(target.y)) else fixedGesture
            if (!dispatch(g)) { status("serviço parou"); break }
            n++
            if (interval >= 250 || n % 10 == 0) status("cliques: $n")
            if (stop.mode == 2 && n >= stop.count) break
            next += jitMs(interval) * 1_000_000L              // tempo ABSOLUTO: não acumula atraso
            if (next < System.nanoTime()) next = System.nanoTime()
            waitUntil(next)
        }
    }

    // ------------------------------------------------------------------ multi alvos
    private fun runMulti(list: List<Target>, stop: Stop, anti: Boolean) {
        if (list.isEmpty()) {
            status("sem alvos: toque em +")
            return
        }
        var cycles = 0
        outer@ while (running.get() && !timeUp()) {
            for ((i, t) in list.withIndex()) {
                if (!running.get() || timeUp()) break@outer
                val g: GestureDescription
                val dur: Long
                if (t.type == 1) {
                    g = swipe(jit(t.x), jit(t.y), jit(t.x2), jit(t.y2), t.swipeMs)
                    dur = t.swipeMs
                } else {
                    g = tap(jit(t.x), jit(t.y))
                    dur = TAP_MS
                }
                if (!dispatch(g)) { status("serviço parou"); break@outer }
                status("ciclo ${cycles + 1} · alvo ${i + 1}/${list.size}")
                waitUntil(System.nanoTime() + (jitMs(t.delayMs) + dur) * 1_000_000L)
            }
            cycles++
            if (stop.mode == 2 && cycles >= stop.count) break
        }
    }

    // ------------------------------------------------------------------ OCR
    private fun runOcr(fixed: Point, box: Rect, p: OcrParams, stop: Stop, anti: Boolean) {
        val everyNs = p.everyMs.coerceAtLeast(500L) * 1_000_000L
        var clicks = 0
        while (running.get() && !timeUp()) {
            status("lendo…")
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
                    val hit = find(text, p.word, p.ignoreCase)
                    if (hit != null) {
                        val pt = when (p.where) {
                            0 -> Point(
                                shot.ox + (hit.centerX() / shot.scale).toInt(),
                                shot.oy + (hit.centerY() / shot.scale).toInt()
                            )
                            1 -> Point(box.centerX(), box.centerY())
                            else -> fixed
                        }
                        dispatch(tap(jit(pt.x), jit(pt.y)))
                        clicks++
                        status("✓ achou! cliques: $clicks")
                        if (stop.mode == 2 && clicks >= stop.count) break
                    } else {
                        status("✗ li: " + snippet(text.text))
                    }
                }
            }
            waitUntil(System.nanoTime() + jitMs(everyNs / 1_000_000L) * 1_000_000L)
        }
    }

    private class Shot(val bmp: Bitmap, val scale: Float, val ox: Int, val oy: Int)

    private fun capture(box: Rect): Shot? {
        try {
            val fr = ScreenCapture.grab(box) ?: return null
            val crop = fr.bmp
            if (crop.height < 120) {                          // texto pequeno lê melhor ampliado
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
    companion object {
        const val TAP_MS = 10L
    }

    /** Anti-Detecção: varia ±4 px na posição e ±10% no tempo, para não parecer um robô. */
    @Volatile private var antiOn = false

    private fun jit(v: Int): Int = if (antiOn) v + rnd.nextInt(9) - 4 else v
    private fun jitMs(v: Long): Long = if (antiOn) (v * (0.9 + rnd.nextDouble() * 0.2)).toLong() else v

    private fun dispatch(g: GestureDescription): Boolean = try {
        svc.dispatchGesture(g, null, null)
        true
    } catch (e: Exception) {
        false
    }

    private fun tap(x: Int, y: Int): GestureDescription {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): GestureDescription {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceIn(1L, 60_000L)))
            .build()
    }

    /** Dorme até o instante absoluto (nanoTime), até a condição de tempo ou até stop(). */
    private fun waitUntil(deadlineNs: Long) {
        while (running.get() && !timeUp()) {
            val w = deadlineNs - System.nanoTime()
            if (w <= 0) return
            LockSupport.parkNanos(w)
        }
    }
}
