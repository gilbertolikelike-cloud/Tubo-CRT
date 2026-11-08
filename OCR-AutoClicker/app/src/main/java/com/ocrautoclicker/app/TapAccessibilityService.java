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

/** Sends queued taps one at a time so multiple active OCR areas do not cancel each other. */
public class TapAccessibilityService extends AccessibilityService {
    private static volatile TapAccessibilityService instance;
    private final ArrayDeque<PointF> pendingTaps = new ArrayDeque<>();
    private boolean gestureInProgress;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        synchronized (this) {
            pendingTaps.clear();
            gestureInProgress = false;
        }
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static boolean tap(float x, float y) {
        TapAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 24) return false;

        synchronized (service) {
            if (service.pendingTaps.size() >= 10) return false;
            service.pendingTaps.add(new PointF(x, y));
            if (!service.gestureInProgress) service.dispatchNextTap();
        }
        return true;
    }

    private void dispatchNextTap() {
        PointF point;
        synchronized (this) {
            if (gestureInProgress) return;
            point = pendingTaps.poll();
            if (point == null) return;
            gestureInProgress = true;
        }

        Path path = new Path();
        path.moveTo(point.x, point.y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
            .build();

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                finishGesture();
            }
            @Override public void onCancelled(GestureDescription description) {
                finishGesture();
            }
        }, new Handler(Looper.getMainLooper()));

        if (!accepted) finishGesture();
    }

    private void finishGesture() {
        synchronized (this) {
            gestureInProgress = false;
        }
        dispatchNextTap();
    }
}
