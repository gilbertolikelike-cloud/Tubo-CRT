package com.crtoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Serviço de acessibilidade vazio: ele não lê nada da tela. Serve só pra duas coisas que o Android
 * só permite assim: desenhar o filtro por cima da barra de botões e acionar captura de tela / bloqueio.
 */
public class CrtAccessService extends AccessibilityService {
    static volatile CrtAccessService instance;

    @Override protected void onServiceConnected() {
        instance = this;
        refreshOverlay();
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        refreshOverlay();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    /** Se o filtro já está ligado, refaz ele agora pra usar (ou largar) o modo que cobre a barra de baixo. */
    private static void refreshOverlay() {
        OverlayService s = OverlayService.current;
        if (s != null) s.reloadOverlay();
    }
}
