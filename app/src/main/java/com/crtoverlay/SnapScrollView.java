package com.crtoverlay;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** Rolagem que sempre para com uma linha certinho em cima de cada prateleira. */
public class SnapScrollView extends ScrollView {
    private static final int VISIBLE_ROWS = 4;
    private int pitch = 1;
    private boolean flung;

    public SnapScrollView(Context c) {
        super(c);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    /** Distância, em pixels, de uma prateleira até a próxima. */
    public void setPitch(int pitch) { this.pitch = Math.max(1, pitch); }

    private int snap(int y) {
        int rows = getChildCount() > 0 ? ((ViewGroup) getChildAt(0)).getChildCount() : 0;
        int last = Math.max(0, rows - VISIBLE_ROWS);
        return Math.max(0, Math.min(last, Math.round(y / (float) pitch))) * pitch;
    }

    @Override public void fling(int velocityY) {
        flung = true;
        smoothScrollTo(0, snap(getScrollY() + velocityY / 5)); // joga umas linhas e encaixa
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) flung = false;
        boolean handled = super.onTouchEvent(ev);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flung)
            smoothScrollTo(0, snap(getScrollY()));
        return handled;
    }
}
