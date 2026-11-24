package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Dublagem da Bíblia feita pelo Gilberto. O app NÃO renomeia, NÃO converte e NÃO mexe nos arquivos dele:
 * só reconhece pelo número que vem na frente do nome (0001_Gnesis_001.mp3 = capítulo 1 da Bíblia inteira,
 * 1189_Apocalipse_022.mp3 = último). O resto do nome (acentos faltando etc.) é ignorado.
 */
final class VoiceLibrary {
    private static final Pattern NAME = Pattern.compile("^(\\d{1,4})[_\\- ].*\\.(mp3|m4a|ogg|wav|aac|opus)$", Pattern.CASE_INSENSITIVE);

    private VoiceLibrary() { }

    static File importDir(Context ctx) {
        File f = new File(ctx.getFilesDir(), "dublagem");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** capítulo da Bíblia inteira (1..1189) -> arquivo. */
    static Map<Integer, Uri> index(Context ctx, SharedPreferences sp) {
        Map<Integer, Uri> map = new HashMap<>();
        File[] mine = importDir(ctx).listFiles();
        if (mine != null) for (File f : mine) {
            Matcher m = NAME.matcher(f.getName());
            if (m.matches()) map.put(Integer.parseInt(m.group(1)), Uri.fromFile(f));
        }
        String tree = sp.getString("voice_tree", null);
        if (tree != null) {
            try { scan(ctx, Uri.parse(tree), DocumentsContract.getTreeDocumentId(Uri.parse(tree)), map, 0); } catch (RuntimeException ignored) { /* pasta sumiu */ }
        }
        return map;
    }

    private static void scan(Context ctx, Uri tree, String docId, Map<Integer, Uri> map, int depth) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (android.database.Cursor c = ctx.getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 2) scan(ctx, tree, id, map, depth + 1);
                } else {
                    Matcher m = NAME.matcher(name == null ? "" : name);
                    if (m.matches()) map.put(Integer.parseInt(m.group(1)), DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        }
    }

    /** Importa um ZIP: copia só os áudios numerados, com o MESMO nome. Devolve quantos entraram (-1 = erro). */
    static int importZip(Context ctx, Uri zip) {
        int n = 0;
        File dir = importDir(ctx);
        try (InputStream raw = ctx.getContentResolver().openInputStream(zip);
             ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (!NAME.matcher(name).matches()) continue;
                File out = new File(dir, name);
                try (OutputStream os = new FileOutputStream(out)) {
                    int r;
                    while ((r = zis.read(buf)) > 0) os.write(buf, 0, r);
                }
                n++;
            }
            return n;
        } catch (java.io.IOException | RuntimeException ex) {
            return n > 0 ? n : -1;
        }
    }

    static void clearImported(Context ctx) {
        File[] fs = importDir(ctx).listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }
}
