package com.fileman.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Code and text screen. Reading runs on a windowed model (sparse row index plus a small page cache, see
 * {@link TextWindow}) so a file of any size shows its first screen at once. Editing works on a window of whole
 * lines; saving streams the untouched parts of the file around it, through a temp file and an atomic rename.
 */
public class FileEditActivity extends BaseActivity {
    @Override
    protected java.io.File aiFile() {
        return file;
    }

    @Override
    protected boolean glassScreen() {
        return false;
    }

    @Override
    protected boolean constrainContent() {
        return false;
    }

    /** Above this size the file can only be read. */
    private static final long EDIT_MAX_BYTES = 64L * 1024 * 1024;
    private static final long VIEW_MAX_BYTES = 1024L * 1024 * 1024;
    /** A file this small is edited whole; a bigger one is edited in a window of whole lines. */
    private static final long FULL_EDIT_BYTES = 384L * 1024;
    private static final int WIN_ROWS = 3000;
    private static final long WIN_BYTES = 256L * 1024;
    private static final int SAVE_CHUNK = 8192;
    private static final long AUTOSAVE_MS = 2000;
    private static final long HL_DEBOUNCE_MS = 150;

    private static final ExecutorService SAVE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-save");
        t.setDaemon(true);
        return t;
    });

    private static final class ExternalChange extends IOException {
        ExternalChange() {
            super("changed");
        }
    }

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService hlIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File file;
    private TextWindow tw;
    private ListView codeList;
    private EditText editor;
    private View loadingBar;
    private TextView status;
    private ImageButton saveBtn;
    private ImageButton modeBtn;
    private LinearLayout bar;
    private LinearLayout editBar;
    private CodeAdapter adapter;
    private OnBackPressedCallback editBack;

    private boolean canEdit;
    private boolean editMode;
    private boolean dirty;
    private boolean suppress;
    private boolean applying;
    private boolean conflict;
    private boolean errShown;
    private int editSeq;
    private long winStart;
    private boolean winWhole;
    private volatile long winEnd;
    private volatile long baseLen;
    private volatile long baseMod;
    private final UndoLog undo = new UndoLog();
    private int pendStart;
    private String pendRemoved = "";
    private String pendAdded = "";

    private int hilite = -1;
    private int numWidthPx;
    private int numDigits;
    private String lastQuery = "";
    private int accentSoft;
    private int[] palette;
    private final char[] numBuf = new char[12];
    private final SparseArray<CharSequence> hl = new SparseArray<>();
    private volatile int hlGen;
    private int loadGen;
    private AtomicBoolean indexCancel = new AtomicBoolean();
    private AtomicBoolean findCancel = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean tickQueued = new AtomicBoolean();

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
        accentSoft = Ui.color(this, R.color.accent_soft);
        palette = CodeColors.palette(this);

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
            String t = tw == null ? null : tw.text(pos);
            if (t != null) copy(t);
            return true;
        });
        codeList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_IDLE) scheduleListHl();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });

        saveBtn = findViewById(R.id.btnA1);
        saveBtn.setImageResource(R.drawable.ic_check);
        saveBtn.setContentDescription(getString(R.string.save));
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(v -> saveAndLeave());

        modeBtn = findViewById(R.id.btnA2);
        modeBtn.setImageResource(R.drawable.ic_code);
        modeBtn.setContentDescription(getString(R.string.code_edit));
        modeBtn.setVisibility(View.GONE);
        modeBtn.setOnClickListener(v -> {
            if (!editMode) enterEdit();
            else saveAndLeave();
        });

        ImageButton reload = findViewById(R.id.btnRefresh);
        reload.setOnClickListener(v -> {
            if (editMode) saveAndLeave();
            else load(topLine());
        });

        FrameLayout body = findViewById(R.id.body);
        bar = ViewerBar.attach(this, body, false,
                new int[]{R.drawable.ic_search, R.drawable.ic_forward, R.drawable.ic_arrow_down, R.drawable.ic_arrow_down},
                new int[]{R.string.code_find, R.string.code_goto, R.string.code_top, R.string.code_bottom},
                new View.OnClickListener[]{v -> findDialog(), v -> gotoDialog(), v -> jumpTop(), v -> jumpBottom()});
        bar.getChildAt(2).setRotation(180f);   // the "top" button is the down arrow turned over
        editBar = ViewerBar.attach(this, body, false,
                new int[]{R.drawable.ic_undo, R.drawable.ic_redo},
                new int[]{R.string.code_undo, R.string.code_redo},
                new View.OnClickListener[]{v -> doUndo(), v -> doRedo()});
        editBar.setVisibility(View.GONE);

        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (suppress || applying) return;
                pendStart = start;
                pendRemoved = count > 0 ? s.subSequence(start, start + count).toString() : "";
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppress || applying) return;
                pendAdded = count > 0 ? s.subSequence(start, start + count).toString() : "";
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppress) return;
                if (!applying) undo.record(pendStart, pendRemoved, pendAdded, SystemClock.uptimeMillis());
                pendRemoved = "";
                pendAdded = "";
                edited();
            }
        });
        editor.setOnScrollChangeListener((v, x, y, ox, oy) -> scheduleEditHl());

        editBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                saveAndLeave();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, editBack);
        load(0);
    }

    // ------------------------------------------------------------------ rows

    private final class CodeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tw == null ? 0 : tw.rows();
        }

        @Override
        public Object getItem(int i) {
            return null;
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
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) num.getLayoutParams();
            if (lp.width != numWidthPx) {
                lp.width = numWidthPx;
                num.setLayoutParams(lp);
            }
            TextWindow w = tw;
            String t = w == null ? null : w.text(pos);
            int no = w == null || t == null ? 0 : w.no(pos);
            if (no > 0) {
                int v = no;
                int p = numBuf.length;
                while (v > 0 && p > 0) {
                    numBuf[--p] = (char) ('0' + v % 10);
                    v /= 10;
                }
                num.setText(numBuf, p, numBuf.length - p);
            } else {
                num.setText("");
            }
            CharSequence cs = t == null ? null : hl.get(pos);
            if (cs != null) code.setText(cs);
            else code.setText(t == null || t.isEmpty() ? " " : t);   // an empty line keeps its height
            row.setBackgroundColor(pos == hilite ? accentSoft : 0);
            return row;
        }
    }

    private void ensureNumWidth(int maxLine) {
        int digits = Integer.toString(Math.max(1, maxLine)).length();
        if (digits <= numDigits) return;
        numDigits = digits;
        Paint p = new Paint();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits; i++) sb.append('8');
        numWidthPx = (int) Math.ceil(p.measureText(sb.toString())) + Ui.dp(this, 8);
    }

    // ------------------------------------------------------------------ load (background, first screen at once)

    private int topLine() {
        TextWindow w = tw;
        if (w == null) return 0;
        int r = codeList.getFirstVisiblePosition();
        while (r > 0 && w.no(r) == 0) r--;
        return Math.max(1, w.no(r));
    }

    private void load(final int restoreLine) {
        final int my = ++loadGen;
        indexCancel.set(true);
        final AtomicBoolean cancel = new AtomicBoolean();
        indexCancel = cancel;
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            TextWindow w = null;
            int err = 0;
            try {
                if (file.length() > VIEW_MAX_BYTES) throw new TextWindow.TooLarge();
                w = TextWindow.open(file);
            } catch (TextWindow.TooLarge e) {
                err = R.string.fm_too_large;
            } catch (TextWindow.NotText e) {
                err = R.string.fm_not_text;
            } catch (Throwable e) {
                err = SafeWrite.describe(e);
                if (err == R.string.fm_save_failed) err = R.string.fm_cannot_open;
            }
            final TextWindow win = w;
            final int fErr = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || my != loadGen) {
                    if (win != null) win.close();
                    return;
                }
                if (fErr != 0 || win == null) {
                    loadingBar.setVisibility(View.INVISIBLE);
                    Toast.makeText(this, fErr != 0 ? fErr : R.string.fm_cannot_open, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                attach(win);
            });
            if (win == null || fErr != 0) return;
            try {
                win.buildIndex(cancel, () -> postTick(my, win));
            } catch (Throwable e) {
                // a read error half way: keep what is indexed
            }
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || my != loadGen) return;
                finishLoad(win, restoreLine);
            });
        });
    }

    private void attach(TextWindow win) {
        TextWindow old = tw;
        tw = win;
        if (old != null) old.close();
        hl.clear();
        hlGen++;
        hilite = -1;
        numDigits = 0;
        ensureNumWidth((int) Math.min(9999999L, win.length() / 24));
        win.setListener(this::postRefresh);
        adapter.notifyDataSetChanged();
        status.setVisibility(View.VISIBLE);
    }

    private void postTick(final int my, final TextWindow w) {
        if (!tickQueued.compareAndSet(false, true)) return;
        ui.postDelayed(() -> {
            tickQueued.set(false);
            if (isFinishing() || isDestroyed() || my != loadGen || tw != w) return;
            ensureNumWidth(w.lines());
            adapter.notifyDataSetChanged();
            status.setText(getString(R.string.code_status, w.lines(), Fmt.size(w.length())));
            if (w.rows() >= 60) loadingBar.setVisibility(View.INVISIBLE);
            scheduleListHl();
        }, 120);
    }

    private void finishLoad(TextWindow w, int restoreLine) {
        loadingBar.setVisibility(View.INVISIBLE);
        ensureNumWidth(w.lines());
        adapter.notifyDataSetChanged();
        status.setText(getString(R.string.code_status, w.lines(), Fmt.size(w.length())));
        status.setVisibility(View.VISIBLE);
        canEdit = file.canWrite() && !w.inMemory() && file.length() <= EDIT_MAX_BYTES;
        modeBtn.setVisibility(canEdit ? View.VISIBLE : View.GONE);
        if (!canEdit && file.canWrite()) Toast.makeText(this, R.string.code_view_only, Toast.LENGTH_SHORT).show();
        if (restoreLine > 1) {
            final int my = loadGen;
            io.execute(() -> {
                try {
                    final int row = w.rowOfLine(restoreLine);
                    ui.post(() -> {
                        if (my == loadGen && !isDestroyed()) codeList.setSelection(row);
                    });
                } catch (Throwable ignored) {
                }
            });
        } else {
            codeList.setSelection(0);
        }
        scheduleListHl();
    }

    private void postRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        ui.postDelayed(() -> {
            refreshQueued.set(false);
            if (isFinishing() || isDestroyed()) return;
            adapter.notifyDataSetChanged();
            scheduleListHl();
        }, 40);
    }

    // ------------------------------------------------------------------ highlighting (visible rows only, stale jobs dropped)

    private final Runnable listHlRun = new Runnable() {
        @Override
        public void run() {
            final TextWindow w = tw;
            if (w == null || editMode || isFinishing() || isDestroyed()) return;
            int first = codeList.getFirstVisiblePosition();
            int last = codeList.getLastVisiblePosition();
            if (last < first) return;
            final int from = Math.max(0, first - 4);
            final int to = Math.min(w.rows() - 1, last + 4);
            if (to < from) return;
            final String[] src = new String[to - from + 1];
            boolean any = false;
            for (int i = 0; i < src.length; i++) {
                if (hl.get(from + i) != null) continue;
                String t = w.text(from + i);
                if (t != null && !t.isEmpty()) {
                    src[i] = t;
                    any = true;
                }
            }
            if (!any) return;
            final int my = ++hlGen;
            final int[] pal = palette;
            hlIo.execute(() -> {
                CharSequence[] out = new CharSequence[src.length];
                for (int i = 0; i < src.length; i++) {
                    if (my != hlGen) return;
                    if (src[i] != null) out[i] = CodeColors.tint(pal, src[i]);
                }
                ui.post(() -> {
                    if (my != hlGen || isFinishing() || isDestroyed() || tw != w) return;
                    if (hl.size() > 800) hl.clear();
                    for (int i = 0; i < out.length; i++) if (out[i] != null) hl.put(from + i, out[i]);
                    adapter.notifyDataSetChanged();
                });
            });
        }
    };

    private void scheduleListHl() {
        ui.removeCallbacks(listHlRun);
        ui.postDelayed(listHlRun, HL_DEBOUNCE_MS);
    }

    private final Runnable editHlRun = new Runnable() {
        @Override
        public void run() {
            if (!editMode || isFinishing() || isDestroyed()) return;
            android.text.Layout lay = editor.getLayout();
            if (lay == null) return;
            int top = Math.max(0, editor.getScrollY() - editor.getTotalPaddingTop());
            int l0 = Math.max(0, lay.getLineForVertical(top) - 3);
            int l1 = Math.min(lay.getLineCount() - 1, lay.getLineForVertical(top + editor.getHeight()) + 3);
            final Editable e = editor.getText();
            final int len = e.length();
            final int a = Math.min(len, lay.getLineStart(l0));
            final int b = Math.min(len, Math.min(lay.getLineEnd(l1), a + 60000));
            if (b <= a) return;
            final String block = e.subSequence(a, b).toString();
            final int my = ++hlGen;
            final int[] pal = palette;
            hlIo.execute(() -> {
                final int[] spans = CodeColors.scan(block, pal, a);
                if (my != hlGen) return;
                ui.post(() -> {
                    if (my != hlGen || !editMode || editor.getText().length() != len) return;
                    suppress = true;
                    try {
                        CodeColors.apply(editor.getText(), a, b, spans);
                    } finally {
                        suppress = false;
                    }
                });
            });
        }
    };

    private void scheduleEditHl() {
        ui.removeCallbacks(editHlRun);
        ui.postDelayed(editHlRun, HL_DEBOUNCE_MS);
    }

    // ------------------------------------------------------------------ edit mode

    private void enterEdit() {
        final TextWindow w = tw;
        if (w == null || !canEdit || !file.canWrite()) {
            Toast.makeText(this, R.string.code_view_only, Toast.LENGTH_SHORT).show();
            return;
        }
        final int top = Math.max(0, codeList.getFirstVisiblePosition());
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                long len = file.length();
                long[] win;
                if (len <= FULL_EDIT_BYTES) win = new long[]{w.dataStart(), len, 0, w.rows()};
                else win = w.windowAround(top, WIN_ROWS, WIN_BYTES);
                final long[] fw = win;
                final boolean whole = fw[0] <= w.dataStart() && fw[1] >= len;
                final String text = w.readRaw(fw[0], fw[1]);
                final long fLen = len;
                final long fMod = file.lastModified();
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loadingBar.setVisibility(View.INVISIBLE);
                    startEdit(text, fw[0], fw[1], whole, fLen, fMod);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loadingBar.setVisibility(View.INVISIBLE);
                    Toast.makeText(this, SafeWrite.describe(t) == R.string.fm_save_failed ? R.string.fm_cannot_open
                            : SafeWrite.describe(t), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startEdit(String text, long ws, long we, boolean whole, long len, long mod) {
        suppress = true;
        editor.setText(text);
        editor.setSelection(0);
        suppress = false;
        undo.clear();
        dirty = false;
        conflict = false;
        errShown = false;
        editSeq = 0;
        editMode = true;
        winStart = ws;
        winEnd = we;
        winWhole = whole;
        baseLen = len;
        baseMod = mod;
        if (editBack != null) editBack.setEnabled(true);
        hlGen++;
        codeList.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        editBar.setVisibility(View.VISIBLE);
        updateUndoButtons();
        editor.setVisibility(View.VISIBLE);
        saveBtn.setVisibility(View.VISIBLE);
        modeBtn.setImageResource(R.drawable.ic_close);
        modeBtn.setContentDescription(getString(R.string.code_view));
        if (!whole) status.setText(R.string.code_edit_part);
        scheduleEditHl();
    }

    private void leaveEdit() {
        ui.removeCallbacks(autoRun);
        ui.removeCallbacks(editHlRun);
        hlGen++;
        dirty = false;
        editMode = false;
        undo.clear();
        if (editBack != null) editBack.setEnabled(false);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        editor.setVisibility(View.GONE);
        suppress = true;
        editor.setText("");
        suppress = false;
        codeList.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        editBar.setVisibility(View.GONE);
        saveBtn.setVisibility(View.GONE);
        modeBtn.setImageResource(R.drawable.ic_code);
        modeBtn.setContentDescription(getString(R.string.code_edit));
    }

    private void edited() {
        dirty = true;
        editSeq++;
        hlGen++;   // any highlight job started before this change is now stale
        ui.removeCallbacks(autoRun);
        ui.postDelayed(autoRun, AUTOSAVE_MS);
        scheduleEditHl();
        updateUndoButtons();
    }

    private void updateUndoButtons() {
        if (editBar == null || editBar.getChildCount() < 2) return;
        editBar.getChildAt(0).setAlpha(undo.canUndo() ? 1f : 0.35f);
        editBar.getChildAt(1).setAlpha(undo.canRedo() ? 1f : 0.35f);
    }

    private void doUndo() {
        UndoLog.Diff d = undo.popUndo();
        if (d == null) return;
        applyDiff(d.start, d.added.length(), d.removed, d.start + d.removed.length());
    }

    private void doRedo() {
        UndoLog.Diff d = undo.popRedo();
        if (d == null) return;
        applyDiff(d.start, d.removed.length(), d.added, d.start + d.added.length());
    }

    private void applyDiff(int start, int removeLen, String insert, int cursor) {
        Editable e = editor.getText();
        int end = start + removeLen;
        if (start < 0 || end > e.length()) return;
        applying = true;
        try {
            e.replace(start, end, insert);
            editor.setSelection(Math.min(e.length(), cursor));
        } finally {
            applying = false;
        }
        Fx.light(editor);
    }

    // ------------------------------------------------------------------ saving

    private final Runnable autoRun = new Runnable() {
        @Override
        public void run() {
            if (editMode && dirty && !conflict) saveNow(false, true, null);
        }
    };

    private void saveAndLeave() {
        if (!editMode) return;
        if (!dirty) {
            leaveAndReload();
            return;
        }
        saveNow(true, false, this::leaveAndReload);
    }

    private void leaveAndReload() {
        int line = 1;
        if (tw != null) line = Math.max(1, topLine());
        leaveEdit();
        load(line);
    }

    /**
     * Copies the text in chunks (no giant String), then writes on a background thread: the untouched start and end
     * of the file are streamed around the edited window into a temp file that replaces the original atomically.
     */
    private void saveNow(final boolean toast, final boolean silent, final Runnable after) {
        if (!editMode || !dirty) {
            if (after != null) after.run();
            return;
        }
        ui.removeCallbacks(autoRun);
        final Editable e = editor.getText();
        final int n = e.length();
        final ArrayList<char[]> chunks = new ArrayList<>((n / SAVE_CHUNK) + 1);
        for (int i = 0; i < n; ) {
            int m = Math.min(SAVE_CHUNK, n - i);
            if (i + m < n && Character.isHighSurrogate(e.charAt(i + m - 1))) m++;
            char[] c = new char[m];
            e.getChars(i, i + m, c, 0);
            chunks.add(c);
            i += m;
        }
        final boolean endsNl = n > 0 && e.charAt(n - 1) == '\n';
        final int seq = editSeq;
        final long ws = winStart;
        final boolean whole = winWhole;
        final boolean forced = !silent;
        SAVE.execute(() -> {
            int err = 0;
            long newEnd = winEnd;
            try {
                final boolean exists = file.exists();
                final long we = winEnd;
                final long curLen = baseLen;
                if (!exists && !whole) throw new FileNotFoundException("ENOENT");
                if (exists && (file.length() != curLen || file.lastModified() != baseMod)) {
                    if (!whole || !forced) throw new ExternalChange();
                }
                final boolean midFile = !whole && we < curLen;
                final long[] written = {0};
                long est = (exists ? file.length() : 0) + (long) n * 3;
                SafeWrite.atomic(file, est, out -> {
                    if (exists) SafeWrite.copyRange(file, 0, ws, out);
                    for (char[] c : chunks) {
                        byte[] b = new String(c).getBytes(StandardCharsets.UTF_8);
                        out.write(b);
                        written[0] += b.length;
                    }
                    if (midFile && !endsNl) {
                        out.write('\n');
                        written[0]++;
                    }
                    if (exists && !whole) SafeWrite.copyRange(file, we, curLen, out);
                });
                newEnd = ws + written[0];
                baseLen = file.length();
                baseMod = file.lastModified();
                winEnd = newEnd;
            } catch (Throwable t) {
                err = t instanceof ExternalChange ? R.string.err_changed : SafeWrite.describe(t);
            }
            final int fErr = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fErr == 0) {
                    errShown = false;
                    conflict = false;
                    if (editSeq == seq) dirty = false;
                    if (toast) {
                        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                        Fx.done(codeList);
                    }
                    if (after != null) after.run();
                } else {
                    if (fErr == R.string.err_changed) conflict = true;
                    if (!silent || !errShown) {
                        errShown = true;
                        Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------------ navigation inside the file

    private void jumpTop() {
        codeList.setSelection(0);
    }

    private void jumpBottom() {
        if (tw == null || tw.rows() == 0) return;
        codeList.setSelectionFromTop(tw.rows() - 1, 0);
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
                    final int want;
                    try {
                        want = Integer.parseInt(e.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        return;
                    }
                    final TextWindow win = tw;
                    if (win == null) return;
                    io.execute(() -> {
                        try {
                            final int at = win.rowOfLine(want);
                            ui.post(() -> {
                                if (!isDestroyed() && tw == win) show(at);
                            });
                        } catch (Throwable ignored) {
                        }
                    });
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

    /** Searches on a background thread from the row below the current top (wrapping); a new query starts at the top. */
    private void findNext(boolean continueFromHere) {
        final TextWindow win = tw;
        if (win == null || win.rows() == 0 || lastQuery.isEmpty()) return;
        final String q = lastQuery;
        int from = continueFromHere ? Math.max(hilite, codeList.getFirstVisiblePosition()) + 1 : 0;
        if (from >= win.rows()) from = 0;
        final int start = from;
        findCancel.set(true);
        final AtomicBoolean cancel = new AtomicBoolean();
        findCancel = cancel;
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int hit = -1;
            try {
                hit = win.find(q, start, cancel);
            } catch (Throwable ignored) {
            }
            final int fHit = hit;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || cancel.get() || tw != win) return;
                loadingBar.setVisibility(View.INVISIBLE);
                if (fHit >= 0) show(fHit);
                else Toast.makeText(this, R.string.code_not_found, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(listHlRun);
        ui.removeCallbacks(editHlRun);
        if (editMode && dirty && !conflict) saveNow(false, true, null);   // never lose typing when the screen goes away
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tw != null && !editMode) scheduleListHl();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        indexCancel.set(true);
        findCancel.set(true);
        hlGen++;
        io.shutdownNow();
        hlIo.shutdownNow();
        if (tw != null) {
            tw.close();
            tw = null;
        }
    }
}
