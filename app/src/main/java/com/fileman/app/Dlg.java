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
 * Dialog builder used everywhere in the app (iOS look): an alert is a rounded card with a bold start-aligned title, the text and two
 * rounded buttons; an action sheet is two floating cards (the choices, then Cancel set apart).
 * <ul>
 *   <li><b>Alert</b> (title, message, buttons): Cancel and the main action sit side by side as pills
 *   (Cancel neutral, main action accent, destructive actions red); three or more buttons stack.</li>
 *   <li><b>Action sheet</b> (a list of choices without buttons): slides up from the bottom, one row per
 *   choice (optional outline icon) and an automatic Cancel pill.</li>
 * </ul>
 */
public class Dlg extends AlertDialog.Builder {
    private boolean hasItems, hasButton;
    private CharSequence titleText, messageText;
    private CharSequence[] itemLabels;
    private int[] itemIcons;
    private DialogInterface.OnClickListener itemListener;

    public Dlg(Context context) {
        super(context);
    }

    @Override
    public AlertDialog create() {
        final boolean sheet = hasItems && !hasButton;
        final AlertDialog[] ref = new AlertDialog[1];
        if (sheet) {
            super.setView(buildSheet(ref));
        } else {
            if (titleText != null) super.setTitle(titleText);
            if (messageText != null) super.setMessage(messageText);
            if (hasItems) super.setAdapter(new ChoiceAdapter(getContext(), itemLabels, itemIcons), itemListener);
        }
        final AlertDialog d = super.create();
        ref[0] = d;
        if (sheet) {
            Window w = d.getWindow();
            if (w != null) {
                w.setGravity(Gravity.BOTTOM);
                w.setWindowAnimations(R.style.SheetAnim);
                w.setBackgroundDrawable(new ColorDrawable(0));
            }
        }
        d.setOnShowListener(dialog -> style(d, sheet));
        return d;
    }

    @Override
    public AlertDialog.Builder setTitle(int titleId) {
        titleText = getContext().getString(titleId);
        return this;
    }

    @Override
    public AlertDialog.Builder setTitle(CharSequence title) {
        titleText = title;
        return this;
    }

    @Override
    public AlertDialog.Builder setMessage(int messageId) {
        messageText = getContext().getString(messageId);
        return this;
    }

    @Override
    public AlertDialog.Builder setMessage(CharSequence message) {
        messageText = message;
        return this;
    }

    // ------------------------------------------------------------------ action sheet: two floating cards

