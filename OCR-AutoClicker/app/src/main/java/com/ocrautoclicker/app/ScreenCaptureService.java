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
    private static final String CHANNEL="ocr_capture";
    private static final int NOTIFICATION_ID=72;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler worker;
    private TextRecognizer recognizer;
    private RegionOverlayManager regionOverlay;
    private AutoClickOverlayManager clickOverlay;
    private MediaProjection.Callback projectionCallback;
    private int width,height,density;
    private long intervalMs=2000L;
    private volatile boolean stopped;
    private final AtomicBoolean processing=new AtomicBoolean(false);

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"OCR da tela",NotificationManager.IMPORTANCE_LOW));
        }
        thread=new HandlerThread("OCR-Region-Worker");
        thread.start();worker=new Handler(thread.getLooper());
        recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||!intent.hasExtra("captureData")){stopSelf();return START_NOT_STICKY;}
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setContentTitle("OCR ativo")
            .setContentText("Reconhecimento limitado ao quadrado selecionado.")
            .setSmallIcon(android.R.drawable.ic_menu_search).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID,notification);

        SharedPreferences prefs=getSharedPreferences("automation",MODE_PRIVATE);
        intervalMs=Math.max(800L,Math.min(60000L,prefs.getLong("ocr_interval_ms",2000L)));
        DisplayMetrics metrics=new DisplayMetrics();
        WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        width=metrics.widthPixels;height=metrics.heightPixels;density=metrics.densityDpi;

        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection=manager.getMediaProjection(intent.getIntExtra("resultCode",0),
            (Intent)intent.getParcelableExtra("captureData"));
        if(projection==null){stopSelf();return START_NOT_STICKY;}

        projectionCallback=new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}};
        projection.registerCallback(projectionCallback,worker);
        reader=ImageReader.newInstance(width,height,android.graphics.PixelFormat.RGBA_8888,2);
        display=projection.createVirtualDisplay("OCR-AutoClicker",width,height,density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);

        regionOverlay=new RegionOverlayManager(this,width,height);
        regionOverlay.show();
        clickOverlay=new AutoClickOverlayManager(this,width,height);
        clickOverlay.show();
        worker.postDelayed(scanLoop,500L);
        return START_NOT_STICKY;
    }

    private final Runnable scanLoop=()->captureAndRead();

    private void scheduleNext(){
        if(!stopped&&worker!=null)worker.postDelayed(scanLoop,intervalMs);
    }

    private void captureAndRead(){
        if(stopped||!processing.compareAndSet(false,true))return;
        Image image=null;Bitmap padded=null;Bitmap crop=null;
        try{
            image=reader.acquireLatestImage();
            if(image==null){processing.set(false);scheduleNext();return;}
            Image.Plane plane=image.getPlanes()[0];
            ByteBuffer buffer=plane.getBuffer();
            int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride();
            int rowPadding=rowStride-pixelStride*width;
            padded=Bitmap.createBitmap(width+rowPadding/pixelStride,height,Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            image.close();image=null;

            Rect area=RegionOverlayManager.getZoneRect(this,width,height);
            area.intersect(0,0,width,height);
            if(area.width()<1||area.height()<1)throw new IllegalStateException("OCR region is empty");
            crop=Bitmap.createBitmap(padded,area.left,area.top,area.width(),area.height());
            padded.recycle();padded=null;
            Bitmap captured=crop;crop=null;
            Rect offset=new Rect(area);
            recognizer.process(InputImage.fromBitmap(captured,0))
                .addOnSuccessListener(result->handleText(result,offset))
                .addOnFailureListener(error->{})
                .addOnCompleteListener(task->{
                    if(!captured.isRecycled())captured.recycle();
                    processing.set(false);scheduleNext();
                });
        }catch(Exception error){
            if(image!=null)image.close();
            if(padded!=null&&!padded.isRecycled())padded.recycle();
            if(crop!=null&&!crop.isRecycled())crop.recycle();
            processing.set(false);scheduleNext();
        }
    }

    private void handleText(Text result,Rect offset){
        String wanted=getSharedPreferences("automation",Context.MODE_PRIVATE)
            .getString("ocr_word","").trim().toLowerCase(Locale.ROOT);
        if(wanted.isEmpty())return;
        for(Text.TextBlock block:result.getTextBlocks()){
            for(Text.Line line:block.getLines()){
                if(!containsWholeWord(line.getText().toLowerCase(Locale.ROOT),wanted))continue;
                for(Text.Element element:line.getElements()){
                    String value=element.getText().toLowerCase(Locale.ROOT);
                    Rect box=element.getBoundingBox();
                    if(box!=null&&containsWholeWord(value,wanted)){
                        TapAccessibilityService.tapOcr(offset.left+box.centerX(),offset.top+box.centerY());
                        return;
                    }
                }
                Rect box=line.getBoundingBox();
                if(box!=null){
                    TapAccessibilityService.tapOcr(offset.left+box.centerX(),offset.top+box.centerY());
                    return;
                }
            }
        }
    }

    private boolean containsWholeWord(String text,String wanted){
        int from=0;
        while(true){
            int at=text.indexOf(wanted,from);if(at<0)return false;
            int end=at+wanted.length();
            boolean left=at==0||!wordChar(text.charAt(at-1));
            boolean right=end>=text.length()||!wordChar(text.charAt(end));
            if(left&&right)return true;from=at+1;
        }
    }
    private boolean wordChar(char c){return Character.isLetterOrDigit(c)||c=='_';}

    @Override public void onDestroy(){
        stopped=true;
        if(worker!=null)worker.removeCallbacksAndMessages(null);
        if(clickOverlay!=null)clickOverlay.destroy();
        if(regionOverlay!=null)regionOverlay.destroy();
        if(display!=null)display.release();
        if(reader!=null)reader.close();
        if(projection!=null){
            if(projectionCallback!=null)try{projection.unregisterCallback(projectionCallback);}catch(Exception ignored){}
            projection.stop();
        }
        if(recognizer!=null)recognizer.close();
        if(thread!=null)thread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
