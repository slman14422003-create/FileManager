package com.fileman.app;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Android 15+ (and every app targeting API 35/36) draws edge-to-edge and the old opt-out is gone on
 * Android 16. This keeps every screen clear of the status bar, navigation bar, cutout and keyboard.
 */
public class App extends Application {
    private static final List<Activity> OPEN = new ArrayList<>();
    private static Activity resumed;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Screens currently alive (used to recreate them when the colour theme changes). */
    static List<Activity> openActivities() {
        synchronized (OPEN) {
            return new ArrayList<>(OPEN);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        Lang.init(this);
        Appearance.applyMode(this);
        Wipe.resume(this);
        // a drive plugged in while one of the main screens is open: ask what to do with it
        Volumes.watch(this, () -> main.postDelayed(() -> {
            Activity a = resumed;
            if (a instanceof HomeActivity || a instanceof FileManagerActivity) UsbPrompt.check(a);
        }, 1200));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                Motion.install(a);
                synchronized (OPEN) {
                    OPEN.add(a);
                }
            }

            @Override
            public void onActivityStarted(Activity a) {
                if (Build.VERSION.SDK_INT < 35) return;
                View content = a.findViewById(android.R.id.content);
                if (content == null || content.getTag(R.id.tag_insets) != null) return;
                content.setTag(R.id.tag_insets, Boolean.TRUE);
                ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                    Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
                    v.setPadding(b.left, b.top, b.right, b.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
                ViewCompat.requestApplyInsets(content);
            }

            @Override
            public void onActivityResumed(final Activity a) {
                resumed = a;
                if (a instanceof HomeActivity) main.postDelayed(() -> {
                    if (resumed == a) UsbPrompt.check(a);
                }, 700);
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (resumed == a) resumed = null;
            }

            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override
            public void onActivityDestroyed(Activity a) {
                synchronized (OPEN) {
                    OPEN.remove(a);
                }
            }
        });
    }
}
