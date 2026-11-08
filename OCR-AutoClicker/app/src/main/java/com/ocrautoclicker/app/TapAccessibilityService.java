package com.ocrautoclicker.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class TapAccessibilityService extends AccessibilityService {
    private static volatile TapAccessibilityService instance;

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
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static boolean tap(float x, float y) {
        TapAccessibilityService service = instance;
        if (service == null || Build.VERSION.SDK_INT < 24) return false;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();
        return service.dispatchGesture(gesture, null, null);
    }
}
