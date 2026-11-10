package com.example.ocrclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pedaço da tela já recortado + posição dele na tela. */
class Frame(val bmp: Bitmap, val ox: Int, val oy: Int)

/**
 * Captura de tela via MediaProjection (funciona no Android 10).
 *
 * OTIMIZAÇÃO: a captura fica DESLIGADA (surface nula = o Android nem desenha para ela) e só liga
 * no instante de cada leitura do OCR: liga, espera 1 quadro novo, desliga. Entre as leituras o
 * custo de GPU/bateria é zero. Só os pixels da caixa são copiados (não a tela inteira).
 */
object ScreenCapture {
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var waiter: CountDownLatch? = null

    @Volatile
    var active = false
        private set

    private val onImage = ImageReader.OnImageAvailableListener { rd ->
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
            synchronized(lock) {
                latest?.close()
                latest = img
            }
            waiter?.countDown()
        }
    }

    fun start(ctx: Context, code: Int, data: Intent) {
        stop()
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)

        val t = HandlerThread("capture").apply { start() }
        val h = Handler(t.looper)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, h)

        val r = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener(onImage, h)
        val d = p.createVirtualDisplay(
            "ocr-capture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        try { d.surface = null } catch (e: Exception) { }     // começa DESLIGADA
        synchronized(lock) {
            projection = p; reader = r; display = d; thread = t; handler = h
            active = true
        }
    }

    /** Chamado quando a tela gira: refaz o tamanho da captura. */
    fun resize(ctx: Context) {
        synchronized(lock) {
            val d = display ?: return
            val h = handler ?: return
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            val old = reader
            val r = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener(onImage, h)
            latest?.close(); latest = null
            d.resize(m.widthPixels, m.heightPixels, m.densityDpi)
            reader = r                                          // continua desligada até a próxima leitura
            old?.close()
        }
    }

    fun stop() {
        var p: MediaProjection? = null
        var t: HandlerThread? = null
        synchronized(lock) {
            active = false
            latest?.close(); latest = null
            display?.release(); display = null
            reader?.close(); reader = null
            p = projection; projection = null
            t = thread; thread = null
            handler = null
        }
        waiter?.countDown()
        try { p?.stop() } catch (e: Exception) { }
        t?.quitSafely()
    }

    /**
     * Liga a captura, espera um quadro NOVO (até 700 ms), desliga, e devolve só o recorte [box]
     * (coordenadas de tela). Null se não houver captura.
     */
    fun grab(box: Rect): Frame? {
        var d: VirtualDisplay? = null
        var r: ImageReader? = null
        synchronized(lock) { d = display; r = reader }
        val dd = d ?: return null
        val rr = r ?: return null

        val latch = CountDownLatch(1)
        waiter = latch
        try {
            dd.surface = rr.surface                            // liga
            latch.await(700, TimeUnit.MILLISECONDS)            // espera 1 quadro novo
        } catch (e: Exception) {
        } finally {
            waiter = null
            try { dd.surface = null } catch (e: Exception) { }  // desliga de novo
        }

        synchronized(lock) {
            val img = latest ?: return null
            val w0 = img.width
            val h0 = img.height
            val plane = img.planes[0]
            val rs = plane.rowStride
            val rw = rs / plane.pixelStride

            val l = box.left.coerceIn(0, w0 - 1)
            val t = box.top.coerceIn(0, h0 - 1)
            val w = box.right.coerceIn(l + 1, w0) - l
            var rows = box.bottom.coerceIn(t + 1, h0) - t

            val buf = plane.buffer
            val start = t * rs
            // a última linha do buffer pode vir sem o padding: se faltar byte, descarta 1 linha
            if (buf.capacity() - start < rs * rows) rows -= 1
            if (rows <= 0) return null

            buf.position(start)
            val tmp = Bitmap.createBitmap(rw, rows, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(buf)                      // copia SÓ as linhas da caixa
            val crop = Bitmap.createBitmap(tmp, l, 0, w, rows)
            if (crop !== tmp) tmp.recycle()
            return Frame(crop, l, t)
        }
    }
}

/** Serviço em primeiro plano (obrigatório no Android 10+ para usar MediaProjection). */
class CaptureService : Service() {

    companion object {
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction("stop"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") {
            ScreenCapture.stop()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("capture", "Captura de tela", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "capture")
            .setContentTitle("OCR Auto Clicker")
            .setContentText("Lendo a tela para o OCR")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", 0)
        if (data != null) {
            try {
                ScreenCapture.start(this, code, data)
                val m = intent.getIntExtra("mode", Mode.OCR)
                val w = intent.getBooleanExtra("withOcr", false)
                AutoClickService.instance?.enable(m, w)
            } catch (e: Exception) {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ScreenCapture.stop()
        super.onDestroy()
    }
}
