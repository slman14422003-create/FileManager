package com.fileman.app;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/** Settings and status of the file-theft guard and the entry to the protected-documents vault. */
public class GuardActivity extends BaseActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.g_title);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.g_sub);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setVisibility(View.GONE);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GuardService.ensure(this);
        render();
    }

    private void render() {
        content.removeAllViews();
        boolean perm = Perms.hasAllFiles(this);
        boolean on = GuardCore.enabled(this);
        String status = !on ? getString(R.string.g_status_off)
                : !perm ? getString(R.string.g_status_perm) : getString(R.string.g_status_on);
        content.addView(Ui.hero(this, getString(R.string.g_title), status));

        if (on && !perm) {
            content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, true,
                    getString(R.string.g_need_files), getString(R.string.g_need_files_sub), false, true),
                    v -> startActivity(new Intent(this, PermissionsActivity.class))));
        }

        Ui.Toggle t1 = Ui.toggle(this, content, R.string.g_enable, R.string.g_enable_sub, on);
        t1.withIcon(R.drawable.ic_lock);
        t1.onChange((bt, v) -> {
            GuardCore.setEnabled(this, v);
            render();
        });
        content.addView(t1.view);

        Ui.Toggle t2 = Ui.toggle(this, content, R.string.g_autokill, R.string.g_autokill_sub, GuardCore.autoKill(this));
        t2.withIcon(R.drawable.ic_cancel);
        t2.onChange((bt, v) -> GuardCore.setAutoKill(this, v));
        content.addView(t2.view);

        Ui.Toggle t3 = Ui.toggle(this, content, R.string.g_root, R.string.g_root_sub, GuardCore.useRoot(this));
        t3.withIcon(R.drawable.ic_code);
        t3.onChange((bt, v) -> GuardCore.setUseRoot(this, v));
        content.addView(t3.view);

        Ui.Toggle t4 = Ui.toggle(this, content, R.string.g_vault_trip, R.string.g_vault_trip_sub,
                GuardCore.wipeVaultOnTrip(this));
        t4.withIcon(R.drawable.ic_delete);
        t4.onChange((bt, v) -> GuardCore.setWipeVaultOnTrip(this, v));
        content.addView(t4.view);

        content.addView(Ui.sectionTitle(this, getString(R.string.g_detect)));
        boolean usage = GuardCore.usageGranted(this);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_chart, false,
                getString(R.string.g_usage), getString(usage ? R.string.g_usage_on : R.string.g_usage_sub), false, true),
                v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    } catch (Throwable t) {
                        Toast.makeText(this, R.string.v_open_failed, Toast.LENGTH_SHORT).show();
                    }
                }));
        Set<String> trusted = GuardCore.trusted(this);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_check_circle, false,
                getString(R.string.g_trusted), getString(R.string.g_trusted_n, trusted.size()), false, true),
                v -> trustedDialog()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                getString(R.string.g_test), getString(R.string.g_test_sub), false, true), v -> {
            GuardCore.trip(this, true);
            Toast.makeText(this, R.string.g_test_done, Toast.LENGTH_SHORT).show();
            content.postDelayed(this::render, 1500);
        }));

        content.addView(Ui.sectionTitle(this, getString(R.string.v_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_lock_fill, true,
                getString(R.string.v_title), getString(R.string.g_vault_row, Vault.list(this).size()), false, true),
                v -> startActivity(new Intent(this, VaultActivity.class))));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_clock, false,
                getString(R.string.v_limit), getString(R.string.v_limit_n, Vault.attemptLimit(this)), false, true),
                v -> limitDialog()));

        content.addView(Ui.sectionTitle(this, getString(R.string.g_log)));
        List<String[]> log = GuardCore.readLog(this);
        if (log.isEmpty()) {
            content.addView(Ui.footer(this, getString(R.string.g_log_empty)));
        } else {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            for (String[] e : log) {
                long ms = 0;
                try {
                    ms = Long.parseLong(e[0]);
                } catch (Throwable ignored) {
                }
                content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false, e[1],
                        df.format(new Date(ms)), false, false), null));
            }
        }
    }

    private void trustedDialog() {
        final List<String> pk = new ArrayList<>(GuardCore.trusted(this));
        if (pk.isEmpty()) {
            Toast.makeText(this, R.string.g_trusted_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] names = new CharSequence[pk.size()];
        for (int i = 0; i < names.length; i++) {
            String label = pk.get(i);
            try {
                label = String.valueOf(getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(pk.get(i), 0)));
            } catch (Throwable ignored) {
            }
            names[i] = label + "  (" + pk.get(i) + ")";
        }
        new Dlg(this).setTitle(R.string.g_trusted_remove).setItems(names, (d, which) -> {
            GuardCore.trust(this, pk.get(which), false);
            render();
        }).show();
    }

    private void limitDialog() {
        final int[] opts = {3, 5, 10};
        CharSequence[] labels = new CharSequence[opts.length];
        for (int i = 0; i < opts.length; i++) labels[i] = getString(R.string.v_limit_n, opts[i]);
        new Dlg(this).setTitle(R.string.v_limit).setItems(labels, (d, which) -> {
            Vault.setAttemptLimit(this, opts[which]);
            render();
        }).show();
    }
}
