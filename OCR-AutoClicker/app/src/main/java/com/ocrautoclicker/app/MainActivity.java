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
    private EditText interval;
    private TextView status;
    private EditText[] words = new EditText[10];
    private CheckBox[] enabled = new CheckBox[10];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 28, 28, 24);
        layout.setBackgroundColor(Color.rgb(20, 24, 30));
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("OCR AUTO CLICKER");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView version = new TextView(this);
        version.setText("BETA 0.3 • ATÉ 10 ÁREAS");
        version.setTextColor(Color.GREEN);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 8, 0, 20);
        layout.addView(version);

        TextView intro = new TextView(this);
        intro.setText("Configure uma palavra para cada área. Marque as áreas que deseja usar. Os quadrados poderão ser movidos e redimensionados quando o OCR estiver iniciado.");
        intro.setTextColor(Color.LTGRAY);
        intro.setTextSize(14);
        layout.addView(intro);

        SharedPreferences prefs = getSharedPreferences("ocr_zones", MODE_PRIVATE);
        for (int i = 0; i < 10; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 2);

            enabled[i] = new CheckBox(this);
            enabled[i].setText("Área " + (i + 1));
            enabled[i].setTextColor(Color.WHITE);
            enabled[i].setButtonTintList(android.content.res.ColorStateList.valueOf(Color.GREEN));
            enabled[i].setChecked(prefs.getBoolean("enabled_" + i, i == 0));
            row.addView(enabled[i], new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            words[i] = new EditText(this);
            words[i].setSingleLine(true);
            words[i].setTextColor(Color.WHITE);
            words[i].setHintTextColor(Color.GRAY);
            words[i].setHint(i == 0 ? "YES" : "Palavra para procurar");
            words[i].setText(prefs.getString("word_" + i, i == 0 ? "YES" : ""));
            words[i].setTextSize(15);
            row.addView(words[i], new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            layout.addView(row);
        }

        addLabel(layout, "Intervalo entre leituras (segundos):");
        interval = new EditText(this);
        interval.setText("2");
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setTextColor(Color.WHITE);
        layout.addView(interval);

        Button save = new Button(this);
        save.setText("SALVAR CONFIGURAÇÕES");
        layout.addView(save);
        save.setOnClickListener(v -> saveZones());

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
            } else {
                status.setText("Permissão de sobreposição já liberada.");
            }
        });

        Button start = new Button(this);
        start.setText("3. INICIAR OCR E CLIQUES");
        layout.addView(start);
        start.setOnClickListener(v -> {
            if (saveZones()) beginCapture();
        });

        Button stop = new Button(this);
        stop.setText("PARAR");
        layout.addView(stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status.setText("Parado pelo usuário.");
        });

        status = new TextView(this);
        status.setText("Status: aguardando configuração");
        status.setTextColor(Color.GREEN);
        status.setPadding(0, 18, 0, 12);
        layout.addView(status);

        TextView note = new TextView(this);
        note.setText("Ao iniciar, confirme a captura de tela. Os contornos mostram as áreas de busca. Use a bolinha com + para mover e a bolinha diagonal para redimensionar. Os controles ficam nas bordas para não bloquear os toques dentro da área.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(13);
        layout.addView(note);

        setContentView(scroll);
    }

    private void addLabel(LinearLayout layout, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 14, 0, 2);
        layout.addView(label);
    }

    private boolean saveZones() {
        SharedPreferences.Editor editor = getSharedPreferences("ocr_zones", MODE_PRIVATE).edit();
        boolean any = false;
        for (int i = 0; i < 10; i++) {
            String word = words[i].getText().toString().trim();
            boolean active = enabled[i].isChecked() && !word.isEmpty();
            editor.putString("word_" + i, word);
            editor.putBoolean("enabled_" + i, active);
            if (active) any = true;
        }
        editor.apply();
        if (!any) {
            status.setText("Ative pelo menos uma área e informe uma palavra.");
            return false;
        }
        status.setText("Configurações salvas.");
        return true;
    }

    private void beginCapture() {
        String seconds = interval.getText().toString().trim();
        int delay;
        try {
            delay = Integer.parseInt(seconds);
            if (delay < 1 || delay > 60) throw new NumberFormatException();
        } catch (Exception e) {
            status.setText("Use um intervalo entre 1 e 60 segundos.");
            return;
        }
        if (!TapAccessibilityService.isReady()) {
            status.setText("Ative primeiro a acessibilidade do OCR Auto Clicker.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            status.setText("Libere a permissão de sobreposição primeiro.");
            return;
        }
        Intent request = ((MediaProjectionManager)
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent();
        startActivityForResult(request, REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("captureData", data);
            service.putExtra("interval", Integer.parseInt(interval.getText().toString().trim()));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("OCR iniciado. Arraste os controles dos quadrados para ajustar as áreas.");
        } else {
            status.setText("Captura de tela cancelada.");
        }
    }
}
