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
        if (sheet) {
            Window w = d.getWindow();
            if (w != null) {
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                w.setWindowAnimations(R.style.SheetAnim);
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setDimAmount(DIM);
                // size the window before it is shown, so it is not re-laid out while the slide-in plays
                int ww = Math.min(getContext().getResources().getDisplayMetrics().widthPixels,
                        Ui.dp(getContext(), SHEET_MAX_WIDTH_DP));
                w.setLayout(ww, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        final boolean center = centered;
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
        g.setColor(Ui.color(c, R.color.surface));
        g.setCornerRadius(Ui.dp(c, (int) radiusDp));
        g.setStroke(hairline(c), Ui.color(c, R.color.stroke_soft));
        return g;
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
        boolean red = destructiveAction();
        int res = iconRes != 0 ? iconRes : red ? R.drawable.ic_delete : R.drawable.ic_info;
        int tint = Ui.color(c, red ? R.color.bad : R.color.accent_text);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 4));

        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(halo(tint));
        ImageView icon = new ImageView(c);
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        badge.addView(icon, new FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.CENTER));
        box.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 60), Ui.dp(c, 60)));

        if (titleText != null) {
            TextView t = new TextView(c);
            t.setText(titleText);
            t.setTextColor(Ui.color(c, R.color.text_primary));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Ui.dp(c, 14);
            box.addView(t, tl);
        }
        return box;
    }

    private View buildSheet(final AlertDialog[] ref) {
        final Context c = getContext();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), Ui.dp(c, 10));

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(card(c, 28));

        LinearLayout grab = new LinearLayout(c);
        grab.setOrientation(LinearLayout.VERTICAL);
        grab.setGravity(Gravity.CENTER_HORIZONTAL);

        View handle = new View(c);
        GradientDrawable hg = new GradientDrawable();
        hg.setCornerRadius(Ui.dp(c, 3));
        hg.setColor(Ui.color(c, R.color.stroke));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 5));
        hp.topMargin = Ui.dp(c, 10);
        hp.bottomMargin = Ui.dp(c, 6);
        grab.addView(handle, hp);

        if (titleText != null || messageText != null) {
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.VERTICAL);
            head.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 6), Ui.dp(c, 24), Ui.dp(c, 12));
            if (titleText != null) {
                TextView h = new TextView(c);
                h.setText(titleText);
                h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextColor(Ui.color(c, R.color.text_primary));
                h.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                h.setGravity(Gravity.START);
                h.setSingleLine(true);
                h.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                head.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (messageText != null) {
                TextView m = new TextView(c);
                m.setText(messageText);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                m.setTextColor(Ui.color(c, R.color.text_secondary));
                m.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                m.setGravity(Gravity.START);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                mp.topMargin = Ui.dp(c, 3);
                head.addView(m, mp);
            }
            grab.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        panel.addView(grab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean anyIcon = false;
        if (itemIcons != null) for (int r : itemIcons) if (r != 0) anyIcon = true;
        // the rows are built once and scroll inside a capped ScrollView: no adapter, no repeated measuring
        final boolean icons = anyIcon;
        MaxHeightScroll scroll = new MaxHeightScroll(c, (int) (c.getResources().getDisplayMetrics().heightPixels * SHEET_MAX_HEIGHT));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable line = new GradientDrawable();
        line.setColor(Ui.color(c, R.color.stroke_soft));
        line.setSize(1, hairline(c));
        rows.setDividerDrawable(new InsetDrawable(line, Ui.dp(c, anyIcon ? 78 : 24), 0, Ui.dp(c, 24), 0));
        rows.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        for (int k = 0; k < itemLabels.length; k++) {
            final int pos = k;
            int res = itemIcons != null && k < itemIcons.length ? itemIcons[k] : 0;
            View row = choiceRow(c, itemLabels[k], res, icons, null);
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

        TextView cancel = new TextView(c);
        cancel.setText(R.string.cancel);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(Ui.color(c, R.color.text_primary));
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackgroundResource(R.drawable.dlg_btn_secondary);
        cancel.setClickable(true);
        Ui.press(c, cancel);
        cancel.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 50));
        kp.setMargins(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 16));
        panel.addView(cancel, kp);

        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        attachDrag(grab, root, ref);
        return root;
    }

    private static void dim(AlertDialog d, float amount) {
        if (d != null && d.getWindow() != null) d.getWindow().setDimAmount(amount);
    }

    private static void attachDrag(View grip, final View target, final AlertDialog[] ref) {
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
                    dim(ref[0], DIM * (1f - Math.min(1f, dy / Math.max(1f, target.getHeight()))));
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
                        dim(ref[0], DIM);
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
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(c, R.color.text_primary));
            title.setGravity(Gravity.START);
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            title.setPaddingRelative(Ui.dp(c, 26), Ui.dp(c, 26), Ui.dp(c, 26), Ui.dp(c, 8));
        }
        boolean headed = title != null || centered;
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            msg.setTextColor(Ui.color(c, headed ? R.color.text_secondary : R.color.text_primary));
            msg.setLineSpacing(0, 1.3f);
            msg.setGravity(centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            msg.setTextAlignment(centered ? View.TEXT_ALIGNMENT_CENTER : View.TEXT_ALIGNMENT_VIEW_START);
            msg.setPaddingRelative(Ui.dp(c, 26), Ui.dp(c, headed ? 8 : 26), Ui.dp(c, 26), Ui.dp(c, 24));
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

        boolean row = shown.size() <= 2;
        for (Button b : shown) if (b.getText().length() > 14) row = false;
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
            bar.setPaddingRelative(Ui.dp(c, 20), Ui.dp(c, 4), Ui.dp(c, 20), Ui.dp(c, 16));
            bar.setGravity(Gravity.CENTER_HORIZONTAL);
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
            boolean quiet = !row && !sheet && order.size() >= 3 && b == neg;
            b.setAllCaps(false);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setMinHeight(Ui.dp(c, 50));
            b.setMinimumHeight(Ui.dp(c, 50));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
            b.setStateListAnimator(null);
            Ui.press(c, b);
            if (quiet) {
                b.setBackgroundResource(R.drawable.dlg_btn_quiet);
                b.setTextColor(Ui.color(c, R.color.text_secondary));
                b.setTypeface(Typeface.DEFAULT);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            } else if (main) {
                b.setBackgroundResource(red ? R.drawable.dlg_btn_danger : R.drawable.dlg_btn_primary);
                b.setTextColor(Ui.color(c, red ? R.color.on_bad : R.color.on_accent));
            } else {
                b.setBackgroundResource(R.drawable.dlg_btn_secondary);
                b.setTextColor(Ui.color(c, red ? R.color.bad : R.color.text_primary));
            }
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
                m.width = row ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
                m.height = Ui.dp(c, quiet ? 42 : 50);
                m.weight = row ? 1f : 0f;
                if (row) m.setMargins(Ui.dp(c, 5), 0, Ui.dp(c, 5), 0);
                else m.setMargins(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
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
