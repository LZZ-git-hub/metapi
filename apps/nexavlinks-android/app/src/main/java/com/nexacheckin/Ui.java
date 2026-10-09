package com.nexacheckin;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int INK = Color.rgb(29, 51, 48), MUTED = Color.rgb(104, 124, 119);
    static final int GREEN = Color.rgb(24, 109, 90), SOFT = Color.rgb(234, 244, 240);
    static final int BACKGROUND = Color.rgb(245, 248, 246), DARK = Color.rgb(22, 67, 57);
    static int dp(Activity a, int value) { return (int)(value * a.getResources().getDisplayMetrics().density + .5f); }
    static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 18); l.setPadding(p, p, p, p); return l;
    }
    static TextView text(Activity a, String value, int size) {
        TextView t = new TextView(a); t.setText(value); t.setTextSize(size); t.setTextColor(INK);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4)); return t;
    }
    static TextView title(Activity a, String value, int size) {
        TextView t = text(a, value, size); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t;
    }
    static TextView muted(Activity a, String value, int size) {
        TextView t = text(a, value, size); t.setTextColor(MUTED); return t;
    }
    static GradientDrawable background(Activity a, int color, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(a, radius)); return bg;
    }
    static Button button(Activity a, String value, Runnable action) { return button(a, value, action, false); }
    static Button button(Activity a, String value, Runnable action, boolean primary) {
        Button b = new Button(a); b.setText(value); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? Color.WHITE : GREEN); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(a, 48)); b.setMinimumHeight(dp(a, 48));
        b.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
        b.setBackgroundTintList(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22339977),
            background(a, primary ? GREEN : SOFT, 14), null));
        b.setStateListAnimator(null); b.setOnClickListener(v -> action.run()); return b;
    }
    static TextView pill(Activity a, String value, boolean confirmed) {
        TextView t = title(a, value, 12); t.setTextColor(confirmed ? GREEN : MUTED);
        t.setBackground(background(a, confirmed ? SOFT : BACKGROUND, 20));
        t.setPadding(dp(a, 10), dp(a, 5), dp(a, 10), dp(a, 5)); t.setGravity(Gravity.CENTER); return t;
    }
    static void weighted(Activity a, LinearLayout row, View child, boolean last) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        if (!last) p.rightMargin = dp(a, 8); row.addView(child, p);
    }
    static void gap(Activity a, LinearLayout column, int height) {
        column.addView(new View(a), new LinearLayout.LayoutParams(1, dp(a, height)));
    }
    static void card(Activity a, View view) {
        GradientDrawable bg = background(a, Color.WHITE, 22); bg.setStroke(dp(a, 1), 0xFFE5EDE9);
        view.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(a, 14); view.setLayoutParams(p);
    }
    static void secure(Activity a, View root) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // Android 15+ edge-to-edge: keep controls clear of system bars and the keyboard.
        int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(l + insets.getSystemWindowInsetLeft(), t + insets.getSystemWindowInsetTop(),
                r + insets.getSystemWindowInsetRight(), b + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        a.setContentView(root); root.requestApplyInsets();
    }
}
