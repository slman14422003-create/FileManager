package com.fileman.app;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Shows every permission the app uses, whether it is granted, and lets the user fix it in one tap. */
public class PermissionsActivity extends AppCompatActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        ((TextView) findViewById(R.id.title)).setText(R.string.perm_title);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.perm_subtitle);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> render());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        content.removeAllViews();
        content.addView(Ui.body(this, getString(R.string.perm_intro), 14, R.color.text_secondary));
        content.addView(Ui.sectionTitle(this, getString(R.string.perm_section_needed)));

        final boolean missing = !Perms.hasAllFiles(this) || !Perms.hasMedia(this) || !Perms.canInstall(this);
        Row fix = new Row(R.drawable.ic_check_circle, missing, getString(R.string.perm_fix_title),
                getString(missing ? R.string.perm_fix_sub : R.string.perm_all_ok), false, missing);
        content.addView(Ui.rowView(this, content, fix, (View v) -> Perms.fixNext(this)));

        boolean files = Perms.hasAllFiles(this);
        Row r1 = new Row(R.drawable.ic_folder, true, getString(R.string.perm_files_title),
                getString(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? R.string.perm_files_sub_r : R.string.perm_files_sub),
                false, !files);
        r1.badge(getString(files ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, files ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, r1, (View v) -> {
            if (Perms.hasAllFiles(this)) Perms.openAppSettings(this);
            else Perms.requestAllFiles(this);
        }));

        boolean media = Perms.hasMedia(this);
        Row rm = new Row(R.drawable.ic_image, true, getString(R.string.perm_media_title),
                getString(R.string.perm_media_sub), false, !media);
        rm.badge(getString(media ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, media ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, rm, (View v) -> {
            if (Perms.hasMedia(this)) Perms.openAppSettings(this);
            else Perms.requestMedia(this);
        }));

        boolean install = Perms.canInstall(this);
        Row r2 = new Row(R.drawable.ic_package, true, getString(R.string.perm_install_title),
                getString(R.string.perm_install_sub), false, !install);
        r2.badge(getString(install ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, install ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, r2, (View v) -> {
            if (Perms.canInstall(this)) Perms.openAppSettings(this);
            else Perms.requestInstall(this);
        }));

        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.pk_perm_installer), getString(R.string.pk_perm_installer_sub), false, false)
                .badge(getString(R.string.perm_granted), Ui.color(this, R.color.ok)), null));

        content.addView(Ui.sectionTitle(this, getString(R.string.def_section)));
        content.addView(Ui.body(this, getString(R.string.def_intro), 14, R.color.text_secondary));
        final int[] names = {R.string.def_type_pdf, R.string.def_type_image, R.string.def_type_video,
                R.string.def_type_audio, R.string.def_type_text, R.string.def_type_doc,
                R.string.def_type_zip, R.string.def_type_apk};
        final int[] icons = {R.drawable.ic_file_text, R.drawable.ic_image, R.drawable.ic_video,
                R.drawable.ic_music, R.drawable.ic_code, R.drawable.ic_file_text,
                R.drawable.ic_archive, R.drawable.ic_package};
        for (int k = 0; k < Perms.DEFAULT_TYPES.length; k++) {
            final String mime = Perms.DEFAULT_TYPES[k][0];
            final String ext = Perms.DEFAULT_TYPES[k][1];
            boolean def = Perms.isDefaultFor(this, mime, ext);
            Row r = new Row(icons[k], def, getString(names[k]), getString(R.string.def_row_sub), false, !def);
            r.badge(getString(def ? R.string.def_yes : R.string.def_no), Ui.color(this, def ? R.color.ok : R.color.bad));
            content.addView(Ui.rowView(this, content, r, (View v) -> Perms.chooseDefault(this, mime, ext)));
        }
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_settings, false,
                getString(R.string.def_system), getString(R.string.def_system_sub), false, true),
                v -> Perms.openDefaultAppsSettings(this)));

        content.addView(Ui.sectionTitle(this, getString(R.string.perm_section_other)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_settings, false,
                getString(R.string.perm_app_settings), getString(R.string.perm_app_settings_sub), false, true),
                v -> Perms.openAppSettings(this)));
    }
}
