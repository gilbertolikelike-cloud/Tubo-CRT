package com.example.ocrclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Condição de parada: mode 0 = nunca, 1 = depois de [seconds] s, 2 = depois de [count] ciclos/cliques. */
class Stop(val mode: Int, val seconds: Long, val count: Int)

class OcrParams(val word: String, val ignoreCase: Boolean, val everyMs: Long, val where: Int)

/**
 * Motor de cliques (thread própria). Três modos:
 *  - único: clica no alvo a cada N ms (tempo absoluto, sem acumular atraso)
 *  - multi: percorre a lista NA ORDEM (1, 2, 3...), cada alvo com seu atraso
 *  - OCR: lê a caixa; achou o texto -> PAUSA os outros cliques, clica no texto, e só retoma
 *         quando o texto some
 *
 * Todos os toques passam por UM funil (GLOCK): nunca dois gestos ao mesmo tempo, então a ordem
 * é sempre respeitada e um clique nunca cancela o outro.
 */
class ClickEngine(
    private val svc: AccessibilityService,
    private val status: (String) -> Unit,   // chamado da thread de trabalho
    private val onFinished: () -> Unit      // idem
) {
    companion object {
        const val TAP_MS = 10L
        private val GLOCK = Any()
        private val gHandler: Handler by lazy {
            val t = HandlerThread("gestures")
            t.start()
            Handler(t.looper)
        }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var paused = false
    @Volatile private var antiOn = false
    private val rnd = Random()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var startNs = 0L
    private var limitNs = 0L
    private var lastSay = 0L

    fun isRunning() = running.get()

    fun startSingle(target: Point, intervalMs: Long, stop: Stop, anti: Boolean) =
        launch(stop, anti, Thread.MAX_PRIORITY) { runSingle(target, intervalMs.coerceAtLeast(20L), stop) }

    fun startMulti(targets: List<Target>, stop: Stop, anti: Boolean) =
        launch(stop, anti, Thread.MAX_PRIORITY) { runMulti(targets, stop, anti) }

    /** [gate]: chamado com true quando o texto foi visto (pausar os outros) e false quando sumiu. */
    fun startOcr(
        fixed: Point, box: Rect, p: OcrParams, stop: Stop, anti: Boolean,
        gate: ((Boolean) -> Unit)? = null
    ) = launch(stop, anti, Thread.NORM_PRIORITY) { runOcr(fixed, box, p, stop, gate) }

    /** Pausa / retoma este motor (usado pelo OCR para segurar os outros cliques). */
    fun pause() { paused = true }

    fun resume() {
        paused = false
        thread?.let { LockSupport.unpark(it) }
    }

    fun stop() {
        running.set(false)
        thread?.let { LockSupport.unpark(it) }
    }

    fun release() {
        stop()
        try { recognizer.close() } catch (e: Exception) { }
    }

    private fun launch(stop: Stop, anti: Boolean, prio: Int, body: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        antiOn = anti
        paused = false
        startNs = System.nanoTime()
        limitNs = if (stop.mode == 1) stop.seconds * 1_000_000_000L else 0L
        val t = Thread {
            try {
                body()
            } catch (e: Exception) {
                say("erro: ${e.javaClass.simpleName}", true)
            } finally {
                val natural = running.get()      // true = terminou sozinho (condição de parada)
                running.set(false)
                if (natural) say("✓ concluído", true)
                onFinished()
            }
        }
        t.priority = prio
        thread = t
        t.start()
    }

    private fun timeUp() = limitNs > 0 && System.nanoTime() - startNs >= limitNs

    /** Texto de status com limite de ~2 por segundo (cada atualização custa uma passada de layout). */
    private fun say(s: String, force: Boolean = false) {
        val now = System.nanoTime()
        if (force || now - lastSay >= 400_000_000L) {
            lastSay = now
            status(s)
        }
    }

    /** Fica parado enquanto o OCR segura os cliques. Devolve true se chegou a pausar. */
    private fun waitWhilePaused(): Boolean {
        if (!paused) return false
        val t0 = System.nanoTime()
        while (paused && running.get() && !timeUp()) LockSupport.parkNanos(40_000_000L)
        startNs += System.nanoTime() - t0           // o tempo parado não conta em "Parar após: tempo"
        return true
    }

    // ------------------------------------------------------------------ alvo único
    private fun runSingle(target: Point, interval: Long, stop: Stop) {
        val cached = tap(target.x, target.y)                 // sem anti-detecção: gesto criado UMA vez
        val sync = interval >= 100                           // intervalos curtos: dispara sem esperar
        var next = System.nanoTime()
        var n = 0
        while (running.get() && !timeUp()) {
            if (waitWhilePaused()) next = System.nanoTime()
            if (!running.get() || timeUp()) break
            val g = if (antiOn) tap(jit(target.x), jit(target.y)) else cached
            if (!dispatch(g, sync, TAP_MS)) { say("serviço parou", true); break }
            n++
            say("cliques: $n")
            if (stop.mode == 2 && n >= stop.count) break
            next += jitMs(interval) * 1_000_000L             // tempo ABSOLUTO: não acumula atraso
            if (next < System.nanoTime()) next = System.nanoTime()
            waitUntil(next)
        }
    }

    // ------------------------------------------------------------------ multi alvos
    private fun gestureFor(t: Target): GestureDescription =
        if (t.type == 1) swipe(jit(t.x), jit(t.y), jit(t.x2), jit(t.y2), t.swipeMs)
        else tap(jit(t.x), jit(t.y))

    private fun runMulti(list: List<Target>, stop: Stop, anti: Boolean) {
        if (list.isEmpty()) {
            say("sem alvos: toque em +", true)
            return
        }
        // sem anti-detecção os gestos são montados UMA vez (nada de alocar a cada toque)
        val cache: List<GestureDescription>? = if (anti) null else list.map { gestureFor(it) }
        var cycles = 0
        outer@ while (running.get() && !timeUp()) {
            for ((i, t) in list.withIndex()) {               // SEMPRE na ordem: 1, 2, 3...
                waitWhilePaused()
                if (!running.get() || timeUp()) break@outer
                val dur = if (t.type == 1) t.swipeMs else TAP_MS
                val t0 = System.nanoTime()
                val g = cache?.get(i) ?: gestureFor(t)
                if (!dispatch(g, true, dur)) { say("serviço parou", true); break@outer }
                say("ciclo ${cycles + 1} · alvo ${i + 1}/${list.size}")
                waitUntil(t0 + (jitMs(t.delayMs) + dur) * 1_000_000L)
            }
            cycles++
            if (stop.mode == 2 && cycles >= stop.count) break
        }
    }

    // ------------------------------------------------------------------ OCR
    private fun runOcr(fixed: Point, box: Rect, p: OcrParams, stop: Stop, gate: ((Boolean) -> Unit)?) {
        val everyMs = p.everyMs.coerceAtLeast(500L)
        val recheckNs = minOf(everyMs, 600L) * 1_000_000L    // depois de clicar, confere logo se o texto sumiu
        var clicks = 0
        try {
            while (running.get() && !timeUp()) {
                say("lendo…")
                var found = false
                val shot = capture(box)
                if (!running.get()) {
                    shot?.bmp?.recycle()
                    break
                }
                if (shot == null) {
                    gate?.invoke(false)
                    say("sem imagem: ligue a captura", true)
                } else {
                    val text = read(shot.bmp)
                    shot.bmp.recycle()
                    if (text == null) {
                        gate?.invoke(false)
                        say("falha no OCR", true)
                    } else {
                        val hit = find(text, p.word, p.ignoreCase)
                        if (hit != null) {
                            found = true
                            gate?.invoke(true)                // viu o texto: segura todos os outros cliques
                            val pt = when (p.where) {
                                0 -> Point(
                                    shot.ox + (hit.centerX() / shot.scale).toInt(),
                                    shot.oy + (hit.centerY() / shot.scale).toInt()
                                )
                                1 -> Point(box.centerX(), box.centerY())
                                else -> fixed
                            }
                            dispatch(tap(jit(pt.x), jit(pt.y)), true, TAP_MS)
                            clicks++
                            say("✓ achou! cliques: $clicks", true)
                            if (stop.mode == 2 && clicks >= stop.count) break
                        } else {
                            gate?.invoke(false)               // texto sumiu: os outros voltam a clicar
                            say("✗ li: " + snippet(text.text), true)
                        }
                    }
                }
                val waitNs = if (found && gate != null) recheckNs else jitMs(everyMs) * 1_000_000L
                waitUntil(System.nanoTime() + waitNs)
            }
        } finally {
            gate?.invoke(false)
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
    /** Anti-Detecção: varia ±4 px na posição e ±10% no tempo, para não parecer um robô. */
    private fun jit(v: Int): Int = if (antiOn) v + rnd.nextInt(9) - 4 else v
    private fun jitMs(v: Long): Long = if (antiOn) (v * (0.9 + rnd.nextDouble() * 0.2)).toLong() else v

    /**
     * Único funil de toques. [wait] = espera o Android terminar o gesto antes de soltar o próximo
     * (garante a ordem e evita que um toque cancele o outro).
     */
    private fun dispatch(g: GestureDescription, wait: Boolean, durMs: Long): Boolean {
        try {
            synchronized(GLOCK) {
                if (!wait) {
                    svc.dispatchGesture(g, null, null)
                    return true
                }
                val latch = CountDownLatch(1)
                val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { latch.countDown() }
                    override fun onCancelled(d: GestureDescription?) { latch.countDown() }
                }, gHandler)
                if (ok) latch.await(durMs + 300L, TimeUnit.MILLISECONDS)
                return true
            }
        } catch (e: Exception) {
            return false
        }
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

    /** Dorme até o instante absoluto (nanoTime), até a condição de tempo, até pausar ou até stop(). */
    private fun waitUntil(deadlineNs: Long) {
        while (running.get() && !timeUp() && !paused) {
            val w = deadlineNs - System.nanoTime()
            if (w <= 0) return
            LockSupport.parkNanos(w)
        }
    }
}
