package com.ocrautoclicker.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Movable and resizable OCR-only region. */
public class RegionOverlayManager {
    private final Context context;
    private final WindowManager wm;
    private final int screenW, screenH;
    private final Rect rect;
    private View frame, moveHandle, resizeHandle;
    private WindowManager.LayoutParams frameParams, moveParams, resizeParams;

    public RegionOverlayManager(Context c, int w, int h) {
        context=c; wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);\n        screenW=w; screenH=h; rect=getZoneRect(c, w, h);
    }

    public static Rect getZoneRect(Context c, int w, int h) {
        SharedPreferences p=c.getSharedPreferences("ocr_region", Context.MODE_PRIVATE);
        int rw=Math.max(80, Math.min(p.getInt("w", Math.max(180,w/3)), w));
        int rh=Math.max(60, Math.min(p.getInt("h", Math.max(90,h/9)), h));
        int x=Math.max(0, Math.min(p.getInt("x",w/3),w-rw));
        int y=Math.max(0, Math.min(p.getInt("y",h/4),h-rh));
        return new Rect(x,y,x+rw,y+rh);
    }

    public void show() {
        if(frame!=null)return;
        frame=new Frame(context);
        frameParams=params(rect.left,rect.top,rect.width(),rect.height(),true);
        wm.addView(frame,frameParams);
        moveHandle=new Handle(context,true);
        moveParams=params(rect.left-16,rect.top-16,40,40,false);
        wm.addView(moveHandle,moveParams);
        attach(moveHandle,false);
        resizeHandle=new Handle(context,false);
        resizeParams=params(rect.right-24,rect.bottom-24,40,40,false);
        wm.addView(resizeHandle,resizeParams);
        attach(resizeHandle,true);
    }

    private WindowManager.LayoutParams params(int x,int y,int w,int h,boolean notTouchable) {
        int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            |WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if(notTouchable)flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=x;p.y=y;return p;
    }

    private void attach(View handle,boolean resize) {
        handle.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY;int sx,sy,sw,sh;
            @Override public boolean onTouch(View v,MotionEvent e) {
                if(e.getAction()==MotionEvent.ACTION_DOWN){
                    downX=e.getRawX();downY=e.getRawY();
                    sx=rect.left;sy=rect.top;sw=rect.width();sh=rect.height();return true;
                }
                if(e.getAction()==MotionEvent.ACTION_MOVE){
                    int dx=(int)(e.getRawX()-downX),dy=(int)(e.getRawY()-downY);
                    if(resize){
                        rect.right=rect.left+Math.max(80,Math.min(screenW-rect.left,sw+dx));
                        rect.bottom=rect.top+Math.max(60,Math.min(screenH-rect.top,sh+dy));
                    }else{
                        int x=Math.max(0,Math.min(screenW-sw,sx+dx));
                        int y=Math.max(0,Math.min(screenH-sh,sy+dy));
                        rect.set(x,y,x+sw,y+sh);
                    }
                    update();return true;
                }
                if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){
                    context.getSharedPreferences("ocr_region",Context.MODE_PRIVATE).edit()
                        .putInt("x",rect.left).putInt("y",rect.top)
                        .putInt("w",rect.width()).putInt("h",rect.height()).apply();
                    return true;
                }
                return true;
            }
        });
    }

    private void update() {
        frameParams.x=rect.left;frameParams.y=rect.top;
        frameParams.width=rect.width();frameParams.height=rect.height();
        wm.updateViewLayout(frame,frameParams);
        moveParams.x=rect.left-16;moveParams.y=rect.top-16;
        wm.updateViewLayout(moveHandle,moveParams);
        resizeParams.x=rect.right-24;resizeParams.y=rect.bottom-24;
        wm.updateViewLayout(resizeHandle,resizeParams);
    }

    public void destroy() {
        remove(frame);remove(moveHandle);remove(resizeHandle);
        frame=moveHandle=resizeHandle=null;
    }
    private void remove(View v){if(v!=null)try{wm.removeView(v);}catch(Exception ignored){}}

    private static class Frame extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Frame(Context c){super(c);}
        @Override protected void onDraw(Canvas c){
            p.setColor(0xFF00E5FF);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);
            c.drawRect(3,3,getWidth()-3,getHeight()-3,p);
        }
    }
    private static class Handle extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final boolean move;
        Handle(Context c,boolean m){super(c);move=m;}
        @Override protected void onDraw(Canvas c){
            float x=getWidth()/2f,y=getHeight()/2f;
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawCircle(x,y,17,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(Color.BLACK);c.drawCircle(x,y,17,p);
            p.setStyle(Paint.Style.FILL);
            if(move){c.drawLine(x-7,y,x+7,y,p);c.drawLine(x,y-7,x,y+7,p);}
            else{c.drawLine(x-6,y+6,x+6,y-6,p);c.drawLine(x-4,y+8,x+8,y-4,p);}
        }
    }
}
