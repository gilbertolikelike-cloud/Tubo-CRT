package com.crtoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Serviço vazio: so serve pra o Android deixar o overlay cobrir tambem a barra de botoes. Nao le nada da tela. */
public class CrtAccessService extends AccessibilityService {
    public static volatile CrtAccessService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
}
