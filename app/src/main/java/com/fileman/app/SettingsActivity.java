package com.fileman.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Language, hidden files, permissions and app info. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout content;

    private final androidx.activity.result.ActivityResultLauncher<Intent> treePick = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), r -> {
                Intent d = r.getData();
                android.net.Uri u = r.getResultCode() == RESULT_OK && d != null ? d.getData() : null;
                if (u == null) return;
                if (SaveTarget.keep(this, u)) {
                    android.widget.Toast.makeText(this, getString(R.string.dl_picked, SaveTarget.treeLabel(u)),
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, R.string.dl_pick_failed, android.widget.Toast.LENGTH_LONG).show();
                }
                render();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.settings_sub);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        scrollHeaderWithContent();
        Ui.autoGroup(this, content);
    }

    /** Moves the title bar into the scrolling page so it leaves the screen with the content, like the other screens. */
    private void scrollHeaderWithContent() {
        View header = (View) findViewById(R.id.btnBack).getParent();
        ViewGroup column = (ViewGroup) header.getParent();
        android.widget.ScrollView scroller = (android.widget.ScrollView) content.getParent();
        column.removeView(header);
        scroller.removeView(content);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String sig = signature();
        if (sig.equals(renderedSig)) return;
        renderedSig = sig;
        render();
    }

    private String renderedSig;

    private String signature() {
        return Store.language(this) + "|" + Store.themeMode(this) + "|" + Appearance.current(this).name + "|"
                + Store.showHidden(this) + "|" + Assistant.enabled(this) + "|" + Blur.enabled(this) + "|"
                + SaveTarget.label(this) + "|" + DisplaySize.level(this) + "|"
                + getResources().getConfiguration().toString().hashCode();
    }

    private String languageName() {
        String v = Store.language(this);
        if ("ar".equals(v)) return getString(R.string.lang_ar);
        if ("en".equals(v)) return getString(R.string.lang_en);
        return getString(R.string.lang_system);
    }

    private void render() {
        renderedSig = signature();
        content.removeAllViews();
        content.addView(Ui.hero(this, getString(R.string.app_name),
                getString(R.string.set_version, BuildConfig.VERSION_NAME)));

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_palette, true,
                getString(R.string.theme_title),
                getString(Appearance.modeName(Store.themeMode(this))) + " · "
                        + getString(Appearance.current(this).name), false, true),
                v -> startActivity(new Intent(this, ThemeActivity.class))));

        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_brand_mark, true,
                getString(R.string.ic_title), getString(R.string.ic_sub), false, true),
                v -> startActivity(new Intent(this, IconActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.lk_row)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_lock, true,
                getString(R.string.lk_row), AppLockUi.status(this), false, true),
                v -> AppLockUi.open(this, this::recreate)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_lock_fill, true,
                getString(R.string.v_title), getString(R.string.v_sub), false, true),
                v -> startActivity(new Intent(this, VaultActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.g_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_lock_fill, true,
                getString(R.string.g_title), getString(R.string.g_row_sub), false, true),
                v -> startActivity(new Intent(this, GuardActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.ds_title)));
        content.addView(displayCard());

        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_language, false,
                getString(R.string.set_language), languageName(), false, true), v -> pickLanguage()));

        Ui.Toggle hidden = Ui.toggle(this, content, R.string.set_hidden, R.string.set_hidden_sub,
                Store.showHidden(this));
        hidden.withIcon(R.drawable.ic_folder);
        hidden.onChange((b, on) -> Store.setShowHidden(this, on));
        content.addView(hidden.view);

        Ui.Toggle calm = Ui.toggle(this, content, R.string.set_reduce, R.string.set_reduce_sub, Store.reduceMotion(this));
        calm.withIcon(R.drawable.ic_star);
        calm.onChange((b, on) -> Store.setReduceMotion(this, on));
        content.addView(calm.view);

        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_file_text, false,
                getString(R.string.set_crash), getString(R.string.set_crash_sub), false, true), v -> {
            String log = CrashLog.read(this);
            if (log == null) {
                android.widget.Toast.makeText(this, R.string.set_crash_none, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log));
            android.widget.Toast.makeText(this, R.string.set_crash_done, android.widget.Toast.LENGTH_SHORT).show();
        }));

        Ui.Toggle smart = Ui.toggle(this, content, R.string.set_smart, R.string.set_smart_sub,
                Assistant.enabled(this));
        smart.withIcon(R.drawable.ic_star);
        smart.onChange((b, on) -> Assistant.setEnabled(this, on));
        content.addView(smart.view);

        final Ui.Toggle tap = Ui.toggle(this, content, R.string.tap_title, R.string.tap_sub, TapShare.enabled(this));
        tap.withIcon(R.drawable.ic_nfc);
        tap.onChange((b, on) -> ShareActivity.tapToggled(tap, on));
        content.addView(tap.view);


        content.addView(Ui.sectionTitle(this, getString(R.string.dl_section)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_download, false,
                getString(R.string.dl_title), SaveTarget.label(this), false, true), v -> pickDownloads()));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_access)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.perm_title), getString(R.string.perm_subtitle), false, true),
                v -> startActivity(new Intent(this, PermissionsActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_updates)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_download, false,
                getString(R.string.upd_check), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, true), v -> startActivity(new Intent(this, UpdateActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_about)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, false), null));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (nav == null) nav = NavBar.attach(this, NavBar.SETTINGS);
    }

    private View nav;

    private View displayCard() {
        final LinearLayout card = Ui.formCard(this);
        card.setPaddingRelative(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        final TextView preview = new TextView(this);
        preview.setText(R.string.ds_preview);
        preview.setTextColor(Ui.color(this, R.color.text_primary));
        preview.setTextSize(15);
        preview.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        card.addView(preview);

        final TextView name = new TextView(this);
        name.setTextColor(Ui.color(this, R.color.text_secondary));
        name.setTextSize(12);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        name.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        card.addView(name);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        TextView small = new TextView(this);
        small.setText("A");
        small.setTextSize(13);
        small.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        small.setTextColor(Ui.color(this, R.color.text_secondary));
        row.addView(small);

        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(DisplaySize.LEVELS - 1);
        bar.setProgress(DisplaySize.level(this));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.stroke)));
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView big = new TextView(this);
        big.setText("A");
        big.setTextSize(24);
        big.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        big.setTextColor(Ui.color(this, R.color.text_primary));
        row.addView(big);
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] names = {R.string.ds_l0, R.string.ds_l1, R.string.ds_l2, R.string.ds_l3, R.string.ds_l4};
        name.setText(names[DisplaySize.level(this)]);
        final int start = DisplaySize.level(this);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                name.setText(names[p]);
                preview.setTextSize(15 * (1f + 0.08f * p));
                if (user) b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
                if (b.getProgress() == DisplaySize.level(SettingsActivity.this)) return;
                DisplaySize.setLevel(SettingsActivity.this, b.getProgress());
                for (android.app.Activity act : App.openActivities()) act.recreate();
            }
        });
        return card;
    }

    private void pickDownloads() {
        String cur = SaveTarget.mode(this);
        final String[] items = {
                (SaveTarget.ASK.equals(cur) ? "● " : "○ ") + getString(R.string.dl_loc_ask),
                (SaveTarget.PUBLIC.equals(cur) ? "● " : "○ ") + getString(R.string.dl_loc_public),
                (SaveTarget.FOLDER.equals(cur) ? "● " : "○ ") + getString(R.string.dl_choose),
                getString(R.string.dl_full_access)};
        new Dlg(this).setTitle(R.string.dl_title).setMessage(R.string.dl_sub)
                .setItems(items, new int[]{R.drawable.ic_search, R.drawable.ic_download, R.drawable.ic_folder, R.drawable.ic_shield},
                        (d, which) -> {
                            if (which == 0) {
                                SaveTarget.setMode(this, SaveTarget.ASK);
                                render();
                            } else if (which == 1) {
                                SaveTarget.setMode(this, SaveTarget.PUBLIC);
                                render();
                            } else if (which == 2) {
                                try {
                                    treePick.launch(SaveTarget.systemPicker(this));
                                } catch (Exception e) {
                                    android.widget.Toast.makeText(this, R.string.dl_pick_failed,
                                            android.widget.Toast.LENGTH_LONG).show();
                                }
                            } else {
                                Perms.requestAllFiles(this);
                            }
                        }).show();
    }

    private void pickLanguage() {
        final String[] codes = {"system", "ar", "en"};
        String cur = Store.language(this);
        String[] names = {getString(R.string.lang_system), getString(R.string.lang_ar), getString(R.string.lang_en)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.set_language).setItems(names, (d, which) -> {
            Store.setLanguage(this, codes[which]);
            Lang.apply(this);   // recreates the open screens in the new language
        }).show();
    }
}
