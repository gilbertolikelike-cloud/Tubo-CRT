package com.ocrautoclicker.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.media.projection.MediaProjectionManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.text.InputType;

public class MainActivity extends Activity {
    private static final int REQUEST_OCR_CAPTURE = 410;
    private EditText interval, ocrWord, ocrInterval;
    private Spinner unit, mode;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("automation", MODE_PRIVATE);
        int pad = dp(22);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(17, 22, 29));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AUTO CLICKER");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Toques automáticos • painel flutuante");
        subtitle.setTextColor(0xFF9EABB9);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(5), 0, dp(24));
        root.addView(subtitle, matchWrap());

        addLabel(root, "INTERVALO ENTRE TOQUES");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        interval = new EditText(this);
        interval.setSingleLine(true);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setTextColor(Color.WHITE);
        interval.setHintTextColor(0xFF8995A3);
        interval.setHint("Ex.: 500");
        long savedMs = Math.max(250L, prefs.getLong("click_default_interval_ms", 1000L));
        int savedUnit = Math.max(0, Math.min(2, prefs.getInt("click_interval_unit", 0)));
        long multiplier = savedUnit == 0 ? 1L : savedUnit == 1 ? 1000L : 60000L;
        interval.setText(String.valueOf(Math.max(1L, savedMs / multiplier)));
        row.addView(interval, new LinearLayout.LayoutParams(0, dp(52), 1));

        unit = new Spinner(this);
        ArrayAdapter<String> units = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Milissegundos", "Segundos", "Minutos"});
        unit.setAdapter(units);
        unit.setSelection(savedUnit);
        row.addView(unit, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(row);

        addLabel(root, "MODO DE CLIQUE");
        mode = new Spinner(this);
        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Vários alvos (intervalo individual)", "Sequencial (um alvo por vez)", "Solo (um único alvo)"});
        mode.setAdapter(modes);
        String savedMode = prefs.getString("click_mode", "custom");
        mode.setSelection("solo".equals(savedMode) ? 2 : "sequence".equals(savedMode) ? 1 : 0);
        root.addView(mode, matchWrap());

        TextView help = new TextView(this);
        help.setText("Depois de iniciar, use + para adicionar bolinhas e arraste cada uma até o local desejado. Toque longo em uma bolinha para mudar seu intervalo. O painel flutuante controla iniciar, pausar e parar.");
        help.setTextColor(0xFFB6C0CB);
        help.setTextSize(14);
        help.setPadding(0, dp(14), 0, dp(18));
        root.addView(help);

        Button save = button("SALVAR CONFIGURAÇÕES");
        root.addView(save, matchWrap());
        save.setOnClickListener(v -> saveSettings());

        Button accessibility = button("1. ATIVAR ACESSIBILIDADE");
        root.addView(accessibility, matchWrap());
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button overlay = button("2. PERMITIR SOBREPOSIÇÃO");
        root.addView(overlay, matchWrap());
        overlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else setStatus("Permissão de sobreposição liberada.");
        });

        Button start = button("3. INICIAR AUTO CLICKER");
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF176B45));
        root.addView(start, matchWrap());
        start.setOnClickListener(v -> {
            if (!saveSettings()) return;
            if (!TapAccessibilityService.isReady()) {
                setStatus("Ative primeiro a acessibilidade do Auto Clicker.");
                return;
            }
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                setStatus("Libere a permissão de sobreposição primeiro.");
                return;
            }
            stopService(new Intent(this, ScreenCaptureService.class));\n            Intent service = new Intent(this, AutoClickService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            setStatus("Auto Clicker iniciado. O painel aparecerá sobre outros aplicativos.");
        });

        addLabel(root, "OCR OPCIONAL — REGIÃO DA TELA");
        ocrWord = new EditText(this);
        ocrWord.setSingleLine(true);
        ocrWord.setInputType(InputType.TYPE_CLASS_TEXT);
        ocrWord.setTextColor(Color.WHITE);
        ocrWord.setHintTextColor(0xFF8995A3);
        ocrWord.setHint("Palavra para procurar, ex.: COMPRAR");
        ocrWord.setText(prefs.getString("ocr_word", "COMPRAR"));
        root.addView(ocrWord, matchWrap());

        ocrInterval = new EditText(this);
        ocrInterval.setSingleLine(true);
        ocrInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        ocrInterval.setTextColor(Color.WHITE);
        ocrInterval.setHintTextColor(0xFF8995A3);
        ocrInterval.setHint("Intervalo OCR em milissegundos (mínimo 800)");
        ocrInterval.setText(String.valueOf(prefs.getLong("ocr_interval_ms", 2000L)));
        root.addView(ocrInterval, matchWrap());

        Button startOcr = button("ATIVAR OCR + QUADRADO");
        startOcr.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF087FBF));
        root.addView(startOcr, matchWrap());
        startOcr.setOnClickListener(v -> startOcrCapture());

        Button stop = button("PARAR AUTO CLICKER / OCR");
        root.addView(stop, matchWrap());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, AutoClickService.class));
            stopService(new Intent(this, ScreenCaptureService.class));
            setStatus("Auto Clicker e OCR parados.");
        });

        status = new TextView(this);
        status.setText("Status: aguardando");
        status.setTextColor(0xFF65E6A5);
        status.setTextSize(14);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status, matchWrap());

        setContentView(scroll);
    }

    private void startOcrCapture() {
        if (!saveSettings()) return;
        String target = ocrWord.getText().toString().trim();
        if (target.isEmpty()) { setStatus("Digite a palavra que o OCR deve procurar."); return; }
        long intervalMs;
        try { intervalMs = Long.parseLong(ocrInterval.getText().toString().trim()); }
        catch (Exception e) { setStatus("Informe o intervalo do OCR em milissegundos."); return; }
        intervalMs = Math.max(800L, Math.min(60000L, intervalMs));
        getSharedPreferences("automation", MODE_PRIVATE).edit()
            .putString("ocr_word", target).putLong("ocr_interval_ms", intervalMs).apply();
        if (!TapAccessibilityService.isReady()) { setStatus("Ative primeiro a acessibilidade."); return; }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            setStatus("Libere a sobreposição antes de ativar o quadrado OCR."); return;
        }
        stopService(new Intent(this, ScreenCaptureService.class));\n        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_OCR_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OCR_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Captura de tela não autorizada; OCR não foi iniciado."); return;
        }
        stopService(new Intent(this, AutoClickService.class));
        Intent service = new Intent(this, ScreenCaptureService.class);
        service.putExtra("resultCode", resultCode);
        service.putExtra("captureData", data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        setStatus("OCR ativo. Arraste o quadrado para selecionar a região.");
    }

    private boolean saveSettings() {
        try {
            long value = Long.parseLong(interval.getText().toString().trim());
            if (value < 1) throw new IllegalArgumentException("O intervalo precisa ser maior que zero.");
            int selectedUnit = unit.getSelectedItemPosition();
            long multiplier = selectedUnit == 0 ? 1L : selectedUnit == 1 ? 1000L : 60000L;
            if (value > Long.MAX_VALUE / multiplier) throw new IllegalArgumentException("Intervalo muito grande.");
            long millis = Math.max(250L, Math.min(86400000L, value * multiplier));
            int selectedMode = mode.getSelectedItemPosition();
            String modeValue = selectedMode == 2 ? "solo" : selectedMode == 1 ? "sequence" : "custom";
            getSharedPreferences("automation", MODE_PRIVATE).edit()
                .putLong("click_default_interval_ms", millis)
                .putLong("global_interval_ms", millis)
                .putLong("click_interval_value", value)
                .putInt("click_interval_unit", selectedUnit)
                .putInt("global_unit", selectedUnit)
                .putString("click_mode", modeValue)
                .apply();
            setStatus("Configurações salvas.");
            return true;
        } catch (Exception e) {
            setStatus(e.getMessage() == null ? "Confira o intervalo informado." : e.getMessage());
            return false;
        }
    }

    private void addLabel(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xFFE5EBF2);
        label.setTextSize(13);
        label.setTypeface(null, 1);
        label.setPadding(0, dp(18), 0, dp(5));
        root.addView(label, matchWrap());
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        if (status != null) status.setText("Status: " + text);
        else Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
