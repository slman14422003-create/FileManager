package com.fileman.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Code and text reader. The text is shown as a virtualized list of lines (only the rows on screen exist), so
 * even a big file opens fast and scrolls smoothly from top to bottom. Small files can be switched to an editor.
 */
public class FileEditActivity extends BaseActivity {
    @Override
    protected boolean constrainContent() {
        return false;
    }

    private static final long VIEW_MAX_BYTES = 8L * 1024 * 1024;
    private static final long EDIT_MAX_BYTES = 400L * 1024;
    /** A longer line (minified code) is split into rows of this many characters. */
    private static final int CHUNK = 1500;

    private static final class Row {
        final String t;
        final int no;   // line number; 0 for the continuation of a split long line

        Row(String t, int no) {
            this.t = t;
            this.no = no;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private File file;
    private ListView codeList;
    private EditText editor;
    private View loadingBar;
    private TextView status;
    private ImageButton saveBtn;
    private ImageButton modeBtn;
    private LinearLayout bar;
    private CodeAdapter adapter;
    private String fullText;   // kept only for files small enough to edit
    private boolean dirty = false;
    private boolean suppress = false;
    private boolean editMode = false;
    private int hilite = -1;
    private int numWidthPx;
    private String lastQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ((TextView) findViewById(R.id.title)).setText(file.getName());
        ViewerBar.headerIcon(this, file);
        ((TextView) findViewById(R.id.subtitle)).setText(file.getAbsolutePath());
        status = findViewById(R.id.status);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        loadingBar = findViewById(R.id.loading);
        if (loadingBar instanceof ProgressBar) Ui.tint(this, (ProgressBar) loadingBar);
        editor = findViewById(R.id.editor);
        codeList = findViewById(R.id.codeList);

        adapter = new CodeAdapter();
        codeList.setAdapter(adapter);
        codeList.setFastScrollEnabled(true);
        codeList.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < rows.size()) copy(rows.get(pos).t);
            return true;
        });

        saveBtn = findViewById(R.id.btnA1);
        saveBtn.setImageResource(R.drawable.ic_check);
        saveBtn.setContentDescription(getString(R.string.save));
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(v -> save());

        modeBtn = findViewById(R.id.btnA2);
        modeBtn.setImageResource(R.drawable.ic_code);
        modeBtn.setContentDescription(getString(R.string.code_edit));
        modeBtn.setVisibility(View.GONE);
        modeBtn.setOnClickListener(v -> {
            if (!editMode) enterEdit();
            else if (dirty) confirmDiscard(this::leaveEdit);
            else leaveEdit();
        });

        ImageButton reload = findViewById(R.id.btnRefresh);
        reload.setOnClickListener(v -> {
            if (editMode && dirty) confirmDiscard(() -> {
                leaveEdit();
                load();
            });
            else {
                if (editMode) leaveEdit();
                load();
            }
        });

        bar = ViewerBar.attach(this, (FrameLayout) findViewById(R.id.body), false,
                new int[]{R.drawable.ic_search, R.drawable.ic_forward, R.drawable.ic_arrow_down, R.drawable.ic_arrow_down},
                new int[]{R.string.code_find, R.string.code_goto, R.string.code_top, R.string.code_bottom},
                new View.OnClickListener[]{v -> findDialog(), v -> gotoDialog(), v -> jumpTop(), v -> jumpBottom()});
        bar.getChildAt(2).setRotation(180f);   // the "top" button is the down arrow turned over

        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppress) dirty = true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (editMode) {
                    if (dirty) confirmDiscard(FileEditActivity.this::leaveEdit);
                    else leaveEdit();
                } else {
                    finish();
                }
            }
        });
        load();
    }

    // ------------------------------------------------------------------ rows

    private final class CodeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            TextView num;
            TextView code;
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                num = (TextView) row.getChildAt(0);
                code = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(FileEditActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                num = new TextView(FileEditActivity.this);
                num.setTypeface(Typeface.MONOSPACE);
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                num.setTextColor(Ui.color(FileEditActivity.this, R.color.text_hint));
                num.setGravity(Gravity.END);
                num.setPadding(0, 0, Ui.dp(FileEditActivity.this, 8), 0);
                row.addView(num, new LinearLayout.LayoutParams(numWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
                code = new TextView(FileEditActivity.this);
                code.setTypeface(Typeface.MONOSPACE);
                code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                code.setTextColor(Ui.color(FileEditActivity.this, R.color.text_primary));
                code.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_LTR);
                code.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                row.addView(code, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setPadding(Ui.dp(FileEditActivity.this, 4), 1, Ui.dp(FileEditActivity.this, 6), 1);
            }
            Row r = rows.get(pos);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) num.getLayoutParams();
            if (lp.width != numWidthPx) {
                lp.width = numWidthPx;
                num.setLayoutParams(lp);
            }
            num.setText(r.no > 0 ? Integer.toString(r.no) : "");
            code.setText(r.t.isEmpty() ? " " : r.t);   // an empty line keeps its height
            row.setBackgroundColor(pos == hilite ? Ui.color(FileEditActivity.this, R.color.accent_soft) : 0);
            return row;
        }
    }

    private static String clean(String s) {
        return s.indexOf('\t') >= 0 ? s.replace("\t", "    ") : s;
    }

    /** Splits the text into rows. Returns the number of lines. */
    private static int parse(String text, List<Row> out) {
        int n = text.length();
        int start = 0;
        int no = 0;
        while (true) {
            if (start >= n) break;   // no empty row after the final line break
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? n : nl;
            int e2 = end;
            if (e2 > start && text.charAt(e2 - 1) == '\r') e2--;
            no++;
            if (e2 - start <= CHUNK) {
                out.add(new Row(clean(text.substring(start, e2)), no));
            } else {
                for (int p = start; p < e2; p += CHUNK) {
                    out.add(new Row(clean(text.substring(p, Math.min(e2, p + CHUNK))), p == start ? no : 0));
                }
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return no;
    }

    private static String decode(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static boolean hasBom16(byte[] d) {
        return d.length >= 2 && (((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) || ((d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF));
    }

    // ------------------------------------------------------------------ load / edit / save

    private void load() {
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            final List<Row> out = new ArrayList<>();
            String full = null;
            int error = 0;
            int lines = 0;
            long len = file.length();
            try {
                if (len > VIEW_MAX_BYTES) {
                    error = R.string.fm_too_large;
                } else {
                    byte[] data = new byte[(int) len];
                    try (InputStream in = new FileInputStream(file)) {
                        int off = 0;
                        int n;
                        while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                    }
                    if (!hasBom16(data)) {
                        for (int i = 0; i < Math.min(data.length, 8192); i++) {
                            if (data[i] == 0) {
                                error = R.string.fm_not_text;
                                break;
                            }
                        }
                    }
                    if (error == 0) {
                        String text = decode(data);
                        lines = parse(text, out);
                        if (len <= EDIT_MAX_BYTES) full = text;
                    }
                }
            } catch (Throwable e) {
                error = R.string.fm_cannot_open;
            }
            final String fullText2 = full;
            final int err = error;
            final int nLines = lines;
            final long size = len;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadingBar.setVisibility(View.INVISIBLE);
                if (err != 0) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                rows.clear();
                rows.addAll(out);
                fullText = fullText2;
                hilite = -1;
                int digits = Integer.toString(Math.max(1, nLines)).length();
                Paint p = new Paint();
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < digits; i++) sb.append('8');
                numWidthPx = (int) Math.ceil(p.measureText(sb.toString())) + Ui.dp(this, 8);
                adapter.notifyDataSetChanged();
                codeList.setSelection(0);
                status.setText(getString(R.string.code_status, nLines, Fmt.size(size)));
                status.setVisibility(View.VISIBLE);
                boolean canEdit = file.canWrite();
                modeBtn.setVisibility(canEdit && fullText != null ? View.VISIBLE : View.GONE);
                if (canEdit && fullText == null) Toast.makeText(this, R.string.code_view_only, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void enterEdit() {
        if (fullText == null || !file.canWrite()) {
            Toast.makeText(this, R.string.code_view_only, Toast.LENGTH_SHORT).show();
            return;
        }
        suppress = true;
        editor.setText(fullText);
        suppress = false;
        dirty = false;
        editMode = true;
        codeList.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        saveBtn.setVisibility(View.VISIBLE);
        modeBtn.setImageResource(R.drawable.ic_close);
        modeBtn.setContentDescription(getString(R.string.code_view));
    }

    private void leaveEdit() {
        dirty = false;
        editMode = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        editor.setVisibility(View.GONE);
        codeList.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        saveBtn.setVisibility(View.GONE);
        modeBtn.setImageResource(R.drawable.ic_code);
        modeBtn.setContentDescription(getString(R.string.code_edit));
    }

    private void confirmDiscard(final Runnable onYes) {
        new Dlg(this)
                .setTitle(R.string.discard_title)
                .setMessage(R.string.discard_msg)
                .setPositiveButton(R.string.discard, (d, w) -> onYes.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void save() {
        if (!file.canWrite()) {
            Toast.makeText(this, R.string.fm_read_only, Toast.LENGTH_SHORT).show();
            return;
        }
        final byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            boolean ok = true;
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(data);
            } catch (Exception e) {
                ok = false;
            }
            final boolean fOk = ok;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadingBar.setVisibility(View.INVISIBLE);
                Toast.makeText(this, fOk ? R.string.saved : R.string.fm_save_failed, Toast.LENGTH_SHORT).show();
                if (fOk) {
                    leaveEdit();
                    load();   // show the saved text
                }
            });
        });
    }

    // ------------------------------------------------------------------ navigation inside the file

    private void jumpTop() {
        codeList.setSelection(0);
    }

    private void jumpBottom() {
        if (rows.isEmpty()) return;
        codeList.setSelectionFromTop(rows.size() - 1, 0);
    }

    private void show(int position) {
        hilite = position;
        adapter.notifyDataSetChanged();
        codeList.setSelectionFromTop(position, Ui.dp(this, 48));
    }

    private void gotoDialog() {
        LinearLayout box = Ui.box(this);
        final EditText e = Ui.edit(this, getString(R.string.code_line_hint), "");
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(e);
        new Dlg(this).setTitle(R.string.code_goto).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int want;
                    try {
                        want = Integer.parseInt(e.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        return;
                    }
                    int at = -1;
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).no >= want) {
                            at = i;
                            break;
                        }
                    }
                    if (at < 0) at = rows.size() - 1;
                    if (at >= 0) show(at);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void findDialog() {
        LinearLayout box = Ui.box(this);
        final EditText e = Ui.edit(this, getString(R.string.code_find_hint), lastQuery);
        box.addView(e);
        new Dlg(this).setTitle(R.string.code_find).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String q = e.getText().toString();
                    if (q.isEmpty()) return;
                    boolean same = q.equalsIgnoreCase(lastQuery);
                    lastQuery = q;
                    findNext(same);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    /** Finds the query in the rows below the current top row (wrapping to the start); a new query starts at the top. */
    private void findNext(boolean continueFromHere) {
        if (rows.isEmpty() || lastQuery.isEmpty()) return;
        final String q = lastQuery.toLowerCase(Locale.ROOT);
        final int n = rows.size();
        int from = continueFromHere ? Math.max(hilite, codeList.getFirstVisiblePosition()) + 1 : 0;
        for (int k = 0; k < n; k++) {
            int i = (from + k) % n;
            if (rows.get(i).t.toLowerCase(Locale.ROOT).contains(q)) {
                show(i);
                return;
            }
        }
        Toast.makeText(this, R.string.code_not_found, Toast.LENGTH_SHORT).show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
