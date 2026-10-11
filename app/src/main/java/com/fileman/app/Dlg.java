package com.fileman.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialog builder used everywhere in the app, styled like iOS:
 * <ul>
 *   <li><b>Alert</b> (title, message, buttons): a rounded centred card; one or two short buttons sit side by
 *   side with hairline separators (Cancel on the start side, the main action on the end side), three or more
 *   buttons stack. Button text is accent blue, the main action is bold, destructive actions are red.</li>
 *   <li><b>Action sheet</b> (a list of choices without buttons): slides up from the bottom, centred accent
 *   rows with hairlines and an automatic Cancel row.</li>
 * </ul>
 */
public class Dlg extends AlertDialog.Builder {
    private boolean hasItems, hasButton;

    public Dlg(Context context) {
        super(context);
    }

    @Override
    public AlertDialog create() {
        final boolean sheet = hasItems && !hasButton;
        if (sheet) super.setNegativeButton(R.string.cancel, null);   // an action sheet always has Cancel
        final AlertDialog d = super.create();
        if (sheet) {
            Window w = d.getWindow();
            if (w != null) {
                w.setGravity(Gravity.BOTTOM);
                w.setWindowAnimations(R.style.SheetAnim);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.y = Ui.dp(getContext(), 8);
                w.setAttributes(lp);
            }
        }
        d.setOnShowListener(dialog -> style(d));
        return d;
    }

    @Override
    public AlertDialog.Builder setPositiveButton(int textId, DialogInterface.OnClickListener l) {
        hasButton = true;
        return super.setPositiveButton(textId, l);
    }

