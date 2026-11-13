package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pastas de vídeo salvas (cada pasta é uma playlist) e a lista de vídeos que tem dentro delas. */
final class TvLibrary {
    static final class Video {
        final String name;
        final Uri uri;

        Video(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private static final String KEY = "tv_folders";

    private TvLibrary() {}

    static List<String> folders(SharedPreferences sp) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(KEY, "").split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    static void addFolder(SharedPreferences sp, String tree) {
        List<String> f = folders(sp);
        if (!f.contains(tree)) f.add(tree);
        sp.edit().putString(KEY, String.join("\n", f)).apply();
    }

    static void removeFolder(SharedPreferences sp, String tree) {
        List<String> f = folders(sp);
        f.remove(tree);
        sp.edit().putString(KEY, String.join("\n", f)).apply();
    }

    /** Nome curto da pasta (o último pedaço do caminho). */
    static String folderName(String tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            String path = id.substring(id.indexOf(':') + 1);
            if (path.isEmpty()) return "Memória interna";
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (RuntimeException e) {
            return "Pasta";
        }
    }

    /** Vídeos da pasta, em ordem natural (1, 2, 10). Se a pasta só tem subpastas, entra nelas. */
    static List<Video> videos(Context ctx, String tree) {
        List<Video> out = new ArrayList<>();
        try {
            Uri treeUri = Uri.parse(tree);
            collect(ctx, treeUri, DocumentsContract.getTreeDocumentId(treeUri), out, 0);
        } catch (RuntimeException ignored) {
            // pasta sem permissão ou apagada: lista vazia
        }
        Collections.sort(out, (a, b) -> natural(a.name, b.name));
        return out;
    }

    private static void collect(Context ctx, Uri tree, String parentId, List<Video> out, int depth) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        List<String> subfolders = new ArrayList<>();
        int before = out.size();
        try (Cursor c = ctx.getContentResolver().query(children,
                new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (Document.MIME_TYPE_DIR.equals(mime)) subfolders.add(id);
                else if (isVideo(name, mime)) out.add(new Video(withoutExtension(name), DocumentsContract.buildDocumentUriUsingTree(tree, id)));
            }
        }
        if (out.size() == before && depth < 2) for (String sub : subfolders) collect(ctx, tree, sub, out, depth + 1);
    }

    private static boolean isVideo(String name, String mime) {
        if (mime != null && mime.startsWith("video/")) return true;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".3gp")
                || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".m4v") || n.endsWith(".ts");
    }

    private static String withoutExtension(String name) {
        if (name == null) return "vídeo";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int natural(String x, String y) {
        String a = x.toLowerCase(Locale.ROOT), b = y.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
