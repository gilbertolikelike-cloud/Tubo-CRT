package com.ocrautoclicker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Displays transparent, movable OCR regions without blocking touches inside them. */
public class RegionOverlayManager {
    private final Context context;
    private final WindowManager windowManager;
    private final int screenWidth;
    private final int screenHeight;
    private final List<ZoneViews> zones = new ArrayList<>();

    public RegionOverlayManager(Context context, int screenWidth, int screenHeight) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public void show() {
        SharedPreferences prefs = context.getSharedPreferences("ocr_zones", Context.MODE_PRIVATE);
        for (int i = 0; i < 10; i++) {
            String word = prefs.getString("word_" + i, i == 0 ? "YES" : "");
            boolean enabled = prefs.getBoolean("enabled_" + i, i == 0);
            if (!enabled || word.trim().isEmpty()) continue;

            Rect rect = getZoneRect(context, i, screenWidth, screenHeight);
            ZoneViews zone = new ZoneViews(i, rect);
            addZone(zone);
            zones.add(zone);
        }
    }

    public static Rect getZoneRect(Context context, int index, int screenWidth, int screenHeight) {
        SharedPreferences prefs = context.getSharedPreferences("ocr_zones", Context.MODE_PRIVATE);
        int col = index % 2;
        int row = index / 2;
        int defaultX = (int) (screenWidth * (col == 0 ? 0.05f : 0.52f));
        int defaultY = (int) (screenHeight * (0.08f + row * 0.18f));
        int defaultW = (int) (screenWidth * 0.42f);
        int defaultH = Math.max(90, (int) (screenHeight * 0.13f));

        int x = prefs.getInt("x_" + index, defaultX);
        int y = prefs.getInt("y_" + index, defaultY);
        int w = prefs.getInt("w_" + index, defaultW);
        int h = prefs.getInt("h_" + index, defaultH);
        w = Math.max(100, Math.min(w, screenWidth));
        h = Math.max(80, Math.min(h, screenHeight));
        x = Math.max(0, Math.min(x, screenWidth - w));
        y = Math.max(0, Math.min(y, screenHeight - h));
        return new Rect(x, y, x + w, y + h);
    }

    private void addZone(ZoneViews zone) {
        zone.frame = new FrameView(context);
        zone.frame.setColor(zone.index);
        zone.frameParams = params(zone.rect.left, zone.rect.top,
            zone.rect.width(), zone.rect.height(), true);
        windowManager.addView(zone.frame, zone.frameParams);

        zone.moveHandle = new HandleView(context, true);
        zone.moveParams = params(zone.rect.left - 17, zone.rect.top - 17, 42, 42, false);
        windowManager.addView(zone.moveHandle, zone.moveParams);
        attachTouch(zone, zone.moveHandle, false);

        zone.resizeHandle = new HandleView(context, false);
        zone.resizeParams = params(zone.rect.right - 25, zone.rect.bottom - 25, 42, 42, false);
        windowManager.addView(zone.resizeHandle, zone.resizeParams);
        attachTouch(zone, zone.resizeHandle, true);
    }

    private WindowManager.LayoutParams params(int x, int y, int w, int h, boolean notTouchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (notTouchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = x;
        p.y = y;
        return p;
    }

    private void attachTouch(ZoneViews zone, View handle, boolean resize) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY, startW, startH;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = zone.rect.left;
                    startY = zone.rect.top;
                    startW = zone.rect.width();
                    startH = zone.rect.height();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (resize) {
                        int w = Math.max(100, Math.min(screenWidth - zone.rect.left, startW + dx));
                        int h = Math.max(80, Math.min(screenHeight - zone.rect.top, startH + dy));
                        zone.rect.right = zone.rect.left + w;
                        zone.rect.bottom = zone.rect.top + h;
                    } else {
                        int x = Math.max(0, Math.min(screenWidth - zone.rect.width(), startX + dx));
                        int y = Math.max(0, Math.min(screenHeight - zone.rect.height(), startY + dy));
                        zone.rect.set(x, y, x + startW, y + startH);
                    }
                    updateZone(zone);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    save(zone);
                    return true;
                }
                return true;
            }
        });
    }

    private void updateZone(ZoneViews zone) {
        zone.frameParams.x = zone.rect.left;
        zone.frameParams.y = zone.rect.top;
        zone.frameParams.width = zone.rect.width();
        zone.frameParams.height = zone.rect.height();
        windowManager.updateViewLayout(zone.frame, zone.frameParams);

        zone.moveParams.x = zone.rect.left - 17;
        zone.moveParams.y = zone.rect.top - 17;
        windowManager.updateViewLayout(zone.moveHandle, zone.moveParams);

        zone.resizeParams.x = zone.rect.right - 25;
        zone.resizeParams.y = zone.rect.bottom - 25;
        windowManager.updateViewLayout(zone.resizeHandle, zone.resizeParams);
    }

    private void save(ZoneViews zone) {
        context.getSharedPreferences("ocr_zones", Context.MODE_PRIVATE).edit()
            .putInt("x_" + zone.index, zone.rect.left)
            .putInt("y_" + zone.index, zone.rect.top)
            .putInt("w_" + zone.index, zone.rect.width())
            .putInt("h_" + zone.index, zone.rect.height())
            .apply();
    }

    public void destroy() {
        for (ZoneViews zone : zones) {
            remove(zone.frame);
            remove(zone.moveHandle);
            remove(zone.resizeHandle);
        }
        zones.clear();
    }

    private void remove(View view) {
        if (view != null) {
            try { windowManager.removeView(view); } catch (Exception ignored) { }
        }
    }

    private static class ZoneViews {
        final int index;
        final Rect rect;
        FrameView frame;
        HandleView moveHandle, resizeHandle;
        WindowManager.LayoutParams frameParams, moveParams, resizeParams;
        ZoneViews(int index, Rect rect) { this.index = index; this.rect = new Rect(rect); }
    }

    private static class FrameView extends View {
        private final Paint paint = new Paint(3);
        private int color = Color.CYAN;
        FrameView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        void setColor(int index) {
            int[] colors = {Color.CYAN, Color.GREEN, Color.YELLOW, Color.MAGENTA, Color.RED,
                Color.BLUE, Color.WHITE, Color.rgb(255, 128, 0), Color.rgb(0, 220, 180), Color.rgb(180, 120, 255)};
            color = colors[index % colors.length];
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5);
            canvas.drawRect(3, 3, getWidth() - 3, getHeight() - 3, paint);
        }
    }

    private static class HandleView extends View {
        private final Paint paint = new Paint(3);
        private final boolean move;
        HandleView(Context context, boolean move) { super(context); this.move = move; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, 18, paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, 18, paint);
            paint.setStyle(Paint.Style.FILL);
            if (move) {
                canvas.drawLine(13, 21, 29, 21, paint);
                canvas.drawLine(21, 13, 21, 29, paint);
            } else {
                canvas.drawLine(15, 27, 27, 15, paint);
                canvas.drawLine(17, 27, 27, 17, paint);
            }
        }
    }
}
