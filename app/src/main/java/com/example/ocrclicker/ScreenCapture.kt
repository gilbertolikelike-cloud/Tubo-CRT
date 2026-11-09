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

/** Pedaço da tela já recortado + posição dele na tela. */
class Frame(val bmp: Bitmap, val ox: Int, val oy: Int)

/**
 * Captura de tela via MediaProjection (funciona no Android 10).
 * Guarda sempre o ÚLTIMO quadro; o OCR só recorta a caixa dele.
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
    var active = false
        private set

    private val onImage = ImageReader.OnImageAvailableListener { rd ->
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
            synchronized(lock) {
                latest?.close()
                latest = img
            }
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

        val r = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(onImage, h)
        val d = p.createVirtualDisplay(
            "ocr-capture", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
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
            val r = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener(onImage, h)
            latest?.close(); latest = null
            d.resize(m.widthPixels, m.heightPixels, m.densityDpi)
            d.surface = r.surface
            reader = r
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
        }
        try { p?.stop() } catch (e: Exception) { }
        t?.quitSafely()
    }

    /** Recorta [box] (coordenadas de tela) do último quadro. Null se ainda não chegou nenhum. */
    fun grab(box: Rect): Frame? {
        synchronized(lock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val rw = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rw, img.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            full.copyPixelsFromBuffer(plane.buffer)

            val l = box.left.coerceIn(0, img.width - 1)
            val t = box.top.coerceIn(0, img.height - 1)
            val w = box.right.coerceIn(l + 1, img.width) - l
            val h = box.bottom.coerceIn(t + 1, img.height) - t
            val crop = Bitmap.createBitmap(full, l, t, w, h)
            if (crop !== full) full.recycle()
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
                AutoClickService.instance?.enable(Mode.OCR)
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
