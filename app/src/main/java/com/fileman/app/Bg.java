package com.fileman.app;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Background executors: worker threads run at background priority so scans and decoding never steal frames from the UI thread. */
public final class Bg {
    private Bg() {
    }

    public static ThreadFactory factory(final String name) {
        return r -> new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            r.run();
        }, name);
    }

    public static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(factory(name));
    }

    public static ExecutorService fixed(int n, String name) {
        return Executors.newFixedThreadPool(n, factory(name));
    }
}
