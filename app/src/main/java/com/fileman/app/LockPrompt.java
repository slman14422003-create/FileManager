package com.fileman.app;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;

final class LockPrompt {
    private LockPrompt() {
    }

    interface Result {
        void password(String p);

        void cancelled();
    }

    static void ask(final Activity a, int titleRes, boolean wrong, final Result r) {
        if (a.isFinishing() || a.isDestroyed()) return;
        final EditText e = Ui.edit(a, a.getString(R.string.lock_enter), "");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final boolean[] answered = {false};
        Dlg d = new Dlg(a);
        d.setTitle(wrong ? R.string.lock_wrong : titleRes);
        d.setView(e);
        d.setPositiveButton(R.string.lock_open, (dd, w) -> {
            answered[0] = true;
            r.password(e.getText().toString());
        });
        d.setNegativeButton(R.string.cancel, (dd, w) -> {
            answered[0] = true;
            r.cancelled();
        });
        androidx.appcompat.app.AlertDialog dlg = d.create();
        dlg.setOnDismissListener(x -> {
            if (!answered[0]) r.cancelled();
        });
        dlg.show();
    }
}
