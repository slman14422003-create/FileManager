package com.fileman.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs every few hours in the background; erases the vault when the owner has been away past the chosen limit. */
public class DeadManJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                if (Remote.overdue(app)) {
                    Vault.destroyAll(app, "dead-man timer");
                    Remote.setDeadDays(app, 0);
                }
            } catch (Throwable ignored) {
            }
            jobFinished(params, false);
        }, "fm-deadman").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
