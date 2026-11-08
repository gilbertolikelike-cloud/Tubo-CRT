package com.ocrautoclicker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {
    private static final String CHANNEL_ID = "ocr_capture";
    private static final int NOTIFICATION_ID = 72;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private TextRecognizer recognizer;
    private String target = "";
    private long intervalMs = 2000;
    private long lastScan;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private boolean stopped;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        workerThread = new HandlerThread("OCR-Capture");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra("captureData")) {
            stopSelf();
            return START_NOT_STICKY;
        }

        target = intent.getStringExtra("target");
        intervalMs = Math.max(1000, Math.min(60000,
            intent.getIntExtra("interval", 2) * 1000L));

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.Display display = wm.getDefaultDisplay();
            display.getRealMetrics(metrics);
        } else {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(
            intent.getIntExtra("resultCode", 0),
            (Intent) intent.getParcelableExtra("captureData"));

        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
            "OCR-AutoClicker",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            worker);

        worker.post(scanLoop);
        return START_NOT_STICKY;
    }

    private final Runnable scanLoop = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            long now = System.currentTimeMillis();
            if (now - lastScan >= intervalMs && processing.compareAndSet(false, true)) {
                lastScan = now;
                captureAndRead();
            }
            if (!stopped) worker.postDelayed(this, 200);
        }
    };

    private void captureAndRead() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                processing.set(false);
                return;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap padded = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
            padded.recycle();
            image.close();
            image = null;

            InputImage input = InputImage.fromBitmap(bitmap, 0);
            recognizer.process(input)
                .addOnSuccessListener(result -> handleText(result))
                .addOnFailureListener(error -> { })
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    processing.set(false);
                });
        } catch (Exception e) {
            if (image != null) image.close();
            processing.set(false);
        }
    }

    private void handleText(Text result) {
        if (target == null || target.trim().isEmpty()) return;
        String wanted = target.trim().toLowerCase(Locale.ROOT);
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String found = line.getText().toLowerCase(Locale.ROOT);
                if (found.contains(wanted)) {
                    Rect box = line.getBoundingBox();
                    if (box != null) {
                        float x = Math.max(1, Math.min(screenWidth - 1, box.centerX()));
                        float y = Math.max(1, Math.min(screenHeight - 1, box.centerY()));
                        TapAccessibilityService.tap(x, y);
                        return;
                    }
                }
            }
        }
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OCR Auto Clicker ativo")
            .setContentText("Procurando: " + target + " • Toque em PARAR no aplicativo para encerrar.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Captura OCR", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
        if (workerThread != null) workerThread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
