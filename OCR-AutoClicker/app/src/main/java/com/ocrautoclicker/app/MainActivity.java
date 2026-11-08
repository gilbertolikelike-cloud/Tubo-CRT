package com.ocrautoclicker.app;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 40, 32, 24);
        layout.setBackgroundColor(Color.rgb(20, 24, 30));

        TextView title = new TextView(this);
        title.setText("OCR AUTO CLICKER");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView version = new TextView(this);
        version.setText("BETA 0.1");
        version.setTextColor(Color.GREEN);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 8, 0, 30);
        layout.addView(version);

        TextView label = new TextView(this);
        label.setText("Texto que deseja procurar:");
        label.setTextColor(Color.WHITE);
        layout.addView(label);

        EditText target = new EditText(this);
        target.setHint("Exemplo: YES");
        target.setSingleLine(true);
        target.setTextColor(Color.WHITE);
        target.setHintTextColor(Color.GRAY);
        layout.addView(target);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Intervalo de leitura (segundos):");
        intervalLabel.setTextColor(Color.WHITE);
        intervalLabel.setPadding(0, 20, 0, 0);
        layout.addView(intervalLabel);

        EditText interval = new EditText(this);
        interval.setHint("2");
        interval.setText("2");
        interval.setInputType(2);
        interval.setTextColor(Color.WHITE);
        layout.addView(interval);

        Button save = new Button(this);
        save.setText("SALVAR CONFIGURAÇÕES");
        layout.addView(save);

        TextView status = new TextView(this);
        status.setText("Status: aguardando configuração");
        status.setTextColor(Color.GREEN);
        status.setPadding(0, 25, 0, 0);
        layout.addView(status);

        TextView info = new TextView(this);
        info.setText(
            "\nPróximas funções:\n" +
            "• Quadrado flutuante ajustável\n" +
            "• Dedo indicador móvel\n" +
            "• Reconhecimento OCR\n" +
            "• Clique automático"
        );
        info.setTextColor(Color.LTGRAY);
        layout.addView(info);

        save.setOnClickListener(v -> {
            String word = target.getText().toString().trim();
            String seconds = interval.getText().toString().trim();

            if (word.isEmpty() || seconds.isEmpty()) {
                status.setText("Preencha os campos primeiro.");
            } else {
                status.setText("Configuração salva: " + word +
                    " / " + seconds + " segundos");
            }
        });

        setContentView(layout);
    }
}
