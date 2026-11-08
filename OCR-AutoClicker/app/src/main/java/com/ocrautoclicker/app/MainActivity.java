package com.ocrautoclicker.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import android.text.InputType;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 410;
    private EditText word, ocrInterval, clickInterval;
    private Spinner ocrUnit, clickUnit, clickMode;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("automation", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(26, 26, 26, 24);
        layout.setBackgroundColor(Color.rgb(20, 24, 30));
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("OCR AUTO CLICKER");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView version = new TextView(this);
        version.setText("BETA 0.8 • 1 OCR + ATÉ 100 CLIQUES");
        version.setTextColor(Color.GREEN);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 8, 0, 18);
        layout.addView(version);

        addLabel(layout, "PALAVRA QUE O OCR DEVE PROCURAR");
        word = new EditText(this);
        word.setSingleLine(true);
        word.setTextColor(Color.WHITE);
        word.setHintTextColor(Color.GRAY);
        word.setHint("Ex.: COMPRAR");
        word.setText(prefs.getString("ocr_word", "YES"));
        layout.addView(word);

        addLabel(layout, "VELOCIDADE DO OCR (SOMENTE LEITURA DA PALAVRA)");
        LinearLayout ocrRow = new LinearLayout(this);
        ocrRow.setOrientation(LinearLayout.HORIZONTAL);
        ocrInterval = numberField(prefs.getLong("ocr_interval_value",
            prefs.getLong("global_interval_value", 2)));
        ocrRow.addView(ocrInterval, weighted());
        ocrUnit = unitSpinner(prefs.getInt("ocr_interval_unit",
            prefs.getInt("global_unit", 1)));
        ocrRow.addView(ocrUnit, weighted());
        layout.addView(ocrRow);

        addLabel(layout, "VELOCIDADE PADRÃO DAS BOLINHAS (INDEPENDENTE DO OCR)");
        LinearLayout clickRow = new LinearLayout(this);
        clickRow.setOrientation(LinearLayout.HORIZONTAL);
        clickInterval = numberField(prefs.getLong("click_interval_value",
            prefs.getLong("global_interval_value", 2)));
        clickRow.addView(clickInterval, weighted());
        clickUnit = unitSpinner(prefs.getInt("click_interval_unit",
            prefs.getInt("global_unit", 1)));
        clickRow.addView(clickUnit, weighted());
        layout.addView(clickRow);

        addLabel(layout, "MODO DAS BOLINHAS");
        clickMode = new Spinner(this);
        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Solo (1 bolinha)", "Personalizado (várias bolinhas)"});
        clickMode.setAdapter(modes);
        clickMode.setSelection("solo".equals(prefs.getString("click_mode", "custom")) ? 0 : 1);
        layout.addView(clickMode);

        TextView info = new TextView(this);
        info.setText("O quadrado OCR continua independente nos dois modos. Solo mantém exatamente uma bolinha; Personalizado permite adicionar e remover várias pelo painel. Pressione uma bolinha para ajustar seu intervalo individual. O botão de cronômetro no painel define por quantos minutos as bolinhas vão funcionar (0 = sem limite).");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(14);
        info.setPadding(0, 10, 0, 12);
        layout.addView(info);

        Button save = new Button(this);
        save.setText("SALVAR CONFIGURAÇÕES");
        layout.addView(save);
        save.setOnClickListener(v -> saveSettings());

        Button accessibility = new Button(this);
        accessibility.setText("1. ATIVAR ACESSIBILIDADE");
        layout.addView(accessibility);
        accessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button overlay = new Button(this);
        overlay.setText("2. PERMITIR SOBREPOR OUTROS APPS");
        layout.addView(overlay);
        overlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else status.setText("Permissão de sobreposição liberada.");
        });

        Button start = new Button(this);
        start.setText("3. INICIAR OCR + PAINEL DE CLIQUES");
        layout.addView(start);
        start.setOnClickListener(v -> {
            if (saveSettings()) beginCapture();
        });

        Button stop = new Button(this);
        stop.setText("PARAR TUDO");
        layout.addView(stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status.setText("Automação parada.");
        });

        status = new TextView(this);
        status.setText("Status: aguardando configuração");
        status.setTextColor(Color.GREEN);
        status.setPadding(0, 16, 0, 12);
        layout.addView(status);

        TextView footer = new TextView(this);
        footer.setText("No jogo, posicione o quadrado OCR sobre a palavra. O painel flutuante controla as bolinhas. Teste primeiro com poucas bolinhas para conferir a resposta do jogo.");
        footer.setTextColor(Color.LTGRAY);
        footer.setTextSize(13);
        layout.addView(footer);
        setContentView(scroll);
    }

    private EditText numberField(long value) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setTextColor(Color.WHITE);
        field.setSingleLine(true);
        field.setText(String.valueOf(Math.max(1L, value)));
        return field;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private Spinner unitSpinner(int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"MS (milissegundos)", "Segundos", "Minutos"});
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(2, selected)));
        return spinner;
    }

    private void addLabel(LinearLayout layout, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 14, 0, 2);
        layout.addView(label);
    }

    private long readInterval(EditText field, String label) {
        long value = Long.parseLong(field.getText().toString().trim());
        if (value < 1) throw new IllegalArgumentException(label);
        return value;
    }

    private long toMillis(long value, int selectedUnit) {
        long multiplier = selectedUnit == 0 ? 1L : (selectedUnit == 1 ? 1000L : 60000L);
        if (value > Long.MAX_VALUE / multiplier) throw new IllegalArgumentException("Intervalo muito grande.");
        return Math.max(50L, Math.min(86400000L, value * multiplier));
    }

    private boolean saveSettings() {
        String target = word.getText().toString().trim();
        if (target.isEmpty()) {
            status.setText("Digite a palavra que o OCR deve procurar.");
            return false;
        }
        try {
            long ocrValue = readInterval(ocrInterval, "Informe a velocidade do OCR.");
            long clickValue = readInterval(clickInterval, "Informe a velocidade das bolinhas.");
            int ocrSelected = ocrUnit.getSelectedItemPosition();
            int clickSelected = clickUnit.getSelectedItemPosition();
            long ocrMs = toMillis(ocrValue, ocrSelected);
            long clickMs = toMillis(clickValue, clickSelected);
            String mode = clickMode.getSelectedItemPosition() == 0 ? "solo" : "custom";
            SharedPreferences.Editor edit = getSharedPreferences("automation", MODE_PRIVATE).edit();
            edit.putString("ocr_word", target)
                .putLong("ocr_interval_value", ocrValue)
                .putInt("ocr_interval_unit", ocrSelected)
                .putLong("ocr_interval_ms", ocrMs)
                .putLong("click_interval_value", clickValue)
                .putInt("click_interval_unit", clickSelected)
                .putLong("click_default_interval_ms", clickMs)
                .putString("click_mode", mode);
            // Keep legacy keys readable by older builds; new code uses separate settings.
            edit.putLong("global_interval_value", clickValue)
                .putInt("global_unit", clickSelected)
                .putLong("global_interval_ms", clickMs);
            edit.apply();
            status.setText("Configurações salvas. OCR e bolinhas têm velocidades separadas.");
            return true;
        } catch (Exception e) {
            status.setText(e.getMessage() == null ? "Confira os intervalos informados." : e.getMessage());
            return false;
        }
    }

    private void beginCapture() {
        if (!TapAccessibilityService.isReady()) {
            status.setText("Ative primeiro a acessibilidade do OCR Auto Clicker.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            status.setText("Libere a permissão de sobreposição primeiro.");
            return;
        }
        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            // Stop an earlier capture service before creating the new overlays/settings.
            stopService(new Intent(this, ScreenCaptureService.class));
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("captureData", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("OCR ativo. Modo " +
                (clickMode.getSelectedItemPosition() == 0 ? "Solo" : "Personalizado") + ".");
        } else status.setText("Captura de tela cancelada.");
    }
}
