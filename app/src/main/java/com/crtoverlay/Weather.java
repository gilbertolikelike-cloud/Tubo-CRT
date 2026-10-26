package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Clima do painel do Bob, pelo Open-Meteo (gratuito, sem chave). Você digita só o nome da cidade;
 * o app não pede sua localização. Sem internet ou sem cidade, o painel simplesmente não mostra o clima.
 */
final class Weather {
    static final int CLEAR = 0, CLOUDY = 1, RAIN = 2, STORM = 3;
    private static final long REFRESH_MS = 15 * 60 * 1000L, VALID_MS = 3 * 60 * 60 * 1000L;
    private static boolean busy;

    private Weather() {}

    /** Procura a cidade e guarda as coordenadas. Devolve o nome achado, ou null. Chame fora da tela principal. */
    static String findCity(Context ctx, String name) throws IOException, JSONException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name="
                + URLEncoder.encode(name, "UTF-8");
        JSONArray results = new JSONObject(download(url)).optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject city = results.getJSONObject(0);
        String shown = city.getString("name");
        ctx.getSharedPreferences("crt", Context.MODE_PRIVATE).edit()
                .putString("wx_lat", String.valueOf(city.getDouble("latitude")))
                .putString("wx_lon", String.valueOf(city.getDouble("longitude")))
                .putString("wx_name", shown)
                .putLong("wx_time", 0)
                .apply();
        return shown;
    }

    /** Baixa o clima de novo se já passaram 15 minutos. Não faz nada sem cidade escolhida. */
    static void refreshIfStale(final Context ctx) {
        final SharedPreferences sp = ctx.getSharedPreferences("crt", Context.MODE_PRIVATE);
        final String lat = sp.getString("wx_lat", null), lon = sp.getString("wx_lon", null);
        if (lat == null || lon == null) return;
        if (System.currentTimeMillis() - sp.getLong("wx_time", 0) < REFRESH_MS) return;
        synchronized (Weather.class) {
            if (busy) return;
            busy = true;
        }
        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&timezone=auto";
                JSONObject now = new JSONObject(download(url)).getJSONObject("current");
                sp.edit().putFloat("wx_temp", (float) now.getDouble("temperature_2m"))
                        .putInt("wx_code", now.getInt("weather_code"))
                        .putLong("wx_time", System.currentTimeMillis())
                        .apply();
            } catch (IOException | JSONException | RuntimeException ignored) {
                // sem internet: tenta de novo na próxima vez
            } finally {
                synchronized (Weather.class) { busy = false; }
            }
        }).start();
    }

    private static String download(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "TuboCRT");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    static boolean hasData(SharedPreferences sp) {
        long t = sp.getLong("wx_time", 0);
        return t > 0 && System.currentTimeMillis() - t < VALID_MS;
    }

    static float temperature(SharedPreferences sp) { return sp.getFloat("wx_temp", 0f); }

    /** Código do tempo (WMO) vira: limpo, nublado, chuva ou tempestade. */
    static int kind(SharedPreferences sp) {
        if (!hasData(sp)) return CLEAR;
        int code = sp.getInt("wx_code", 0);
        if (code >= 95) return STORM;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if (code >= 2) return CLOUDY;
        return CLEAR;
    }
}
