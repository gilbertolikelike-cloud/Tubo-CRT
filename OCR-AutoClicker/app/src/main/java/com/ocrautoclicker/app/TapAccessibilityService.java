package com.ocrautoclicker.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayDeque;

/** Serializes gestures and always gives OCR taps priority over normal click points. */
public class TapAccessibilityService extends AccessibilityService {
    private static volatile TapAccessibilityService instance;
    private final ArrayDeque<PointF> priorityTaps = new ArrayDeque<>();
    private final ArrayDeque<PointF> normalTaps = new ArrayDeque<>();
    private boolean gestureInProgress;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        synchronized (this) {
            priorityTaps.clear();
            normalTaps.clear();
            gestureInProgress = false;
        }
        super.onDestroy();
    }

    public static boolean isReady() { return instance != null; }

    public static boolean tap(float x, float y) { return enqueue(x, y, false); }

    /**
     * Sends a normal click only when the gesture engine is completely idle.
     * Unlike tap(), this never builds a backlog: the click scheduler retries
     * later, so a slow device cannot accumulate stale taps from many targets.
     * OCR taps keep their priority and are drained before the next normal tap.
     */
    public static boolean tapWhenIdle(float x, float y) {
        TapAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 24) return false;
        synchronized (service) {
            if (service.gestureInProgress
                    || !service.priorityTaps.isEmpty()
                    || !service.normalTaps.isEmpty()) return false;
            service.normalTaps.add(new PointF(x, y));
            service.dispatchNextTap();
            return true;
        }
    }
    /** Clears queued clicker taps immediately on pause without discarding OCR taps. */
    public static void clearPendingNormalTaps() {
        TapAccessibilityService service = instance;
        if (service == null) return;
        synchronized (service) { service.normalTaps.clear(); }
    }

    public static boolean tapOcr(float x, float y) { return enqueue(x, y, true); }

    private static boolean enqueue(float x, float y, boolean priority) {
        TapAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 24) return false;
        synchronized (service) {
            ArrayDeque<PointF> queue = priority ? service.priorityTaps : service.normalTaps;
            int limit = priority ? 12 : 8;
            if (queue.size() >= limit) return false;
            queue.add(new PointF(x, y));
            if (!service.gestureInProgress) service.dispatchNextTap();
        }
        return true;
    }

    private void dispatchNextTap() {
        PointF point;
        synchronized (this) {
            if (gestureInProgress) return;
            point = priorityTaps.poll();
            if (point == null) point = normalTaps.poll();
            if (point == null) return;
            gestureInProgress = true;
        }
        Path path = new Path();
        path.moveTo(point.x, point.y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
            .build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { finishGesture(); }
            @Override public void onCancelled(GestureDescription description) { finishGesture(); }
        }, new Handler(Looper.getMainLooper()));
        if (!accepted) finishGesture();
    }

    private void finishGesture() {
        synchronized (this) { gestureInProgress = false; }
        dispatchNextTap();
    }
}
