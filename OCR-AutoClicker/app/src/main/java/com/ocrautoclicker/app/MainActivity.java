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
    private EditText word, interval;
    private Spinner unit;
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
        version.setText("BETA 0.6 • 1 OCR + ATÉ 100 CLIQUES");
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

        addLabel(layout, "VELOCIDADE GLOBAL / INTERVALO PADRÃO");
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        interval = new EditText(this);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        interval.setTextColor(Color.WHITE);
        interval.setSingleLine(true);
        interval.setText(String.valueOf(prefs.getLong("global_interval_value", 2)));
        speedRow.addView(interval, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        unit = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"MS (milissegundos)", "Segundos", "Minutos"});
        unit.setAdapter(adapter);
        unit.setSelection(prefs.getInt("global_unit", 1));
        speedRow.addView(unit, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        layout.addView(speedRow);

        TextView info = new TextView(this);
        info.setText("O intervalo global é usado como padrão. Toque e arraste as bolinhas para posicionar. Pressione uma bolinha por alguns instantes para configurar o intervalo individual dela.");
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
        footer.setText("No jogo, posicione o quadrado OCR sobre a palavra e use + para criar bolinhas de clique. O painel flutuante permite iniciar, pausar e ajustar a quantidade. O limite desta beta é 100 pontos.");
        footer.setTextColor(Color.LTGRAY);
        footer.setTextSize(13);
        layout.addView(footer);
        setContentView(scroll);
    }

    private void addLabel(LinearLayout layout, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 14, 0, 2);
        layout.addView(label);
    }

    private boolean saveSettings() {
        String target = word.getText().toString().trim();
        if (target.isEmpty()) {
            status.setText("Digite a palavra que o OCR deve procurar.");
            return false;
        }
        long value;
        try {
            value = Long.parseLong(interval.getText().toString().trim());
            if (value < 1) throw new NumberFormatException();
        } catch (Exception e) {
            status.setText("Informe um intervalo numérico maior que zero.");
            return false;
        }
        int selectedUnit = unit.getSelectedItemPosition();
        long multiplier = selectedUnit == 0 ? 1L : (selectedUnit == 1 ? 1000L : 60000L);
        if (value > Long.MAX_VALUE / multiplier) {
            status.setText("Esse intervalo é muito grande.");
            return false;
        }
        long intervalMs = Math.max(50L, Math.min(86400000L, value * multiplier));
        getSharedPreferences("automation", MODE_PRIVATE).edit()
            .putString("ocr_word", target)
            .putLong("global_interval_value", value)
            .putInt("global_unit", selectedUnit)
            .putLong("global_interval_ms", intervalMs)
            .putLong("ocr_interval_ms", intervalMs)
            .apply();
        status.setText("Configurações salvas.");
        return true;
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
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("captureData", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("OCR ativo. Use o painel flutuante para controlar as bolinhas.");
        } else status.setText("Captura de tela cancelada.");
    }
}
