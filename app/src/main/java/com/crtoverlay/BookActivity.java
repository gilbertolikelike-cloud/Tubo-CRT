package com.crtoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Estante de livros (PDFs) e leitor em forma de livro. Botões e letras grandes. */
public class BookActivity extends Activity implements BookView.Listener {
    private static final int REQ_FILES = 41, REQ_TREE = 42;
    private static final int[] SPINES = {0xFF8E2A1E, 0xFF1E4F8E, 0xFF2E6B34, 0xFF7A5A12, 0xFF5B2A7A, 0xFF1E6F6F};

    private static final class Entry {
        String name;
        Uri uri;
        String tree; // pasta de onde veio (null = PDF avulso)
    }

    private SharedPreferences sp;
    private float dens;
    private FrameLayout root;
    private LinearLayout libraryView, shelf, readerView, bar;
    private BookView book;
    private TextView info;
    private String currentKey = "";
    private Entry current;
    private boolean triedCopy;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("crt", MODE_PRIVATE);
        dens = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0804);
        buildLibrary();
        readerView = new LinearLayout(this);
        readerView.setOrientation(LinearLayout.VERTICAL);
        readerView.setVisibility(View.GONE);
        root.addView(libraryView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(readerView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        refreshShelf();
    }

    private int dp(int v) { return Math.round(v * dens); }

    private Button btn(String text, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(text);
        x.setTextSize(18);
        x.setAllCaps(false);
        x.setTextColor(0xFFFFFFFF);
        x.setMinHeight(dp(52));
        x.setMinWidth(dp(56));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF4A2A14);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(2), 0xFFC9A24B);
        x.setBackground(g);
        x.setOnClickListener(l);
        return x;
    }

    // ---------- estante ----------
    private void buildLibrary() {
        libraryView = new LinearLayout(this);
        libraryView.setOrientation(LinearLayout.VERTICAL);
        libraryView.setPadding(dp(18), dp(14), dp(18), dp(10));
        TextView title = new TextView(this);
        title.setText("Meus livros");
        title.setTextSize(28);
        title.setTextColor(0xFFFFD27A);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        libraryView.addView(title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        Button addFiles = btn("+ Adicionar PDFs", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FILES);
        });
        Button addTree = btn("+ Adicionar pasta", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(10);
        row.addView(addFiles, lp);
        row.addView(addTree, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        libraryView.addView(row);
        ScrollView sv = new ScrollView(this);
        shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        sv.addView(shelf, new ViewGroup.LayoutParams(-1, -2));
        libraryView.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private List<String> lines(String key) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(key, "").split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private void saveLines(String key, List<String> v) { sp.edit().putString(key, String.join("\n", v)).apply(); }

    private void refreshShelf() {
        new Thread(() -> {
            final List<Entry> all = new ArrayList<>();
            for (String u : lines("pdf_items")) {
                Entry e = new Entry();
                e.uri = Uri.parse(u);
                e.name = displayName(e.uri);
                all.add(e);
            }
            for (String t : lines("pdf_folders")) collect(Uri.parse(t), t, DocumentsContract.getTreeDocumentId(Uri.parse(t)), all, 0);
            Collections.sort(all, (a, c) -> a.name.toLowerCase(Locale.ROOT).compareTo(c.name.toLowerCase(Locale.ROOT)));
            main.post(() -> fillShelf(all));
        }).start();
    }

    private void collect(Uri tree, String treeStr, String parentId, List<Entry> out, int depth) {
        try (Cursor c = getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 2) collect(tree, treeStr, id, out, depth + 1);
                } else if ("application/pdf".equals(mime) || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf"))) {
                    Entry e = new Entry();
                    e.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    e.name = name == null ? "livro" : (name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name);
                    e.tree = treeStr;
                    out.add(e);
                }
            }
        } catch (RuntimeException ignored) {
            // pasta sem permissão ou apagada
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                return n.toLowerCase(Locale.ROOT).endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
            }
        } catch (RuntimeException ignored) {
            // sem nome: usa o fim do endereço
        }
        String p = uri.getLastPathSegment();
        return p == null ? "livro" : p;
    }

    private void fillShelf(List<Entry> all) {
        shelf.removeAllViews();
        if (all.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("A estante está vazia.\nToque em \"+ Adicionar PDFs\" ou \"+ Adicionar pasta\".");
            t.setTextSize(20);
            t.setTextColor(0xFFEFE6CC);
            t.setPadding(0, dp(20), 0, 0);
            shelf.addView(t);
            return;
        }
        for (final Entry e : all) {
            String key = String.valueOf(e.uri.toString().hashCode());
            int count = sp.getInt("pdf_count_" + key, 0), page = sp.getInt("pdf_page_" + key, -1);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1F140C);
            bg.setCornerRadius(dp(10));
            row.setBackground(bg);
            View spine = new View(this);
            GradientDrawable sg = new GradientDrawable();
            sg.setColor(SPINES[Math.abs(key.hashCode()) % SPINES.length]);
            sg.setCornerRadii(new float[]{dp(10), dp(10), 0, 0, 0, 0, dp(10), dp(10)});
            spine.setBackground(sg);
            row.addView(spine, new LinearLayout.LayoutParams(dp(20), dp(72)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), dp(8), dp(10), dp(8));
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextSize(20);
            name.setTextColor(0xFFFFFFFF);
            name.setMaxLines(2);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView sub = new TextView(this);
            sub.setText(count > 0 ? "Página " + Math.max(1, page + 1) + " de " + count : "Toque pra abrir");
            sub.setTextSize(16);
            sub.setTextColor(0xFFB8A98C);
            col.addView(name);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            shelf.addView(row, lp);
            row.setOnClickListener(v -> openBook(e));
            row.setOnLongClickListener(v -> { confirmRemove(e); return true; });
        }
    }

    private void confirmRemove(final Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(e.tree == null ? "Tirar este livro da estante?" : "Este livro vem de uma pasta. Tirar a pasta inteira da estante?")
                .setPositiveButton("Tirar", (d, w) -> {
                    if (e.tree == null) {
                        List<String> l = lines("pdf_items");
                        l.remove(e.uri.toString());
                        saveLines("pdf_items", l);
                    } else {
                        List<String> l = lines("pdf_folders");
                        l.remove(e.tree);
                        saveLines("pdf_folders", l);
                    }
                    refreshShelf();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == REQ_FILES) {
            List<String> l = lines("pdf_items");
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            for (Uri u : picked) {
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException ignored) { /* vale só até fechar */ }
                if (!l.contains(u.toString())) l.add(u.toString());
            }
            saveLines("pdf_items", l);
            refreshShelf();
        } else if (request == REQ_TREE && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException ignored) { /* vale só até fechar */ }
            List<String> l = lines("pdf_folders");
            if (!l.contains(u.toString())) l.add(u.toString());
            saveLines("pdf_folders", l);
            refreshShelf();
        }
    }

    // ---------- leitor ----------
    private void openBook(final Entry e) {
        current = e;
        triedCopy = false;
        currentKey = String.valueOf(e.uri.toString().hashCode());
        if (book != null) readerView.removeView(book);
        readerView.removeAllViews();
        book = new BookView(this);
        readerView.addView(book, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildBar();
        readerView.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        libraryView.setVisibility(View.GONE);
        readerView.setVisibility(View.VISIBLE);
        try {
            ParcelFileDescriptor f = getContentResolver().openFileDescriptor(e.uri, "r");
            book.open(f, sp.getInt("pdf_page_" + currentKey, -1), this);
        } catch (Exception ex) {
            onError("Não consegui abrir esse PDF");
        }
    }

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1A0F08);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.addView(btn("◀", v -> book.prev()));
        info = new TextView(this);
        info.setTextSize(17);
        info.setTextColor(0xFFFFFFFF);
        info.setGravity(Gravity.CENTER);
        info.setTypeface(null, android.graphics.Typeface.BOLD);
        info.setOnClickListener(v -> askPage());
        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btn("▶", v -> book.next()));
        bar.addView(btn("A−", v -> book.zoomBy(0.8f)));
        bar.addView(btn("A+", v -> book.zoomBy(1.25f)));
        bar.addView(btn("1⇄2", v -> book.toggleTwo()));
        bar.addView(btn("Cor", v -> book.cycleTheme()));
        bar.addView(btn("Estante", v -> closeBook()));
    }

    private void askPage() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextSize(22);
        new AlertDialog.Builder(this)
                .setTitle("Ir para a página")
                .setView(et)
                .setPositiveButton("Ir", (d, w) -> {
                    try { book.goTo(Integer.parseInt(et.getText().toString().trim())); } catch (NumberFormatException ignored) { /* vazio */ }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void saveProgress() {
        if (book == null || currentKey.isEmpty() || book.pageCount() <= 0) return;
        sp.edit().putInt("pdf_page_" + currentKey, book.currentLeft()).putInt("pdf_count_" + currentKey, book.pageCount()).apply();
    }

    private void closeBook() {
        saveProgress();
        if (book != null) { book.close(); book = null; }
        readerView.removeAllViews();
        readerView.setVisibility(View.GONE);
        libraryView.setVisibility(View.VISIBLE);
        refreshShelf();
    }

    @Override protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (book != null) book.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (readerView.getVisibility() == View.VISIBLE) closeBook(); else super.onBackPressed();
    }

    // ---------- BookView.Listener ----------
    @Override public void onPageInfo(String text) {
        if (info != null) info.setText(text);
    }

    @Override public void onTapCenter() {
        if (bar != null) bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** Alguns provedores de arquivo não deixam abrir o PDF direto: tenta de novo com uma cópia temporária. */
    @Override public void onError(String msg) {
        if (current == null || triedCopy) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }
        triedCopy = true;
        Toast.makeText(this, "Preparando o livro…", Toast.LENGTH_SHORT).show();
        final Entry e = current;
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(e.uri)) {
                File f = new File(getCacheDir(), "livro_" + currentKey + ".pdf");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                main.post(() -> { if (book != null) book.open(pfd, sp.getInt("pdf_page_" + currentKey, -1), BookActivity.this); });
            } catch (Exception ex) {
                main.post(() -> Toast.makeText(BookActivity.this, "Não consegui abrir esse PDF", Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