    @Override
    public AlertDialog.Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
        hasButton = true;
        return super.setPositiveButton(text, l);
    }

    @Override
    public AlertDialog.Builder setNegativeButton(int textId, DialogInterface.OnClickListener l) {
        hasButton = true;
        return super.setNegativeButton(textId, l);
    }

    @Override
    public AlertDialog.Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
        hasButton = true;
        return super.setNegativeButton(text, l);
    }

    @Override
    public AlertDialog.Builder setNeutralButton(int textId, DialogInterface.OnClickListener l) {
        hasButton = true;
        return super.setNeutralButton(textId, l);
    }

    @Override
    public AlertDialog.Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) {
        hasButton = true;
        return super.setNeutralButton(text, l);
    }

    /** Option lists become an iOS action sheet: centred accent rows separated by hairlines. */
    @Override
    public AlertDialog.Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        hasItems = true;
        return setAdapter(new ChoiceAdapter(getContext(), items), listener);
    }

    private static final int[] DESTRUCTIVE = {R.string.delete};

    /** True for labels of actions that remove or abort something (shown in red). */
    static boolean destructive(Context c, CharSequence label) {
        if (label == null) return false;
        Set<String> set = new HashSet<>();
        for (int id : DESTRUCTIVE) set.add(c.getString(id));
        return set.contains(label.toString());
    }

    private static int hairline(Context c) {
        return Math.max(1, Ui.dp(c, 1) / 2);
    }

    private static final class ChoiceAdapter extends ArrayAdapter<CharSequence> {
        ChoiceAdapter(Context c, CharSequence[] items) {
            super(c, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context c = getContext();
            TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(c);
            CharSequence label = getItem(position);
            t.setText(label);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(c, 58));
            t.setPaddingRelative(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            t.setTextColor(Ui.color(c, destructive(c, label) ? R.color.bad : R.color.accent_text));
            t.setBackgroundResource(R.drawable.bg_tab_btn);
            return t;
        }
    }

    /** A centered result dialog with a big status icon (success or error). */
    public static AlertDialog result(Context c, boolean ok, CharSequence title, CharSequence message) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 22));

        int col = Ui.color(c, ok ? R.color.ok : R.color.bad);
        android.widget.FrameLayout halo = new android.widget.FrameLayout(c);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((col & 0x00FFFFFF) | 0x26000000);
        halo.setBackground(circle);
        ImageView icon = new ImageView(c);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_cancel);
        icon.setImageTintList(ColorStateList.valueOf(col));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        halo.addView(icon, new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42), Gravity.CENTER));
        box.addView(halo, new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72)));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.color(c, R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 16);
        box.addView(t, tl);

        TextView m = new TextView(c);
        m.setText(message);
        m.setTextColor(Ui.color(c, R.color.text_secondary));
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        m.setLineSpacing(0, 1.2f);
        m.setGravity(Gravity.CENTER);
        m.setTextIsSelectable(true);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 8);
        box.addView(m, ml);

        return new Dlg(c).setView(box).setPositiveButton(android.R.string.ok, null).show();
    }

    private static void style(AlertDialog d) {
        Context c = d.getContext();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(c, R.color.text_primary));
            title.setGravity(Gravity.CENTER);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 26), Ui.dp(c, 24), Ui.dp(c, 4));
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            boolean shortText = msg.getText() != null && msg.getText().length() <= 140;
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            msg.setTextColor(Ui.color(c, title != null ? R.color.text_secondary : R.color.text_primary));
            msg.setLineSpacing(0, 1.2f);
            msg.setGravity(shortText ? Gravity.CENTER : Gravity.START);
            msg.setTextAlignment(shortText ? View.TEXT_ALIGNMENT_CENTER : View.TEXT_ALIGNMENT_VIEW_START);
            msg.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, title != null ? 6 : 26), Ui.dp(c, 24), Ui.dp(c, 22));
        }
        ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(new ColorDrawable(Ui.color(c, R.color.stroke)));
            lv.setDividerHeight(hairline(c));
            lv.setClipToPadding(false);
            lv.setPaddingRelative(0, Ui.dp(c, 6), 0, 0);
            lv.setSelector(android.R.color.transparent);
        }

        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        List<Button> shown = new ArrayList<>();
        if (shown(pos)) shown.add(pos);
        if (shown(neu)) shown.add(neu);
        if (shown(neg)) shown.add(neg);
        if (shown.isEmpty()) return;

        // side by side for one or two short buttons (Cancel first, main action last); otherwise stacked
        boolean row = shown.size() <= 2;
        for (Button b : shown) if (b.getText().length() > 14) row = false;
        List<Button> order = new ArrayList<>();
        if (row) {
            if (shown(neg)) order.add(neg);
            if (shown(neu)) order.add(neu);
            if (shown(pos)) order.add(pos);
        } else {
            order.addAll(shown);
        }

        ViewParent parent = shown.get(0).getParent();
        if (parent instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) parent;
            bar.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            bar.setPadding(0, 0, 0, 0);
            bar.setMinimumHeight(0);
            View spacer = bar.findViewById(androidx.appcompat.R.id.spacer);
            if (spacer != null) spacer.setVisibility(View.GONE);
            for (Button b : order) {
                bar.removeView(b);
                bar.addView(b);
            }
        }
        boolean single = order.size() == 1;
        for (int i = 0; i < order.size(); i++) {
            Button b = order.get(i);
            boolean main = b == pos || single;
            boolean red = destructive(c, b.getText());
            b.setAllCaps(false);
            b.setTypeface(main && !red ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            b.setMinHeight(Ui.dp(c, 52));
            b.setMinWidth(0);
            b.setStateListAnimator(null);
            b.setBackgroundResource(row && i > 0 ? R.drawable.btn_ios_sep : R.drawable.btn_ios);
            b.setTextColor(Ui.color(c, red ? R.color.bad : R.color.accent_text));
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
                m.width = row ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
                m.height = Ui.dp(c, row ? 54 : 56);
                m.weight = row ? 1f : 0f;
                m.setMargins(0, 0, 0, 0);
                b.setLayoutParams(m);
            }
        }
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() != View.GONE;
    }
}
