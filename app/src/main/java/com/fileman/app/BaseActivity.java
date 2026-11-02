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
        if (glassBackdrop()) Glass.backdrop(this);
        if (aiButton()) Ai.attach(this);
        Layout.constrain(this, constrainContent());
    }

    /** The floating assistant button is shown on every normal screen; full-window viewers opt out. */
    protected boolean aiButton() {
        return true;
    }

    /** Screens that draw their own full-window content (media, PDF) return false to skip the glass backdrop. */
    protected boolean glassBackdrop() {
        return true;
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
