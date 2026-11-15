package com.fileman.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Dlg extends AlertDialog.Builder {
    private static final int SHEET_MAX_WIDTH_DP = 560;
    private static final int CENTERED_MESSAGE_MAX = 140;
    private static final float DIM = 0.5f;
    private float dimBase = DIM;
    private static final float SHEET_MAX_HEIGHT = 0.62f;

    private boolean hasItems, hasButton, hasView;
    private CharSequence titleText, messageText;
    private CharSequence[] itemLabels;
    private int[] itemIcons;
    private int iconRes;
    private DialogInterface.OnClickListener itemListener;
    private CharSequence positiveText, negativeText, neutralText;

    public Dlg(Context context) {
        super(context);
    }

    @Override
    public AlertDialog create() {
        final boolean sheet = hasItems && !hasButton;
        dimBase = Blur.dim(getContext(), DIM);
        final AlertDialog[] ref = new AlertDialog[1];
        boolean centered = false;
        if (sheet) {
            super.setView(buildSheet(ref));
        } else {
            centered = !hasItems && !hasView && (titleText != null || iconRes != 0)
                    && (messageText == null || (messageText.length() <= CENTERED_MESSAGE_MAX
                    && messageText.toString().indexOf('\n') < 0));
            if (centered) {
                super.setCustomTitle(buildHeader());
            } else {
                if (titleText != null) super.setTitle(titleText);
                if (iconRes != 0) super.setIcon(iconRes);
            }
            if (messageText != null) super.setMessage(messageText);
            if (hasItems) super.setAdapter(new ChoiceAdapter(getContext(), itemLabels, itemIcons), itemListener);
        }
        final AlertDialog d = super.create();
        ref[0] = d;
        if (!sheet) {
            Window aw = d.getWindow();
            if (aw != null) {
                Context cc = getContext();
                aw.setBackgroundDrawable(iosCard(cc, 14));
                int target = Ui.dp(cc, (hasView || hasItems) ? 320 : 280);
                int screen = cc.getResources().getDisplayMetrics().widthPixels;
                aw.setLayout(Math.min(target, screen - Ui.dp(cc, 48)), ViewGroup.LayoutParams.WRAP_CONTENT);
                aw.getDecorView().setClipToOutline(true);
            }
        }
        if (sheet) {
            Window w = d.getWindow();
            if (w != null) {
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                w.setWindowAnimations(R.style.SheetAnim);
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setDimAmount(dimBase);
                // size the window before it is shown, so it is not re-laid out while the slide-in plays
                int ww = Math.min(getContext().getResources().getDisplayMetrics().widthPixels,
                        Ui.dp(getContext(), SHEET_MAX_WIDTH_DP));
                w.setLayout(ww, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        final boolean center = centered;
        Blur.dialogWindow(d.getWindow(), getContext(), sheet);
        d.setOnShowListener(dialog -> style(d, sheet, center));
        return d;
    }

    @Override
    public Dlg setTitle(int titleId) {
        titleText = getContext().getString(titleId);
        return this;
    }

    @Override
    public Dlg setTitle(CharSequence title) {
        titleText = title;
        return this;
    }

    @Override
    public Dlg setMessage(int messageId) {
        messageText = getContext().getString(messageId);
        return this;
    }

    @Override
    public Dlg setMessage(CharSequence message) {
        messageText = message;
        return this;
    }

    @Override
    public Dlg setIcon(int iconId) {
        iconRes = iconId;
        return this;
    }

    @Override
    public Dlg setView(View view) {
        hasView = view != null;
        super.setView(view);
        return this;
    }

    @Override
    public Dlg setView(int layoutResId) {
        hasView = true;
        super.setView(layoutResId);
        return this;
    }

    private static int hairline(Context c) {
        return Math.max(1, Ui.dp(c, 1) / 2);
    }

    private static GradientDrawable card(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Blur.surface(c, Ui.color(c, R.color.surface), 0xE6));
        g.setCornerRadius(Ui.dp(c, (int) radiusDp));
        g.setStroke(hairline(c), Ui.color(c, R.color.stroke_soft));
        return g;
    }

    private static GradientDrawable iosCard(Context c, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Blur.surface(c, Ui.color(c, R.color.surface), 0xF2));
        g.setCornerRadius(Ui.dp(c, radiusDp));
        return g;
    }

    private static View hairlineView(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.color(c, R.color.stroke_soft));
        return v;
    }

    private static TextView sheetRow(Context c, CharSequence label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 57));
        t.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(Ui.color(c, destructive(c, label) ? R.color.bad : R.color.accent_text));
        t.setBackgroundResource(R.drawable.ios_press);
        t.setClickable(true);
        return t;
    }

    private static GradientDrawable halo(int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((color & 0x00FFFFFF) | 0x26000000);
        return circle;
    }

    private boolean destructiveAction() {
        Context c = getContext();
        return destructive(c, positiveText) || (positiveText == null && destructive(c, neutralText));
    }

    private View buildHeader() {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 2));
        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(Ui.color(c, destructiveAction() ? R.color.bad : R.color.accent_text)));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
        }
        if (titleText != null) {
            TextView t = new TextView(c);
            t.setText(titleText);
            t.setTextColor(Ui.color(c, R.color.text_primary));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = iconRes != 0 ? Ui.dp(c, 8) : 0;
            box.addView(t, tl);
        }
        return box;
    }

    /** iPhone action sheet: one rounded group of centred rows and a separate Cancel card below it. */
    private View buildSheet(final AlertDialog[] ref) {
        final Context c = getContext();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 8));

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(iosCard(c, 14));
        panel.setClipToOutline(true);

        if (titleText != null || messageText != null) {
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.VERTICAL);
            head.setGravity(Gravity.CENTER_HORIZONTAL);
            head.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
            if (titleText != null) {
                TextView h = new TextView(c);
                h.setText(titleText);
                h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextColor(Ui.color(c, R.color.text_secondary));
                h.setGravity(Gravity.CENTER);
                h.setMaxLines(2);
                h.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                head.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (messageText != null) {
                TextView m = new TextView(c);
                m.setText(messageText);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                m.setTextColor(Ui.color(c, R.color.text_secondary));
                m.setGravity(Gravity.CENTER);
                m.setMaxLines(3);
                m.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                mp.topMargin = titleText != null ? Ui.dp(c, 2) : 0;
                head.addView(m, mp);
            }
            panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(hairlineView(c), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairline(c)));
        }

        MaxHeightScroll scroll = new MaxHeightScroll(c, (int) (c.getResources().getDisplayMetrics().heightPixels * SHEET_MAX_HEIGHT));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable line = new GradientDrawable();
        line.setColor(Ui.color(c, R.color.stroke_soft));
        line.setSize(1, hairline(c));
        rows.setDividerDrawable(line);
        rows.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        for (int k = 0; k < itemLabels.length; k++) {
            final int pos = k;
            TextView row = sheetRow(c, itemLabels[k]);
            row.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                if (ref[0] != null) ref[0].dismiss();
                if (itemListener != null) itemListener.onClick(ref[0], pos);
            });
            rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = new TextView(c);
        cancel.setText(R.string.cancel);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(Ui.color(c, R.color.accent_text));
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(iosCard(c, 14));
        cancel.setClickable(true);
        Ui.press(c, cancel);
        cancel.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 57));
        kp.topMargin = Ui.dp(c, 8);
        root.addView(cancel, kp);
        return root;
    }

    private static void dim(AlertDialog d, float amount) {
        if (d != null && d.getWindow() != null) d.getWindow().setDimAmount(amount);
    }

    private static void attachDrag(View grip, final View target, final AlertDialog[] ref, final float base) {
        final float limit = Ui.dp(grip.getContext(), 90);
        final float[] down = new float[1];
        final VelocityTracker[] tracker = new VelocityTracker[1];
        grip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawY();
                    tracker[0] = VelocityTracker.obtain();
                    tracker[0].addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (tracker[0] != null) tracker[0].addMovement(e);
                    float dy = Math.max(0f, e.getRawY() - down[0]);
                    target.setTranslationY(dy);
                    dim(ref[0], base * (1f - Math.min(1f, dy / Math.max(1f, target.getHeight()))));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float vy = 0f;
                    if (tracker[0] != null) {
                        tracker[0].addMovement(e);
                        tracker[0].computeCurrentVelocity(1000);
                        vy = tracker[0].getYVelocity();
                        tracker[0].recycle();
                        tracker[0] = null;
                    }
                    boolean close = e.getActionMasked() == MotionEvent.ACTION_UP
                            && (target.getTranslationY() > limit || vy > 1400f);
                    if (close && ref[0] != null) {
                        ref[0].dismiss();
                    } else {
                        dim(ref[0], base);
                        target.animate().translationY(0f).setDuration(Motion.DUR).setInterpolator(Motion.OUT).start();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    @Override
    public Dlg setPositiveButton(int textId, DialogInterface.OnClickListener l) {
        hasButton = true;
        positiveText = getContext().getString(textId);
        super.setPositiveButton(textId, l);
        return this;
    }

    @Override
    public Dlg setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
        hasButton = true;
        positiveText = text;
        super.setPositiveButton(text, l);
        return this;
    }

    @Override
    public Dlg setNegativeButton(int textId, DialogInterface.OnClickListener l) {
        hasButton = true;
        negativeText = getContext().getString(textId);
        super.setNegativeButton(textId, l);
        return this;
    }

    @Override
    public Dlg setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
        hasButton = true;
        negativeText = text;
        super.setNegativeButton(text, l);
        return this;
    }

    @Override
    public Dlg setNeutralButton(int textId, DialogInterface.OnClickListener l) {
        hasButton = true;
        neutralText = getContext().getString(textId);
        super.setNeutralButton(textId, l);
        return this;
    }

    @Override
    public Dlg setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) {
        hasButton = true;
        neutralText = text;
        super.setNeutralButton(text, l);
        return this;
    }

    @Override
    public Dlg setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        hasItems = true;
        itemLabels = items;
        itemIcons = null;
        itemListener = listener;
        return this;
    }

    public Dlg setItems(CharSequence[] items, int[] icons, DialogInterface.OnClickListener listener) {
        hasItems = true;
        itemLabels = items;
        itemIcons = icons;
        itemListener = listener;
        return this;
    }

    private static final int[] DESTRUCTIVE = {R.string.delete, R.string.sec_install_anyway,
            R.string.pk_hist_clear, R.string.tl_delete_this};

    static boolean destructive(Context c, CharSequence label) {
        if (label == null) return false;
        Set<String> set = new HashSet<>();
        for (int id : DESTRUCTIVE) set.add(c.getString(id));
        return set.contains(label.toString());
    }

    private static final class ChoiceAdapter extends ArrayAdapter<CharSequence> {
        private final int[] icons;
        private final boolean anyIcon;

        ChoiceAdapter(Context c, CharSequence[] items, int[] icons) {
            super(c, 0, items);
            this.icons = icons;
            boolean any = false;
            if (icons != null) for (int r : icons) if (r != 0) any = true;
            anyIcon = any;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int res = icons != null && position < icons.length ? icons[position] : 0;
            return choiceRow(getContext(), getItem(position), res, anyIcon, convertView);
        }
    }

    /** One tappable row of a choice list (shared by the sheet and the dialog list). */
    private static View choiceRow(Context c, CharSequence label, int res, boolean anyIcon, View reuse) {
        LinearLayout row;
        if (reuse instanceof LinearLayout && "choice".equals(reuse.getTag())) {
            row = (LinearLayout) reuse;
        } else {
            row = new LinearLayout(c);
            row.setTag("choice");
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(c, 58));
            row.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 8));
            row.setBackgroundResource(R.drawable.bg_tab_btn);
            ImageView iv = new ImageView(c);
            iv.setPadding(Ui.dp(c, 9), Ui.dp(c, 9), Ui.dp(c, 9), Ui.dp(c, 9));
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
            ip.setMarginEnd(Ui.dp(c, 16));
            row.addView(iv, ip);
            TextView t = new TextView(c);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        ImageView iv = (ImageView) row.getChildAt(0);
        TextView t = (TextView) row.getChildAt(1);
        boolean red = destructive(c, label);
        t.setText(label);
        t.setTextColor(Ui.color(c, red ? R.color.bad : R.color.text_primary));
        if (res != 0) {
            iv.setImageResource(res);
            Ui.outlineIcon(c, iv, 12);
            if (red) iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.bad)));
            iv.setVisibility(View.VISIBLE);
        } else {
            iv.setImageDrawable(null);
            iv.setBackground(null);
            iv.setVisibility(anyIcon ? View.INVISIBLE : View.GONE);
        }
        return row;
    }

    /** ScrollView that never grows taller than a share of the screen, so the Cancel button stays reachable. */
    private static final class MaxHeightScroll extends android.widget.ScrollView {
        private final int max;

        MaxHeightScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int mode = View.MeasureSpec.getMode(h);
            int size = View.MeasureSpec.getSize(h);
            if (mode == View.MeasureSpec.UNSPECIFIED || size > max) {
                h = View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST);
            }
            super.onMeasure(w, h);
        }
    }

    public static AlertDialog result(Context c, boolean ok, CharSequence title, CharSequence message) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 18));

        int col = Ui.color(c, ok ? R.color.ok : R.color.bad);
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(halo(col));
        ImageView icon = new ImageView(c);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_cancel);
        icon.setImageTintList(ColorStateList.valueOf(col));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.addView(icon, new FrameLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42), Gravity.CENTER));
        box.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 72)));

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

    private static void style(AlertDialog d, boolean sheet, boolean centered) {
        Context c = d.getContext();
        Window win = d.getWindow();
        if (win != null) {
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (sheet) return;
        if (win != null && Blur.windowBlur(c)) win.setDimAmount(Blur.dim(c, DIM));
        focusSingleField(d);
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
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(c, R.color.text_primary));
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 4));
        }
        boolean headed = title != null || centered;
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            msg.setTextColor(Ui.color(c, headed ? R.color.text_secondary : R.color.text_primary));
            msg.setLineSpacing(0, 1.18f);
            msg.setGravity(Gravity.CENTER_HORIZONTAL);
            msg.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            msg.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, headed ? 4 : 22), Ui.dp(c, 20), Ui.dp(c, 20));
        }
        ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(new ColorDrawable(Ui.color(c, R.color.stroke_soft)));
            lv.setDividerHeight(hairline(c));
            lv.setClipToPadding(false);
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

        boolean row = shown.size() <= 2;
        for (Button b : shown) if (b.getText().length() > 16) row = false;
        List<Button> order = new ArrayList<>();
        if (row) {
            if (shown(neg)) order.add(neg);
            if (shown(neu)) order.add(neu);
            if (shown(pos)) order.add(pos);
        } else {
            if (shown(pos)) order.add(pos);
            if (shown(neu)) order.add(neu);
            if (shown(neg)) order.add(neg);
        }

        View panel = d.findViewById(androidx.appcompat.R.id.buttonPanel);
        if (panel != null) panel.setPadding(0, 0, 0, 0);
        ViewParent parent = shown.get(0).getParent();
        if (parent instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) parent;
            try {
                bar.getClass().getMethod("setAllowStacking", boolean.class).invoke(bar, false);
            } catch (Throwable ignored) {
            }
            bar.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            bar.setPadding(0, 0, 0, 0);
            bar.setGravity(Gravity.CENTER_HORIZONTAL);
            bar.setMinimumHeight(0);
            View spacer = bar.findViewById(androidx.appcompat.R.id.spacer);
            if (spacer != null) spacer.setVisibility(View.GONE);
            for (Button b : order) {
                bar.removeView(b);
                bar.addView(b);
            }
            // hairline above the buttons and between them, like an iPhone alert
            GradientDrawable top = new GradientDrawable();
            top.setColor(Ui.color(c, R.color.stroke_soft));
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{top});
            ld.setLayerGravity(0, Gravity.TOP);
            ld.setLayerHeight(0, hairline(c));
            bar.setBackground(ld);
            GradientDrawable div = new GradientDrawable();
            div.setColor(Ui.color(c, R.color.stroke_soft));
            div.setSize(hairline(c), hairline(c));
            bar.setDividerDrawable(div);
            bar.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        }
        boolean single = order.size() == 1;
        for (int i = 0; i < order.size(); i++) {
            Button b = order.get(i);
            boolean main = b == pos || single;
            boolean red = destructive(c, b.getText());
            b.setAllCaps(false);
            b.setTypeface(main ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            b.setMinHeight(Ui.dp(c, 46));
            b.setMinimumHeight(Ui.dp(c, 46));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
            b.setStateListAnimator(null);
            b.setBackgroundResource(R.drawable.ios_press);
            b.setTextColor(Ui.color(c, red ? R.color.bad : R.color.accent_text));
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
                m.width = row ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
                m.height = Ui.dp(c, 46);
                m.weight = row ? 1f : 0f;
                m.setMargins(0, 0, 0, 0);
                b.setLayoutParams(m);
            }
        }
    }

    private static void collectEdits(View v, List<EditText> out) {
        if (v instanceof EditText) {
            out.add((EditText) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectEdits(g.getChildAt(i), out);
        }
    }

    private static void focusSingleField(AlertDialog d) {
        View custom = d.findViewById(androidx.appcompat.R.id.custom);
        if (custom == null) return;
        List<EditText> edits = new ArrayList<>();
        collectEdits(custom, edits);
        if (edits.size() != 1) return;
        final EditText e = edits.get(0);
        e.requestFocus();
        String text = e.getText().toString();
        int dot = text.lastIndexOf('.');
        e.setSelection(0, dot > 0 ? dot : text.length());
        e.post(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    e.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() != View.GONE;
    }
}
