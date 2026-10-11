package com.fileman.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Language, hidden files, permissions and app info. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.settings_sub);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private String languageName() {
        String v = Store.language(this);
        if ("ar".equals(v)) return getString(R.string.lang_ar);
        if ("en".equals(v)) return getString(R.string.lang_en);
        return getString(R.string.lang_system);
    }

    private void render() {
        content.removeAllViews();

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_palette, true,
                getString(R.string.theme_title),
                getString(Appearance.modeName(Store.themeMode(this))) + " · "
                        + getString(Appearance.current(this).name), false, true),
                v -> startActivity(new Intent(this, ThemeActivity.class))));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_language, false,
                getString(R.string.set_language), languageName(), false, true), v -> pickLanguage()));

        Ui.Toggle hidden = Ui.toggle(this, content, R.string.set_hidden, R.string.set_hidden_sub,
                Store.showHidden(this));
        hidden.onChange((b, on) -> Store.setShowHidden(this, on));
        content.addView(hidden.view);

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
