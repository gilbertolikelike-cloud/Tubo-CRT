package com.crtoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONException;
import org.json.JSONObject;

/** Folha de quadros de uma animação (assets/bob_meta.json diz onde estão os quadros e quanto dura cada um). */
final class SpriteSheet {
    final Bitmap bitmap;
    final int cols, frames, frameW, frameH;
    final float ms, aspect, scale;
    final boolean blend, tipDown;
    /** Ponto de rotação (fração da célula) e altura do corpo da espada (fração da célula), quando o gif tem efeito em volta. */
    final float ax, ay, sh;

    private SpriteSheet(Bitmap bitmap, int cols, int frames, int frameW, int frameH, float ms, float aspect, float scale, boolean blend,
                        float ax, float ay, float sh, boolean tipDown) {
        this.bitmap = bitmap; this.cols = cols; this.frames = frames; this.frameW = frameW; this.frameH = frameH;
        this.ms = ms; this.aspect = aspect; this.scale = scale; this.blend = blend;
        this.ax = ax; this.ay = ay; this.sh = sh; this.tipDown = tipDown;
    }

    /** sample = 2 usa metade da resolução (gasta 4x menos memória). Devolve null se faltar o arquivo. */
    static SpriteSheet load(Context ctx, String key, int sample) {
        try (InputStream in = ctx.getAssets().open("bob_meta.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONObject o = new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8")).getJSONObject(key);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inScaled = false;
            opt.inSampleSize = Math.max(1, sample);
            Bitmap b;
            try (InputStream sheet = ctx.getAssets().open(o.getString("file"))) {
                b = BitmapFactory.decodeStream(sheet, null, opt);
            }
            if (b == null) return null;
            int cols = o.getInt("cols"), rows = o.getInt("rows");
            return new SpriteSheet(b, cols, o.getInt("frames"), b.getWidth() / cols, b.getHeight() / rows,
                    (float) o.getDouble("ms"), (float) o.getDouble("aspect"),
                    (float) o.optDouble("scale", 1.0), o.optBoolean("blend", true),
                    (float) o.optDouble("ax", 0.5), (float) o.optDouble("ay", 0.5), (float) o.optDouble("sh", 1.0),
                    "down".equals(o.optString("tip", "up")));
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Preenche "out" com o retângulo do quadro dentro da folha. */
    void frame(Rect out, int index) {
        int left = (index % cols) * frameW, top = (index / cols) * frameH;
        out.set(left, top, left + frameW, top + frameH);
    }

    void recycle() {
        bitmap.recycle();
    }
}