    private static GradientDrawable card(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.color(c, R.color.surface));
        g.setCornerRadius(Ui.dp(c, 26));
        g.setStroke(hairline(c), Ui.color(c, R.color.stroke_soft));
        return g;
    }

    /** Top card: optional small caption and the choices; second card: Cancel, set apart by a gap. */
    private View buildSheet(final AlertDialog[] ref) {
        final Context c = getContext();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 10));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(card(c));
        top.setClipToOutline(true);
        if (titleText != null || messageText != null) {
            TextView h = new TextView(c);
            StringBuilder sb = new StringBuilder();
            if (titleText != null) sb.append(titleText);
            if (messageText != null) sb.append(sb.length() > 0 ? "\n" : "").append(messageText);
            h.setText(sb);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextColor(Ui.color(c, R.color.text_secondary));
            h.setGravity(Gravity.CENTER);
            h.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 16));
            top.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            View line = new View(c);
            line.setBackgroundColor(Ui.color(c, R.color.stroke_soft));
            top.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairline(c)));
        }
        ListView lv = new ListView(c);
        lv.setAdapter(new SheetAdapter(c, itemLabels));
        lv.setDivider(new ColorDrawable(Ui.color(c, R.color.stroke_soft)));
        lv.setDividerHeight(hairline(c));
        lv.setSelector(android.R.color.transparent);
        lv.setVerticalScrollBarEnabled(false);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            if (ref[0] != null) ref[0].dismiss();
            if (itemListener != null) itemListener.onClick(ref[0], pos);
        });
        top.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = new TextView(c);
        cancel.setText(R.string.cancel);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(Ui.color(c, R.color.accent_text));
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(card(c));
        cancel.setClickable(true);
        Ui.press(c, cancel);
        cancel.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 58));
        kp.topMargin = Ui.dp(c, 8);
        root.addView(cancel, kp);
        return root;
    }

    private static final class SheetAdapter extends ArrayAdapter<CharSequence> {
        SheetAdapter(Context c, CharSequence[] items) {
            super(c, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context c = getContext();
            TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(c);
            CharSequence label = getItem(position);
            t.setText(label);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(c, 58));
            t.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, 10), Ui.dp(c, 20), Ui.dp(c, 10));
            t.setTextColor(Ui.color(c, destructive(c, label) ? R.color.bad : R.color.accent_text));
            t.setBackgroundResource(R.drawable.bg_tab_btn);
            return t;
        }
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

    /** Option lists become an action sheet: one start-aligned row per choice, hairlines between. */
    @Override
    public AlertDialog.Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        hasItems = true;
        itemLabels = items;
        itemIcons = null;
        itemListener = listener;
        return this;
    }

    /** Same, with an outline icon in front of each row (0 = no icon for that row). */
    public Dlg setItems(CharSequence[] items, int[] icons, DialogInterface.OnClickListener listener) {
        hasItems = true;
        itemLabels = items;
        itemIcons = icons;
        itemListener = listener;
        return this;
    }

    private static final int[] DESTRUCTIVE = {R.string.delete, R.string.sec_install_anyway};

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
        private final int[] icons;

        ChoiceAdapter(Context c, CharSequence[] items, int[] icons) {
            super(c, 0, items);
            this.icons = icons;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context c = getContext();
            LinearLayout row;
            if (convertView instanceof LinearLayout && "choice".equals(convertView.getTag())) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(c);
                row.setTag("choice");
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Ui.dp(c, 56));
                row.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 8));
                row.setBackgroundResource(R.drawable.bg_tab_btn);
                ImageView iv = new ImageView(c);
                iv.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
                iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38));
                ip.setMarginEnd(Ui.dp(c, 14));
                row.addView(iv, ip);
                TextView t = new TextView(c);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
                t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            ImageView iv = (ImageView) row.getChildAt(0);
            TextView t = (TextView) row.getChildAt(1);
            CharSequence label = getItem(position);
            boolean red = destructive(c, label);
            t.setText(label);
            t.setTextColor(Ui.color(c, red ? R.color.bad : R.color.text_primary));
            int res = icons != null && position < icons.length ? icons[position] : 0;
            if (res != 0) {
                iv.setImageResource(res);
                Ui.outlineIcon(c, iv, 10);
                if (red) iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.bad)));
                iv.setVisibility(View.VISIBLE);
            } else {
                iv.setVisibility(View.GONE);
            }
            return row;
        }
    }

    /** A centered result dialog with a big status icon (success or error). */
    public static AlertDialog result(Context c, boolean ok, CharSequence title, CharSequence message) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 18));

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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 16);
        box.addView(t, tl);

        TextView m = new TextView(c);
        m.setText(message);
        m.setTextColor(Ui.color(c, R.color.text_secondary));
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        m.setLineSpacing(0, 1.25f);
        m.setGravity(Gravity.CENTER);
        m.setTextIsSelectable(true);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 8);
        box.addView(m, ml);

        return new Dlg(c).setView(box).setPositiveButton(android.R.string.ok, null).show();
    }

    private static void style(AlertDialog d, boolean sheet) {
        Context c = d.getContext();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (sheet) d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (sheet) return;
        View tt = d.findViewById(androidx.appcompat.R.id.title_template);
        if (tt != null) {
            tt.setPadding(0, 0, 0, 0);
            ViewGroup.LayoutParams tlp = tt.getLayoutParams();
            if (tlp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) tlp).setMargins(0, 0, 0, 0);
        }
        View topPanel = d.findViewById(androidx.appcompat.R.id.topPanel);
        if (topPanel != null) topPanel.setPadding(0, 0, 0, 0);
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(c, R.color.text_primary));
            title.setGravity(Gravity.START);
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            title.setPaddingRelative(Ui.dp(c, 26), Ui.dp(c, 26), Ui.dp(c, 26), Ui.dp(c, 6));
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            msg.setTextColor(Ui.color(c, title != null ? R.color.text_secondary : R.color.text_primary));
            msg.setLineSpacing(0, 1.3f);
            msg.setGravity(Gravity.START);
            msg.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            msg.setPaddingRelative(Ui.dp(c, 26), Ui.dp(c, title != null ? 6 : 26), Ui.dp(c, 26), Ui.dp(c, 22));
        }
        ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(new ColorDrawable(Ui.color(c, R.color.stroke_soft)));
            lv.setDividerHeight(hairline(c));
            lv.setClipToPadding(false);
            lv.setPaddingRelative(0, 0, 0, Ui.dp(c, 6));
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

        View panel = d.findViewById(androidx.appcompat.R.id.buttonPanel);
        if (panel != null) panel.setPadding(0, 0, 0, 0);
        ViewParent parent = shown.get(0).getParent();
        if (parent instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) parent;
            bar.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            bar.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, 0), Ui.dp(c, 20), Ui.dp(c, 20));
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
            boolean main = b == pos || (single && !sheet);
            boolean red = destructive(c, b.getText());
            b.setAllCaps(false);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setMinHeight(Ui.dp(c, 48));
            b.setMinimumHeight(Ui.dp(c, 48));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
            b.setStateListAnimator(null);
            if (main) {
                b.setBackgroundResource(red ? R.drawable.dlg_btn_danger : R.drawable.dlg_btn_primary);
                b.setTextColor(Ui.color(c, R.color.on_accent));
            } else {
                b.setBackgroundResource(R.drawable.dlg_btn_secondary);
                b.setTextColor(Ui.color(c, red ? R.color.bad : R.color.text_primary));
            }
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
                m.width = row ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
                m.height = Ui.dp(c, 48);
                m.weight = row ? 1f : 0f;
                if (row) m.setMargins(Ui.dp(c, 5), 0, Ui.dp(c, 5), 0);
                else m.setMargins(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
                b.setLayoutParams(m);
            }
        }
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() != View.GONE;
    }
}
