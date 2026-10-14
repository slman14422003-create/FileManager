package com.fileman.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens a contacts file (.vcf): lists the contacts, shows and edits each one, repairs damaged files
 * (see {@link Contacts}) and saves clean vCard 3.0 back. The first save keeps the original as "name.vcf.bak".
 */
public class ContactsActivity extends BaseActivity {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int[] AVATAR_COLORS = {R.color.accent_text, R.color.ok, R.color.violet, R.color.warn,
            R.color.info, R.color.bad, R.color.lime};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService photoIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private File file;
    private final List<Contacts.Card> all = new ArrayList<>();
    private final List<Contacts.Card> shown = new ArrayList<>();
    private LinkedHashMap<Integer, Integer> fixes = new LinkedHashMap<>();
    private boolean dirty = false;
    private OnBackPressedCallback dirtyBack;

    private void setDirty(boolean d) {
        dirty = d;
        if (dirtyBack != null) dirtyBack.setEnabled(d);
    }

    private View loading;
    private TextView subtitle, summary, empty;
    private EditText search;
    private ListView list;
    private ContactAdapter adapter;

    private final LruCache<Contacts.Card, Bitmap> photos = new LruCache<>(48);
    private final Set<Contacts.Card> noPhoto = new HashSet<>();
    private final Set<Contacts.Card> photoLoading = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ((TextView) findViewById(R.id.title)).setText(file.getName());
        subtitle = findViewById(R.id.subtitle);
        subtitle.setText(Fmt.size(file.length()));
        ViewerBar.headerIcon(this, file);
        loading = findViewById(R.id.loading);
        if (loading instanceof ProgressBar) Ui.tint(this, (ProgressBar) loading);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (dirty) confirmDiscard(this::load);
            else load();
        });
        ImageButton more = findViewById(R.id.btnA2);
        more.setImageResource(R.drawable.ic_more);
        more.setContentDescription(getString(R.string.more));
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(v -> Opener.moreMenu(this, file,
                new String[]{getString(R.string.ct_report), getString(R.string.ct_dedupe), getString(R.string.ct_sort),
                        getString(R.string.ct_save_copy)},
                new Runnable[]{this::showReport, this::dedupe, this::sortAz, () -> save(true)}));
        buildBody();

        dirtyBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                confirmDiscard(() -> {
                    setDirty(false);
                    finish();
                });
            }
        };
        getOnBackPressedDispatcher().addCallback(this, dirtyBack);
        load();
    }

    private void buildBody() {
        FrameLayout holder = findViewById(R.id.holder);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        summary = new TextView(this);
        summary.setTextColor(Ui.color(this, R.color.text_secondary));
        summary.setTextSize(13.5f);
        summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        summary.setBackgroundResource(R.drawable.bg_note);
        summary.setPaddingRelative(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        summary.setOnClickListener(v -> showReport());
        Ui.press(this, summary);
        col.addView(Ui.block(this, summary));

        search = Ui.edit(this, getString(R.string.ct_search_hint), "");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        col.addView(Ui.block(this, search));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 100));
        list.setFastScrollEnabled(true);
        adapter = new ContactAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) showContact(shown.get(pos));
        });
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        holder.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(Ui.color(this, R.color.text_hint));
        empty.setTextSize(15);
        empty.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        holder.addView(empty, elp);

        ViewerBar.attach(this, holder, false,
                new int[]{R.drawable.ic_add, R.drawable.ic_check},
                new int[]{R.string.ct_add, R.string.save},
                new View.OnClickListener[]{v -> showEditor(null), v -> save(false)});
    }

    // ------------------------------------------------------------------ loading and saving

    private static byte[] readAll(File f) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length() + 16, Integer.MAX_VALUE / 2));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            Contacts.Result res = null;
            int err = 0;
            try {
                if (file.length() > MAX_BYTES) err = R.string.fm_cannot_open;
                else res = Contacts.parse(readAll(file));
            } catch (IllegalArgumentException e) {
                err = R.string.ct_not_vcard;
            } catch (Throwable e) {
                err = R.string.fm_cannot_open;
            }
            final Contacts.Result r = res;
            final int e = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.INVISIBLE);
                if (e != 0 || r == null) {
                    Toast.makeText(this, e != 0 ? e : R.string.fm_cannot_open, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                photos.evictAll();
                noPhoto.clear();
                all.clear();
                all.addAll(r.cards);
                fixes = r.fixes;
                setDirty(r.fixCount() > 0);   // repaired in memory: offer to save
                refresh();
            });
        });
    }

    private void save(final boolean asCopy) {
        if (!asCopy && !dirty) {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!asCopy && !file.canWrite()) {
            Toast.makeText(this, R.string.fm_read_only, Toast.LENGTH_LONG).show();
            return;
        }
        final byte[] data = Contacts.write(new ArrayList<>(all)).getBytes(StandardCharsets.UTF_8);
        loading.setVisibility(View.VISIBLE);
        io.execute(() -> {
            boolean ok = true;
            String note = null;
            try {
                File dir = file.getParentFile();
                if (asCopy) {
                    File target = uniqueCopy();
                    writeFile(target, data);
                    note = target.getName();
                } else {
                    File bak = new File(dir, file.getName() + ".bak");
                    if (!bak.exists()) {   // the very first original is the one worth keeping
                        writeFile(bak, readAll(file));
                        note = bak.getName();
                    }
                    writeFile(file, data);
                }
            } catch (Throwable e) {
                ok = false;
            }
            final boolean fOk = ok;
            final String fNote = note;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.INVISIBLE);
                if (!fOk) {
                    Toast.makeText(this, R.string.fm_save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                if (!asCopy) {
                    setDirty(false);
                    fixes.clear();
                    refresh();
                }
                Toast.makeText(this, fNote == null ? getString(R.string.saved)
                                : getString(asCopy ? R.string.ct_saved_copy : R.string.ct_saved_bak, fNote),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private File uniqueCopy() {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot) : ".vcf";
        File dir = file.getParentFile();
        File t = new File(dir, base + "_fixed" + ext);
        for (int i = 2; t.exists(); i++) t = new File(dir, base + "_fixed" + i + ext);
        return t;
    }

    /** Writes through a temporary file so a failure never leaves a half-written contacts file behind. */
    private static void writeFile(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), "." + target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private void confirmDiscard(final Runnable onYes) {
        new Dlg(this).setTitle(R.string.discard_title).setMessage(R.string.discard_msg)
                .setPositiveButton(R.string.discard, (d, w) -> onYes.run())
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        subtitle.setText(getString(R.string.ct_subtitle, all.size(), Fmt.size(file.length())));
        int total = 0;
        for (int v : fixes.values()) total += v;
        if (dirty && total > 0) summary.setText(getString(R.string.ct_summary_fixed, all.size(), total));
        else if (dirty) summary.setText(getString(R.string.ct_summary_edited, all.size()));
        else summary.setText(getString(R.string.ct_summary_ok, all.size()));
        int accent = Ui.color(this, dirty ? R.color.accent_text : R.color.text_secondary);
        summary.setTextColor(accent);
        applyFilter();
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String qd = Contacts.digits(q);
        shown.clear();
        for (Contacts.Card c : all) {
            if (q.isEmpty()) {
                shown.add(c);
                continue;
            }
            String key = c.searchKey();
            if (key.contains(q) || (qd.length() >= 2 && key.contains(qd))) shown.add(c);
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) {
            empty.setText(all.isEmpty() ? R.string.ct_empty : R.string.ct_empty_search);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private final class ContactAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_contact, parent, false);
            Contacts.Card c = shown.get(position);
            String name = c.display();
            ((TextView) v.findViewById(R.id.title)).setText(name.isEmpty() ? getString(R.string.ct_no_name) : name);
            TextView sub = v.findViewById(R.id.sub);
            String d = c.firstDetail();
            sub.setText(d);
            sub.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
            bindAvatar((TextView) v.findViewById(R.id.avatar), c);
            Ui.press(ContactsActivity.this, v.findViewById(R.id.card));
            Ui.shapeRow(ContactsActivity.this, v, position == 0, position == shown.size() - 1);
            return v;
        }
    }

    /** Round avatar: the contact's photo when it has one, otherwise a coloured circle with the first letter. */
    private void bindAvatar(final TextView av, final Contacts.Card c) {
        av.setTag(c);
        Bitmap bmp = photos.get(c);
        if (bmp != null) {
            RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bmp);
            d.setCircular(true);
            av.setText("");
            av.setBackground(d);
            return;
        }
        int col = Ui.color(this, AVATAR_COLORS[(c.display().hashCode() & 0x7fffffff) % AVATAR_COLORS.length]);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor((col & 0x00FFFFFF) | 0x26000000);
        av.setBackground(g);
        av.setTextColor(col);
        av.setText(c.initial());
        if (c.hasPhoto() && !noPhoto.contains(c) && photoLoading.add(c)) {
            photoIo.execute(() -> {
                final Bitmap b = decodePhoto(c);
                ui.post(() -> {
                    photoLoading.remove(c);
                    if (isFinishing() || isDestroyed()) return;
                    if (b == null) {
                        noPhoto.add(c);
                        return;
                    }
                    photos.put(c, b);
                    if (av.getTag() == c) bindAvatar(av, c);
                });
            });
        }
    }

    private static Bitmap decodePhoto(Contacts.Card c) {
        try {
            for (Contacts.Extra e : c.extras) {
                if (!"PHOTO".equals(e.name) || e.params.startsWith("VALUE=")) continue;
                byte[] raw = Base64.decode(e.value, Base64.DEFAULT);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                int side = Math.min(o.outWidth, o.outHeight);
                if (side <= 0) continue;
                int sample = 1;
                while (side / (sample * 2) >= 128) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                if (src != null) return ThumbnailUtils.extractThumbnail(src, 112, 112);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ view / edit / delete

    private String typeLabel(String types) {
        if (types == null || types.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : types.split(",")) {
            int res;
            switch (t.trim()) {
                case "CELL":
                    res = R.string.ct_t_cell;
                    break;
                case "HOME":
                    res = R.string.ct_t_home;
                    break;
                case "WORK":
                    res = R.string.ct_t_work;
                    break;
                case "FAX":
                    res = R.string.ct_t_fax;
                    break;
                case "MAIN":
                    res = R.string.ct_t_main;
                    break;
                case "OTHER":
                    res = R.string.ct_t_other;
                    break;
                default:
                    continue;   // PREF, INTERNET and vendor types are not worth showing
            }
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(res));
        }
        return sb.toString();
    }

    private void showContact(final Contacts.Card c) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10), 0);

        TextView av = new TextView(this);
        av.setGravity(Gravity.CENTER);
        av.setTextSize(26);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        bindAvatar(av, c);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72));
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        alp.bottomMargin = Ui.dp(this, 12);
        box.addView(av, alp);

        String name = c.display();
        TextView nm = new TextView(this);
        nm.setText(name.isEmpty() ? getString(R.string.ct_no_name) : name);
        nm.setTextColor(Ui.color(this, R.color.text_primary));
        nm.setTextSize(21);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setGravity(Gravity.CENTER);
        nm.setTextIsSelectable(true);
        box.addView(nm);

        String job = Contacts.joinNonEmpty(" · ", c.title, c.org);
        if (!job.isEmpty()) {
            TextView jt = new TextView(this);
            jt.setText(job);
            jt.setTextColor(Ui.color(this, R.color.text_secondary));
            jt.setTextSize(14);
            jt.setGravity(Gravity.CENTER);
            box.addView(jt);
        }

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 18);
        box.addView(rows, rlp);
        for (final Contacts.Field f : c.tels) {
            rows.addView(Ui.rowView(this, rows, new Row(R.drawable.ic_phone, false, f.value, typeLabel(f.types), false, true),
                    v -> phoneMenu(f.value)));
        }
        for (final Contacts.Field f : c.emails) {
            rows.addView(Ui.rowView(this, rows, new Row(R.drawable.ic_mail, false, f.value, typeLabel(f.types), false, true),
                    v -> mailMenu(f.value)));
        }
        for (Contacts.Adr a : c.adrs) {
            final String t = a.display();
            if (t.isEmpty()) continue;
            rows.addView(Ui.rowView(this, rows, new Row(R.drawable.ic_home, false, t, typeLabel(a.types), false, false),
                    v -> copy(t)));
        }
        if (!c.bday.isEmpty()) {
            final String t = c.bday;
            rows.addView(Ui.rowView(this, rows, new Row(R.drawable.ic_clock, false, t, null, false, false), v -> copy(t)));
        }
        if (!c.url.isEmpty()) {
            final String t = c.url;
            rows.addView(Ui.rowView(this, rows, new Row(R.drawable.ic_open, false, t, null, false, false), v -> {
                try {
                    String u = t.contains("://") ? t : "http://" + t;
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception e) {
                    Toast.makeText(this, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
                }
            }));
        }
        if (!c.note.isEmpty()) {
            final String t = c.note;
            rows.addView(Ui.rowView(this, rows, new Row(R.drawable.ic_file_text, false, getString(R.string.ct_note), t, false, false),
                    v -> copy(t)));
        }
        Ui.group(this, rows);
        sv.addView(box);

        AlertDialog d = new Dlg(this).setView(sv)
                .setPositiveButton(R.string.ct_edit, (dd, w) -> showEditor(c))
                .setNeutralButton(R.string.delete, (dd, w) -> confirmDelete(c))
                .setNegativeButton(R.string.ct_close, null)
                .create();
        d.show();
    }

    private void phoneMenu(final String number) {
        new Dlg(this).setTitle(number).setItems(new String[]{getString(R.string.ct_call), getString(R.string.ct_sms),
                getString(R.string.fm_copy)}, (d, which) -> {
            if (which == 0) launch(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
            else if (which == 1) launch(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))));
            else copy(number);
        }).show();
    }

    private void mailMenu(final String address) {
        new Dlg(this).setTitle(address).setItems(new String[]{getString(R.string.ct_mail), getString(R.string.fm_copy)},
                (d, which) -> {
                    if (which == 0) launch(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address)));
                    else copy(address);
                }).show();
    }

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.fm_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(final Contacts.Card c) {
        String n = c.display();
        new Dlg(this).setTitle(R.string.ct_delete_title)
                .setMessage(getString(R.string.ct_delete_msg, n.isEmpty() ? getString(R.string.ct_no_name) : n))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    all.remove(c);
                    setDirty(true);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void showEditor(final Contacts.Card existing) {
        final boolean isNew = existing == null;
        final Contacts.Card c = isNew ? new Contacts.Card() : existing;

        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.box(this);
        sv.addView(box);

        final EditText fGiven = Ui.edit(this, "", c.given);
        final EditText fFamily = Ui.edit(this, "", c.family);
        StringBuilder ph = new StringBuilder();
        for (Contacts.Field f : c.tels) ph.append(ph.length() > 0 ? "\n" : "").append(f.value);
        final EditText fPhones = Ui.editMulti(this, "", ph.toString(), 2);
        fPhones.setTextDirection(View.TEXT_DIRECTION_LTR);
        StringBuilder em = new StringBuilder();
        for (Contacts.Field f : c.emails) em.append(em.length() > 0 ? "\n" : "").append(f.value);
        final EditText fEmails = Ui.editMulti(this, "", em.toString(), 1);
        fEmails.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        fEmails.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText fOrg = Ui.edit(this, "", c.org);
        final EditText fTitle = Ui.edit(this, "", c.title);
        final String oldAdr = c.adrs.isEmpty() ? "" : c.adrs.get(0).display();
        final EditText fAdr = Ui.edit(this, "", oldAdr);
        final EditText fNote = Ui.editMulti(this, "", c.note, 2);

        addField(box, R.string.ct_first_name, fGiven);
        addField(box, R.string.ct_last_name, fFamily);
        addField(box, R.string.ct_phones, fPhones);
        addField(box, R.string.ct_emails, fEmails);
        addField(box, R.string.ct_org, fOrg);
        addField(box, R.string.ct_job, fTitle);
        addField(box, R.string.ct_address, fAdr);
        addField(box, R.string.ct_note, fNote);

        final AlertDialog d = new Dlg(this).setTitle(isNew ? R.string.ct_add : R.string.ct_edit).setView(sv)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null).create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // numbers and addresses are validated first so a typo never silently disappears
            List<String> numbers = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String line : lines(fPhones.getText().toString())) {
                String n = Contacts.cleanPhone(line);
                if (n == null) {
                    fPhones.setError(getString(R.string.ct_bad_phone));
                    return;
                }
                if (seen.add(Contacts.digits(n))) numbers.add(n);
            }
            List<String> mails = new ArrayList<>();
            Set<String> seenMail = new HashSet<>();
            for (String line : lines(fEmails.getText().toString())) {
                if (!line.matches("[^@\\s]+@[^@\\s]+")) {
                    fEmails.setError(getString(R.string.ct_bad_email));
                    return;
                }
                if (seenMail.add(line.toLowerCase(Locale.ROOT))) mails.add(line);
            }
            String given = fGiven.getText().toString().trim();
            String family = fFamily.getText().toString().trim();
            String org = fOrg.getText().toString().trim();
            if (given.isEmpty() && family.isEmpty() && numbers.isEmpty() && mails.isEmpty() && org.isEmpty()) {
                Toast.makeText(this, R.string.ct_need_one, Toast.LENGTH_SHORT).show();
                return;
            }

            boolean nameChanged = !given.equals(c.given) || !family.equals(c.family);
            c.given = given;
            c.family = family;
            if (nameChanged || c.fn.isEmpty()) {
                String fn = Contacts.joinNonEmpty(" ", c.prefix, given, c.additional, family, c.suffix);
                if (fn.isEmpty()) fn = !org.isEmpty() ? org : !numbers.isEmpty() ? numbers.get(0) : mails.get(0);
                c.fn = fn;
            }

            // keep each number's type (mobile, work...) when the number itself is unchanged
            Map<String, Contacts.Field> oldTels = new HashMap<>();
            for (Contacts.Field f : c.tels) oldTels.put(Contacts.digits(f.value), f);
            c.tels.clear();
            for (String n : numbers) {
                Contacts.Field o = oldTels.get(Contacts.digits(n));
                c.tels.add(new Contacts.Field(n, o != null ? o.types : "CELL", o != null ? o.group : ""));
            }
            Map<String, Contacts.Field> oldMails = new HashMap<>();
            for (Contacts.Field f : c.emails) oldMails.put(f.value.toLowerCase(Locale.ROOT), f);
            c.emails.clear();
            for (String m : mails) {
                Contacts.Field o = oldMails.get(m.toLowerCase(Locale.ROOT));
                c.emails.add(new Contacts.Field(m, o != null ? o.types : "INTERNET", o != null ? o.group : ""));
            }

            c.org = org;
            c.title = fTitle.getText().toString().trim();
            c.note = fNote.getText().toString().trim();
            String adr = fAdr.getText().toString().trim();
            if (!adr.equals(oldAdr)) {
                if (adr.isEmpty()) {
                    if (!c.adrs.isEmpty()) c.adrs.remove(0);
                } else {
                    Contacts.Adr a = c.adrs.isEmpty() ? new Contacts.Adr() : c.adrs.get(0);
                    for (int i = 0; i < 7; i++) a.parts[i] = "";
                    a.parts[2] = adr;
                    if (c.adrs.isEmpty()) c.adrs.add(a);
                }
            }
            if (isNew) {
                c.version = "3.0";
                all.add(0, c);
            }
            setDirty(true);
            refresh();
            if (isNew) list.setSelection(0);
            d.dismiss();
        });
    }

    private void addField(LinearLayout box, int labelRes, EditText e) {
        box.addView(Ui.label(this, getString(labelRes)));
        box.addView(e);
    }

    // ------------------------------------------------------------------ menu actions

    private void showReport() {
        StringBuilder sb = new StringBuilder();
        if (fixes.isEmpty()) {
            sb.append(getString(R.string.ct_report_clean));
        } else {
            for (Map.Entry<Integer, Integer> e : fixes.entrySet()) {
                sb.append("• ").append(getString(e.getKey(), e.getValue())).append('\n');
            }
        }
        sb.append("\n").append(getString(R.string.ct_report_hint));
        new Dlg(this).setTitle(R.string.ct_report_title).setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void dedupe() {
        int n = Contacts.removeDuplicates(all);
        if (n > 0) {
            setDirty(true);
            refresh();
            Toast.makeText(this, getString(R.string.ct_dedupe_done, n), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.ct_dedupe_none, Toast.LENGTH_SHORT).show();
        }
    }

    private void sortAz() {
        Contacts.sortByName(all);
        setDirty(true);
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdown();
        photoIo.shutdownNow();
    }
}
