package com.crtoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Os 34 botões desenhados pelo Gilberto (assets/ic/NN_nome.png). Se faltar algum, o app usa o botão antigo. */
final class Icons {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static Map<String, String> files;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF DST = new RectF();

    private Icons() { }

    static synchronized Bitmap get(Context ctx, String name) {
        if (files == null) {
            files = new HashMap<>();
            try {
                String[] list = ctx.getAssets().list("ic");
                if (list != null) for (String f : list) {
                    int u = f.indexOf('_'), dot = f.lastIndexOf('.');
                    if (u > 0 && dot > u) files.put(f.substring(u + 1, dot), f);
                }
            } catch (java.io.IOException ignored) { /* sem ícones: botões antigos */ }
        }
        Bitmap b = CACHE.get(name);
        if (b != null) return b;
        String f = files.get(name);
        if (f == null) return null;
        try (InputStream in = ctx.getAssets().open("ic/" + f)) {
            b = BitmapFactory.decodeStream(in);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
        if (b != null) CACHE.put(name, b);
        return b;
    }

    /** Desenha o ícone centrado em (cx,cy) com raio r. dim = botão desligado (fica apagado). */
    static boolean draw(Canvas c, Context ctx, String name, float cx, float cy, float r, boolean dim) {
        Bitmap b = get(ctx, name);
        if (b == null) return false;
        DST.set(cx - r, cy - r, cx + r, cy + r);
        PAINT.setAlpha(dim ? 110 : 255);
        c.drawBitmap(b, null, DST, PAINT);
        PAINT.setAlpha(255);
        return true;
    }
}
