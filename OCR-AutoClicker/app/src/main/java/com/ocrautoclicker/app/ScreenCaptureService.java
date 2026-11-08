package com.ocrautoclicker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.os.SystemClock;
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
    private static final String CHANNEL_ID="ocr_capture";
    private static final int NOTIFICATION_ID=72;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private TextRecognizer recognizer;
    private RegionOverlayManager regionOverlay;
    private AutoClickOverlayManager clickOverlay;
    private long intervalMs=2000L;
    private final AtomicBoolean processing=new AtomicBoolean(false);
    private int screenWidth,screenHeight,screenDensity;
    private volatile boolean stopped;
    private MediaProjection.Callback projectionCallback;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        boolean ocrEnabled=intent.hasExtra("captureData");
        SharedPreferences prefs=getSharedPreferences("automation",MODE_PRIVATE);

        // The click-only path does not create a screen capture, OCR worker or ML Kit recognizer.
        if(Build.VERSION.SDK_INT>=34) {
            int type = ocrEnabled
                ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                : android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            startForeground(NOTIFICATION_ID,buildNotification(ocrEnabled),type);
        } else {
            startForeground(NOTIFICATION_ID,buildNotification(ocrEnabled));
        }

        DisplayMetrics metrics=new DisplayMetrics();
        WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth=metrics.widthPixels;screenHeight=metrics.heightPixels;screenDensity=metrics.densityDpi;

        clickOverlay=new AutoClickOverlayManager(this,screenWidth,screenHeight);
        clickOverlay.show();

        if(!ocrEnabled) {
            // No screen capture or text recognition in ordinary Auto Click mode.
            return START_NOT_STICKY;
        }

        intervalMs=Math.max(500L,Math.min(86400000L,prefs.getLong("ocr_interval_ms",2000L)));
        workerThread=new HandlerThread("OCR-Worker");
        workerThread.start();
        worker=new Handler(workerThread.getLooper());
        recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=manager.getMediaProjection(intent.getIntExtra("resultCode",0),
            (Intent)intent.getParcelableExtra("captureData"));
        if(projection==null){stopSelf();return START_NOT_STICKY;}

        imageReader=ImageReader.newInstance(screenWidth,screenHeight,android.graphics.PixelFormat.RGBA_8888,2);
        virtualDisplay=projection.createVirtualDisplay("OCR-AutoClicker",screenWidth,screenHeight,
            screenDensity,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,imageReader.getSurface(),null,worker);

        projectionCallback=new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}};
        projection.registerCallback(projectionCallback,worker);

        regionOverlay=new RegionOverlayManager(this,screenWidth,screenHeight);
        regionOverlay.show();
        worker.post(scanLoop);
        return START_NOT_STICKY;
    }

    // One scan is scheduled after the previous OCR task completes: no 200 ms polling loop.
    private final Runnable scanLoop=new Runnable(){
        @Override public void run(){if(!stopped)captureAndRead();}
    };

    private void scheduleNextScan(){
        if(!stopped&&worker!=null)worker.postDelayed(scanLoop,intervalMs);
    }

    private void captureAndRead(){
        if(stopped||!processing.compareAndSet(false,true))return;
        Image image=null;
        Bitmap bitmap=null;
        try{
            image=imageReader.acquireLatestImage();
            if(image==null){processing.set(false);scheduleNextScan();return;}
            Image.Plane plane=image.getPlanes()[0];
            ByteBuffer buffer=plane.getBuffer();
            int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride();
            int rowPadding=rowStride-pixelStride*screenWidth;
            Bitmap padded=Bitmap.createBitmap(screenWidth+rowPadding/pixelStride,
                screenHeight,Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            bitmap=Bitmap.createBitmap(padded,0,0,screenWidth,screenHeight);
            padded.recycle();
            image.close();image=null;
            Bitmap captured=bitmap;
            recognizer.process(InputImage.fromBitmap(captured,0))
                .addOnSuccessListener(this::handleText)
                .addOnFailureListener(error->{})
                .addOnCompleteListener(task->{
                    try{if(!captured.isRecycled())captured.recycle();}catch(Exception ignored){}
                    processing.set(false);
                    scheduleNextScan();
                });
        }catch(Exception e){
            if(image!=null)image.close();
            if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
            processing.set(false);
            scheduleNextScan();
        }
    }

    private void handleText(Text result){
        SharedPreferences prefs=getSharedPreferences("automation",Context.MODE_PRIVATE);
        String wanted=prefs.getString("ocr_word","YES");
        if(wanted==null||wanted.trim().isEmpty())return;
        wanted=wanted.trim().toLowerCase(Locale.ROOT);
        Rect area=RegionOverlayManager.getZoneRect(this,0,screenWidth,screenHeight);
        for(Text.TextBlock block:result.getTextBlocks()){
            for(Text.Line line:block.getLines()){
                String lineText=line.getText().toLowerCase(Locale.ROOT);
                if(!containsWholeWord(lineText,wanted))continue;
                for(Text.Element element:line.getElements()){
                    String value=element.getText().toLowerCase(Locale.ROOT);
                    Rect box=element.getBoundingBox();
                    if(box!=null&&containsWholeWord(value,wanted)
                        &&area.contains(box.centerX(),box.centerY())){
                        TapAccessibilityService.tapOcr(box.centerX(),box.centerY());
                        return;
                    }
                }
                Rect lineBox=line.getBoundingBox();
                if(lineBox!=null&&area.contains(lineBox.centerX(),lineBox.centerY())){
                    TapAccessibilityService.tapOcr(lineBox.centerX(),lineBox.centerY());
                    return;
                }
            }
        }
    }

    private boolean containsWholeWord(String text,String wanted){
        int from=0;
        while(true){
            int at=text.indexOf(wanted,from);
            if(at<0)return false;
            int end=at+wanted.length();
            boolean left=at==0||!wordChar(text.charAt(at-1));
            boolean right=end>=text.length()||!wordChar(text.charAt(end));
            if(left&&right)return true;
            from=at+1;
        }
    }
    private boolean wordChar(char c){return Character.isLetterOrDigit(c)||c=='_';}

    private Notification buildNotification(boolean ocrEnabled){
        return new Notification.Builder(this,CHANNEL_ID)
            .setContentTitle("Auto Click ativo")
            .setContentText(ocrEnabled
                ? "Auto Click e quadrado OCR ativos."
                : "Auto Click ativo. OCR desligado para economizar recursos.")
            .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true).build();
    }
    private void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL_ID,"Captura OCR",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy(){
        stopped=true;
        if(worker!=null)worker.removeCallbacksAndMessages(null);
        if(clickOverlay!=null)clickOverlay.destroy();
        if(regionOverlay!=null)regionOverlay.destroy();
        if(virtualDisplay!=null)virtualDisplay.release();
        if(imageReader!=null)imageReader.close();
        if(projection!=null){
            if(projectionCallback!=null)try{projection.unregisterCallback(projectionCallback);}catch(Exception ignored){}
            projection.stop();
        }
        if(recognizer!=null)recognizer.close();
        if(workerThread!=null)workerThread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
