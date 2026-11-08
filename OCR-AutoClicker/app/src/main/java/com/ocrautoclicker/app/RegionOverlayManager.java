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

/** One movable/resizable OCR search rectangle. */
public class RegionOverlayManager {
    private final Context context;
    private final WindowManager wm;
    private final int sw, sh;
    private View frame, moveHandle, resizeHandle;
    private WindowManager.LayoutParams frameParams, moveParams, resizeParams;
    private final Rect rect;

    public RegionOverlayManager(Context context, int width, int height) {
        this.context = context;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        sw = width; sh = height;
        rect = getZoneRect(context, 0, width, height);
    }

    public void show() {
        if (frame != null) return;
        frame = new FrameView(context);
        frameParams = params(rect.left, rect.top, rect.width(), rect.height(), true);
        wm.addView(frame, frameParams);
        moveHandle = new HandleView(context, true);
        moveParams = params(rect.left - 17, rect.top - 17, 42, 42, false);
        wm.addView(moveHandle, moveParams);
        attachTouch(moveHandle, false);
        resizeHandle = new HandleView(context, false);
        resizeParams = params(rect.right - 25, rect.bottom - 25, 42, 42, false);
        wm.addView(resizeHandle, resizeParams);
        attachTouch(resizeHandle, true);
    }

    public static Rect getZoneRect(Context context, int ignored, int width, int height) {
        SharedPreferences p = context.getSharedPreferences("ocr_region", Context.MODE_PRIVATE);
        int w = Math.max(100, Math.min(p.getInt("w", Math.max(180, width / 3)), width));
        int h = Math.max(80, Math.min(p.getInt("h", Math.max(90, height / 9)), height));
        int x = Math.max(0, Math.min(p.getInt("x", width / 3), width - w));
        int y = Math.max(0, Math.min(p.getInt("y", height / 4), height - h));
        return new Rect(x, y, x + w, y + h);
    }

    private WindowManager.LayoutParams params(int x, int y, int w, int h, boolean notTouchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (notTouchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT; p.x = x; p.y = y;
        return p;
    }

    private void attachTouch(View handle, boolean resize) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int sx, sy, swidth, sheight;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX=e.getRawX(); downY=e.getRawY();
                    sx=rect.left; sy=rect.top; swidth=rect.width(); sheight=rect.height();
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx=(int)(e.getRawX()-downX), dy=(int)(e.getRawY()-downY);
                    if (resize) {
                        int w=Math.max(100, Math.min(sw-rect.left, swidth+dx));
                        int h=Math.max(80, Math.min(sh-rect.top, sheight+dy));
                        rect.right=rect.left+w; rect.bottom=rect.top+h;
                    } else {
                        int x=Math.max(0, Math.min(sw-swidth, sx+dx));
                        int y=Math.max(0, Math.min(sh-sheight, sy+dy));
                        rect.set(x,y,x+swidth,y+sheight);
                    }
                    update(); return true;
                }
                if (e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL) {
                    context.getSharedPreferences("ocr_region", Context.MODE_PRIVATE).edit()
                        .putInt("x",rect.left).putInt("y",rect.top)
                        .putInt("w",rect.width()).putInt("h",rect.height()).apply();
                    return true;
                }
                return true;
            }
        });
    }

    private void update() {
        frameParams.x=rect.left; frameParams.y=rect.top;
        frameParams.width=rect.width(); frameParams.height=rect.height();
        wm.updateViewLayout(frame, frameParams);
        moveParams.x=rect.left-17; moveParams.y=rect.top-17;
        wm.updateViewLayout(moveHandle, moveParams);
        resizeParams.x=rect.right-25; resizeParams.y=rect.bottom-25;
        wm.updateViewLayout(resizeHandle, resizeParams);
    }

    public void destroy() {
        remove(frame); remove(moveHandle); remove(resizeHandle);
        frame=moveHandle=resizeHandle=null;
    }
    private void remove(View v) { if (v!=null) try { wm.removeView(v); } catch(Exception ignored) { } }

    private static class FrameView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        FrameView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            paint.setColor(Color.CYAN); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5);
            c.drawRect(3,3,getWidth()-3,getHeight()-3,paint);
        }
    }
    private static class HandleView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private final boolean move;
        HandleView(Context c, boolean m) { super(c); move=m; }
        @Override protected void onDraw(Canvas c) {
            float x=getWidth()/2f,y=getHeight()/2f;
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL); c.drawCircle(x,y,18,paint);
            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3);
            c.drawCircle(x,y,18,paint); paint.setStyle(Paint.Style.FILL);
            if(move){c.drawLine(x-8,y,x+8,y,paint);c.drawLine(x,y-8,x,y+8,paint);}
            else {c.drawLine(x-6,y+6,x+6,y-6,paint);c.drawLine(x-4,y+8,x+8,y-4,paint);}
        }
    }
}
