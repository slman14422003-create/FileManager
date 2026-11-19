package com.fileman.app;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Base of every screen: applies the user's colour theme on top of the app theme, keeps the system bar
 * icons readable in day and night mode, and applies the layout language (see {@link Layout}).
 */
public abstract class BaseActivity extends AppCompatActivity {
    private float displayScale = 1f;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        displayScale = DisplaySize.scale(newBase);
        super.attachBaseContext(DisplaySize.wrap(newBase));
    }

    @Override
    public void applyOverrideConfiguration(Configuration overrideConfiguration) {
        if (overrideConfiguration != null && displayScale != 1f) {
            overrideConfiguration.densityDpi = Math.round(Resources.getSystem().getConfiguration().densityDpi * displayScale);
        }
        super.applyOverrideConfiguration(overrideConfiguration);
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        super.onApplyThemeResource(theme, resid, first);
        try {
            Appearance.applyTo(theme, this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            boolean light = !Appearance.isNight(this);
            c.setAppearanceLightStatusBars(light);
            c.setAppearanceLightNavigationBars(light);
        } catch (Throwable ignored) {
        }
        try {
            Blur.install(this, glassScreen());
        } catch (Throwable ignored) {
        }
        Layout.constrain(this, constrainContent());
        try {
            AiSheet.install(this);
        } catch (Throwable ignored) {
        }
        if (savedInstanceState == null && !(this instanceof HomeActivity)) Motion.enterScreen(this);
    }

    /** Screens that handle back themselves enable their callback only while needed, so the system's live back preview works otherwise. */
    protected void syncBack() {
    }

    @Override
    public void finish() {
        super.finish();
        if (Store.reduceMotion(this)) overridePendingTransition(0, 0);
    }

    private final Runnable syncRun = this::syncBack;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        boolean r = super.dispatchTouchEvent(ev);
        int a = ev.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            android.view.View d = getWindow().getDecorView();
            d.removeCallbacks(syncRun);
            d.postDelayed(syncRun, 150);
        }
        return r;
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncBack();
    }

    /** Screens whose header and bars should blur what scrolls under them. Viewers and capture screens return false. */
    protected boolean glassScreen() {
        return true;
    }

    /** The file this screen is showing, when there is exactly one (used by the smart sheet). */
    protected java.io.File aiFile() {
        return null;
    }

    /** The folder this screen is working in, when there is one (used by the smart sheet). */
    protected java.io.File aiFolder() {
        return null;
    }

    protected void useGroupedBackground() {
        int g = Ui.color(this, R.color.bg_group);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(g));
        getWindow().setStatusBarColor(g);
        getWindow().setNavigationBarColor(g);
    }

    /** Screens that must use the whole window (media, PDF, spreadsheets, editors) return false. */
    protected boolean constrainContent() {
        return true;
    }

    /** Called after the window size changed (rotation, split screen, folding) and the column was re-fitted. */
    protected void onLayoutChanged() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Layout.constrain(this, constrainContent());
        onLayoutChanged();
    }
}
