package com.nexacheckin;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** Visual-only subscription card; no network, arithmetic totals, or changes to cached amounts. */
final class SubscriptionCard {
    private static final int AMBER = 0xFFAC6A16, RED = 0xFFB44347;
    static View create(Activity activity, Subscription subscription, long now) {
        LinearLayout card = Ui.column(activity);
        int padding = Ui.dp(activity, 14); card.setPadding(padding, padding, padding, padding);
        GradientDrawable background = Ui.background(activity, Color.WHITE, 18);
        background.setStroke(Ui.dp(activity, 1), 0xFFDCE9E3); card.setBackground(background);
        String state = subscription.state(now);
        boolean active = "生效中".equals(state);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.title(activity, subscription.name, 16);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = Ui.pill(activity, state, active);
        if (!active) { badge.setTextColor(Ui.MUTED); badge.setBackground(Ui.background(activity, Ui.BACKGROUND, 20)); }
        header.addView(badge); card.addView(header);
        String expiry = subscription.expiresAt > 0
            ? "订阅到期 " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(Policy.ZONE).format(Instant.ofEpochMilli(subscription.expiresAt))
            : subscription.expiresAt == 0 ? "无固定到期日" : "订阅到期时间未取得";
        card.addView(Ui.muted(activity, expiry + " · 上海时间", 11));
        if (!active) card.addView(Ui.muted(activity, "以下为查询时额度，不代表当前可用", 11));
        if (subscription.quotas.isEmpty()) {
            Ui.gap(activity, card, 8); card.addView(Ui.muted(activity, subscription.quotaDescription(), 13));
        } else {
            for (int i = 0; i < subscription.quotas.size(); i++) {
                if (i > 0) {
                    View divider = new View(activity); divider.setBackgroundColor(0xFFEDF2EF);
                    LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 1));
                    line.topMargin = Ui.dp(activity, 10); line.bottomMargin = Ui.dp(activity, 10); card.addView(divider, line);
                } else Ui.gap(activity, card, 10);
                quota(activity, card, subscription.quotas.get(i), active, now);
            }
        }
        return card;
    }
    private static void quota(Activity activity, LinearLayout card, Subscription.Quota quota, boolean active, long now) {
        boolean currentWindow = active && !quota.reset.needsRefresh(now);
        int used = quota.usedBasisPoints();
        int accent = !currentWindow || used < 0 ? Ui.MUTED : used >= 9000 ? RED : used >= 7000 ? AMBER : Ui.GREEN;
        LinearLayout labels = new LinearLayout(activity); labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView period = Ui.title(activity, quota.label() + "周期额度", 12);
        period.setTextColor(Ui.MUTED); labels.addView(period, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ratio = Ui.text(activity, quota.usageLabel(), 11); ratio.setTextColor(accent); labels.addView(ratio); card.addView(labels);

        LinearLayout values = new LinearLayout(activity); values.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = Ui.title(activity, Totals.money(quota.remaining()), 23); left.setTextColor(accent);
        left.setMaxLines(1);
        left.setAutoSizeTextTypeUniformWithConfiguration(12, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        values.addView(left, new LinearLayout.LayoutParams(0, Ui.dp(activity, 36), 1));
        values.addView(Ui.muted(activity, currentWindow ? "剩余 USD" : "上次剩余 USD", 11)); card.addView(values);
        if (used >= 0) {
            ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(false); bar.setMax(10000); bar.setProgress(used);
            bar.setProgressTintList(ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFE8EFEB));
            bar.setContentDescription(quota.label() + "周期 " + quota.usageLabel());
            card.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 6)));
        } else card.addView(Ui.muted(activity, "数据未齐全，暂不绘制用量进度", 11));
        Ui.gap(activity, card, 4);
        LinearLayout detail = new LinearLayout(activity);
        TextView consumed = Ui.muted(activity, "已用 " + Totals.money(quota.used), 11);
        TextView limit = Ui.muted(activity, "上限 " + Totals.money(quota.limit), 11); limit.setGravity(Gravity.END);
        Ui.weighted(activity, detail, consumed, false); Ui.weighted(activity, detail, limit, true); card.addView(detail);
        TextView reset = Ui.muted(activity, quota.reset.description(active, now), 11);
        if (active && quota.reset.needsRefresh(now)) reset.setTextColor(AMBER);
        card.addView(reset);
    }
}
