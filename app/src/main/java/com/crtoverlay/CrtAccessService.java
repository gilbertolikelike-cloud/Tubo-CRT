package com.crtoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Serviço de acessibilidade. Serve pra três coisas que o Android só permite assim:
 *  - desenhar o filtro por cima da barra de botões;
 *  - acionar captura de tela e bloqueio;
 *  - "Parar todos os apps": abre a tela de cada app em Configurações e toca em "Forçar parada" e na confirmação.
 * Ele só olha a tela durante o "Parar todos os apps", e só procura esses botões.
 */
public class CrtAccessService extends AccessibilityService {
    static volatile CrtAccessService instance;

    private static final String[] FORCE_STOP = {"Forçar parada", "Forçar encerramento", "Force stop", "Forzar detención", "Forzar parada"};
    private static final String[] CONFIRM = {"Forçar parada", "Forçar encerramento", "Force stop", "Forzar detención", "Forzar parada",
            "OK", "Sim", "Yes", "Aceptar"};
    private static final String[] CANCEL = {"Cancelar", "Cancel"}; // só confirma se a caixa também tiver "Cancelar"

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean stopping;
    private int stoppedCount;

    @Override protected void onServiceConnected() {
        instance = this;
        refreshOverlay();
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        stopping = false;
        refreshOverlay();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        stopping = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    /** Se o filtro já está ligado, refaz ele agora pra usar (ou largar) o modo que cobre a barra de baixo. */
    private static void refreshOverlay() {
        OverlayService s = OverlayService.current;
        if (s != null) s.reloadOverlay();
    }

    // ---------- parar todos os apps ----------
    boolean isStopping() { return stopping; }

    void forceStopAll(List<String> packages) {
        if (stopping || packages.isEmpty()) return;
        stopping = true;
        stoppedCount = 0;
        queue.clear();
        queue.addAll(packages);
        nextApp();
    }

    private void nextApp() {
        if (!stopping) return;
        final String pkg = queue.poll();
        if (pkg == null) { finishStopping(); return; }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            handler.post(this::nextApp);
            return;
        }
        handler.postDelayed(() -> pressForceStop(0), 900);
    }

    private void pressForceStop(final int attempt) {
        if (!stopping) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo button = root == null ? null : findButton(root, FORCE_STOP);
        if (button == null) {
            if (attempt < 3) handler.postDelayed(() -> pressForceStop(attempt + 1), 500);
            else backAndNext();
            return;
        }
        if (!button.isEnabled()) { backAndNext(); return; } // o app já estava parado
        button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        handler.postDelayed(() -> pressConfirm(0), 600);
    }

    private void pressConfirm(final int attempt) {
        if (!stopping) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // só confirma numa caixa de diálogo de verdade (tem "Cancelar" junto), pra nunca tocar num "OK" qualquer
        AccessibilityNodeInfo ok = root != null && hasText(root, CANCEL) ? findButton(root, CONFIRM) : null;
        if (ok == null) {
            if (attempt < 3) handler.postDelayed(() -> pressConfirm(attempt + 1), 400);
            else backAndNext();
            return;
        }
        ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        stoppedCount++;
        handler.postDelayed(this::backAndNext, 500);
    }

    private void backAndNext() {
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(this::nextApp, 450);
    }

    private void finishStopping() {
        stopping = false;
        Toast.makeText(this, "Pronto: " + stoppedCount + " apps parados", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private static AccessibilityNodeInfo findButton(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence text = n.getText();
                if (text == null || !label.equalsIgnoreCase(text.toString().trim())) continue; // texto exato, nunca "parecido"
                AccessibilityNodeInfo target = n;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target != null) return target;
            }
        }
        return null;
    }

    private static boolean hasText(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes)
                if (n.getText() != null && label.equalsIgnoreCase(n.getText().toString().trim())) return true;
        }
        return false;
    }
}
