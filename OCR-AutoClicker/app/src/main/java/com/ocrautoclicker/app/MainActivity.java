package com.ocrautoclicker.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 410;
    private EditText target;
    private EditText interval;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 36, 32, 24);
        layout.setBackgroundColor(Color.rgb(20, 24, 30));

        TextView title = new TextView(this);
        title.setText("OCR AUTO CLICKER");
        title.setTextSize(25);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView version = new TextView(this);
        version.setText("BETA 0.2 • OCR REAL");
        version.setTextColor(Color.GREEN);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 8, 0, 24);
        layout.addView(version);

        addLabel(layout, "Texto que deseja procurar:");
        target = new EditText(this);
        target.setSingleLine(true);
        target.setHint("Exemplo: YES");
        target.setTextColor(Color.WHITE);
        target.setHintTextColor(Color.GRAY);
        layout.addView(target);

        addLabel(layout, "Intervalo entre leituras (segundos):");
        interval = new EditText(this);
        interval.setText("2");
        interval.setInputType(2);
        interval.setTextColor(Color.WHITE);
        layout.addView(interval);

        Button accessibility = new Button(this);
        accessibility.setText("1. ATIVAR ACESSIBILIDADE");
        layout.addView(accessibility);
        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

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
        start.setOnClickListener(v -> beginCapture());

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
        status.setPadding(0, 20, 0, 12);
        layout.addView(status);

        TextView note = new TextView(this);
        note.setText("A captura da tela exige sua confirmação. O Android também exige que você ative manualmente o serviço de acessibilidade. O OCR procura o texto na tela inteira e toca no centro da palavra/frase encontrada. Mantenha o app visível na notificação para poder parar.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(13);
        layout.addView(note);

        setContentView(layout);
    }

    private void addLabel(LinearLayout layout, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 14, 0, 2);
        layout.addView(label);
    }

    private void beginCapture() {
        String word = target.getText().toString().trim();
        String seconds = interval.getText().toString().trim();
        if (word.isEmpty()) {
            status.setText("Digite o texto que deseja procurar.");
            return;
        }
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
            service.putExtra("target", target.getText().toString().trim());
            service.putExtra("interval", Integer.parseInt(interval.getText().toString().trim()));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("OCR iniciado. Acompanhe a notificação para parar.");
        } else {
            status.setText("Captura de tela cancelada.");
        }
    }
}
