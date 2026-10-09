package com.nexacheckin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newFixedThreadPool(RefreshPolicy.MAX_CONCURRENT);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Progress> progress = new HashMap<>();
    private final ArrayDeque<Account> queue = new ArrayDeque<>();
    private final Set<String> routerClaims = new HashSet<>();
    private List<Account> accounts = new ArrayList<>();
    private AccountStore accountStore;
    private Vault snapshots;
    private LinearLayout cards, overview;
    private ScrollView homeScroll;
    private Button remindersButton;
    private final Set<String> expandedAccounts = new HashSet<>();
    private boolean statisticsExpanded;
    private AlertDialog reminderDialog;
    private TextView reminderStatus;
    private Switch reminderSwitch;
    private boolean updatingReminderSwitch;
    private boolean ready;
    private String displayDay = Policy.day(), cacheWarning = "", visitingRevision = "";
    private int deletingSlot, visitingSlot;
    private final Set<Integer> queryingSlots = new HashSet<>();
    private final Runnable drainQueue = this::runNext;
    private LoginRequest pendingLogin;
    private static final int NOTIFICATION_PERMISSION = 10;
    // Local date/expiry display only. No timer ever starts a network request.
    private final Runnable midnight = new Runnable() {
        @Override public void run() {
            if (ready) { displayDay = Policy.day(); syncAccounts(); render(); }
            handler.postDelayed(this, 60000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            deletingSlot = state.getInt("deletingSlot", 0); visitingSlot = state.getInt("visitingSlot", 0);
            visitingRevision = state.getString("visitingRevision", "");
            statisticsExpanded = state.getBoolean("statisticsExpanded", false);
            ArrayList<String> expanded = state.getStringArrayList("expandedAccounts");
            if (expanded != null) expandedAccounts.addAll(expanded);
            try { if (state.containsKey("pendingLogin")) pendingLogin = LoginRequest.from(state.getString("pendingLogin")); }
            catch (Exception ignored) { pendingLogin = null; }
        }
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL);
        TextView wordmark = Ui.title(this, "NEXA", 11); wordmark.setTextColor(Ui.GREEN); wordmark.setLetterSpacing(.12f);
        brand.addView(wordmark); brand.addView(Ui.title(this, "今日签到", 28));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        remindersButton = Ui.button(this, "提醒设置", this::reminderSettings);
        header.addView(remindersButton);
        root.addView(header); Ui.gap(this, root, 12);
        ScrollView scroll = new ScrollView(this); homeScroll = scroll; scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        overview = new LinearLayout(this); overview.setOrientation(LinearLayout.VERTICAL); content.addView(overview);
        LinearLayout actions = new LinearLayout(this);
        Ui.weighted(this, actions, Ui.button(this, "刷新数据", this::refreshAll, true), false);
        Ui.weighted(this, actions, Ui.button(this, "+ 添加账号", () -> edit(null)), true);
        content.addView(actions); Ui.gap(this, content, 16);
        cards = new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL); content.addView(cards);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); Ui.secure(this, root);
        snapshots = new Vault(this, "snapshots.enc");
        try { accountStore = AccountStore.get(this); accounts = accountStore.list(); ready = true; }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("无法解密账号数据")
                .setMessage("为避免覆盖已有数据，已停止加载。请勿清除应用数据；检查手机系统或联系开发者。")
                .setCancelable(false).setPositiveButton("退出", (d, w) -> finish()).show();
        }
        if (ready) {
            try { progress.putAll(SnapshotData.decode(accounts, snapshots.loadValues())); }
            catch (Exception e) { cacheWarning = "未能读取上次统计，账号未受影响；请手动刷新。"; }
            render();
            if (ExpiryNotifications.enabled(this) && !ExpiryNotifications.permissionAsked(this)) requestReminderPermission();
        }
    }
    @Override protected void onResume() {
        super.onResume(); displayDay = Policy.day();
        if (ready) { syncAccounts(); accountStore.rescheduleReminders(); render(); }
        handler.removeCallbacks(midnight); handler.post(midnight);
    }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(midnight); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("deletingSlot", deletingSlot); state.putInt("visitingSlot", visitingSlot);
        state.putString("visitingRevision", visitingRevision);
        state.putBoolean("statisticsExpanded", statisticsExpanded);
        state.putStringArrayList("expandedAccounts", new ArrayList<>(expandedAccounts));
        try { if (pendingLogin != null) state.putString("pendingLogin", pendingLogin.json().toString()); }
        catch (Exception ignored) { /* No credentials are stored in saved state. */ }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (reminderDialog != null) reminderDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        // Do not interrupt a token rotation: its new pair must be persisted even after recreation.
        worker.shutdown(); super.onDestroy();
    }
    private void syncAccounts() {
        List<Account> latest = accountStore.list();
        for (Account old : accounts) {
            Account found = null;
            for (Account a : latest) if (a.slot == old.slot) found = a;
            if (found == null || !found.revision.equals(old.revision)) {
                // Our in-flight result will replace this snapshot once all GETs finish.
                if (!queryingSlots.contains(old.slot)) progress.remove(old.slot);
            }
        }
        accounts = latest;
    }
    private Account find(int slot) { for (Account a : accounts) if (a.slot == slot) return a; return null; }
    private void message(String value) { if (!isDestroyed()) Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private String time(long value) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(Policy.ZONE).format(Instant.ofEpochMilli(value));
    }
    private LinearLayout metric(String label, String value, boolean dark) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView caption = Ui.muted(this, label, 12), amount = Ui.title(this, value, 22);
        if (dark) { caption.setTextColor(0xFFB9D6CB); amount.setTextColor(Color.WHITE); }
        box.addView(caption); box.addView(amount); return box;
    }
    private String coverage(String label, java.math.BigDecimal value, int count) {
        return label + " " + count + "/" + accounts.size()
            + (count < accounts.size() ? " · 其余未取得" : " · 已齐全");
    }
    private void requestReminderPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!ExpiryNotifications.permissionAsked(this)) {
                if (!ExpiryNotifications.markPermissionAsked(this)) { message("未能保存通知设置，请稍后重试"); return; }
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            } else openNotificationSettings();
        } else if (ready) { accountStore.rescheduleReminders(); render(); }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NOTIFICATION_PERMISSION || !ready) return;
        accountStore.rescheduleReminders(); render();
        if (!ExpiryNotifications.allowed(this)) message("通知未开启；首页仍会显示到期提醒，不影响手动刷新");
    }
    private void openNotificationSettings() {
        try {
            boolean appNotifications = getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
            Intent intent = new Intent(appNotifications ? android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                : android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (appNotifications) intent.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ExpiryNotifications.CHANNEL);
            startActivity(intent);
        } catch (Exception e) { message("无法打开系统通知设置，请在手机设置中为 Nexa 开启通知"); }
    }
    private void reminderSettings() {
        if (!ready || reminderDialog != null) return;
        LinearLayout sheet = Ui.column(this); sheet.setBackground(Ui.background(this, Ui.BACKGROUND, 26));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.title(this, "提醒设置", 23), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(Ui.button(this, "完成", () -> { if (reminderDialog != null) reminderDialog.dismiss(); }));
        sheet.addView(heading); Ui.gap(this, sheet, 14);
        LinearLayout card = Ui.column(this); Ui.card(this, card);
        LinearLayout toggle = new LinearLayout(this); toggle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout caption = new LinearLayout(this); caption.setOrientation(LinearLayout.VERTICAL);
        caption.addView(Ui.title(this, "凭证到期提醒", 17));
        caption.addView(Ui.muted(this, "提前约 1 小时通知", 13));
        toggle.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        reminderSwitch = new Switch(this); reminderSwitch.setShowText(false);
        int[][] switchStates = {new int[]{android.R.attr.state_checked}, new int[]{}};
        reminderSwitch.setThumbTintList(new android.content.res.ColorStateList(switchStates, new int[]{Ui.GREEN, 0xFF84958E}));
        reminderSwitch.setTrackTintList(new android.content.res.ColorStateList(switchStates, new int[]{0xFFBCDCD0, 0xFFDEE5E1}));
        reminderSwitch.setContentDescription("凭证到期提醒开关"); reminderSwitch.setMinHeight(Ui.dp(this, 48));
        reminderSwitch.setChecked(ExpiryNotifications.enabled(this)); toggle.addView(reminderSwitch);
        card.addView(toggle); Ui.gap(this, card, 8);
        reminderStatus = Ui.muted(this, "", 12); card.addView(reminderStatus); sheet.addView(card);
        LinearLayout timing = Ui.column(this); Ui.card(this, timing);
        timing.addView(Ui.title(this, "提醒时间", 14));
        timing.addView(Ui.title(this, "到期前 1 小时", 21));
        timing.addView(Ui.muted(this, "仅提醒，不自动续期", 12)); sheet.addView(timing);
        sheet.addView(Ui.button(this, "系统通知权限  ›", this::openNotificationSettings));
        Ui.gap(this, sheet, 8);
        sheet.addView(Ui.button(this, "使用说明  ›", this::showHelp));
        ScrollView scroll = new ScrollView(this); scroll.addView(sheet);
        reminderDialog = new AlertDialog.Builder(this).setView(scroll).create();
        reminderDialog.setOnDismissListener(d -> { reminderDialog = null; reminderSwitch = null; reminderStatus = null; });
        reminderSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (updatingReminderSwitch) return;
            if (!ExpiryNotifications.setEnabled(this, enabled)) { message("提醒设置保存失败，请重试"); updateReminderPanel(); return; }
            accountStore.rescheduleReminders(); render();
            if (enabled) {
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestReminderPermission();
                else if (!ExpiryNotifications.allowed(this)) openNotificationSettings();
            }
        });
        reminderDialog.show();
        android.view.Window window = reminderDialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            int height = (int)(getResources().getDisplayMetrics().heightPixels * .85f);
            window.setLayout(-1, Math.min(height, Ui.dp(this, 560)));
        }
        updateReminderPanel();
    }
    private void updateReminderPanel() {
        if (reminderStatus == null || reminderSwitch == null) return;
        updatingReminderSwitch = true; reminderSwitch.setChecked(ExpiryNotifications.enabled(this)); updatingReminderSwitch = false;
        reminderStatus.setText(ExpiryNotifications.status(this));
        reminderStatus.setTextColor(ExpiryNotifications.needsAttention(this) ? 0xFF9B5829 : Ui.MUTED);
    }
    private void showHelp() {
        showText("使用说明", "刷新：进入首页不联网；手动刷新，或新增/重新登录成功、签到页返回时更新对应账号。\n\n"
            + "Congee（粥）：仅查询余额，不执行签到，也不计入签到总数。新增或重新登录后更新自己的余额；访问凭证失效时重新登录。\n\n"
            + "Router：添加时选 AgentRouter / AnyRouter，沿用 Linux DO 登录。点击一键签到才发起 Router 签到尝试；不在启动或后台运行。原站登录或查看个人信息本身也可能自动发放签到奖励。访问受限时请在原站人工完成验证，不保证免验证。\n\n"
            + "签到：只按原站查询结果显示。是否满足昨日/今日消费条件直接采用原站 eligible 判定，不用本地消费或缓存推测。可签到仍需手动完成原站验证。跨天或返回原站后重新核实。\n\n"
            + "订阅：到期时间与周期重置分别展示。预计重置根据原站周期起点计算；已过边界需刷新，不能把历史剩余额度当成重置后的值。\n\n"
            + "统计：使用本机快照，不是实时金额；缺失账号不按零计算。“已知”代表部分合计。订阅周期额度互相重叠，不与余额相加。\n\n"
            + "到期提醒：仅根据访问凭证时间判断，不代表刷新凭证寿命。省电限制可能延迟通知，强行停止后需要重新打开应用。\n\n"
            + "隐私：账号凭证与统计加密保存在手机；请勿分享 Token 或清除应用数据。");
    }
    private void showText(String title, String text) {
        ScrollView scroll = new ScrollView(this); LinearLayout content = Ui.column(this);
        content.addView(Ui.text(this, text, 14)); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("知道了", null).create();
        dialog.setOnShowListener(d -> { if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); });
        dialog.show();
    }
    private TextView expiryNotice(String value, boolean expired) {
        TextView notice = Ui.text(this, value, 13);
        notice.setTextColor(expired ? 0xFFA02D35 : 0xFF855600);
        notice.setBackground(Ui.background(this, expired ? 0xFFFDECEE : 0xFFFFF4D6, 12));
        int padding = Ui.dp(this, 10); notice.setPadding(padding, padding, padding, padding);
        return notice;
    }
    private void render() {
        if (cards == null) return;
        final int scrollY = homeScroll == null ? 0 : homeScroll.getScrollY();
        long now = System.currentTimeMillis(); String day = Policy.day();
        Totals t = Totals.of(accounts, progress, day); overview.removeAllViews();
        updateReminderPanel();
        if (remindersButton != null) remindersButton.setText(ExpiryNotifications.needsAttention(this) ? "提醒待设置" : "提醒设置");

        LinearLayout hero = Ui.column(this); Ui.card(this, hero); hero.setBackground(Ui.background(this, Ui.DARK, 24));
        TextView caption = Ui.muted(this, day + " · 上海时间", 12); caption.setTextColor(0xFFB9D6CB); hero.addView(caption);
        String summary = t.checkinAccounts == 0 && !accounts.isEmpty()
            ? "余额查看" : t.checked + " / " + t.checkinAccounts + "  已签到";
        TextView completed = Ui.title(this, summary, 30);
        completed.setTextColor(Color.WHITE); hero.addView(completed);
        int unchecked = t.checkinAccounts - t.checked - t.unknown;
        String remainingText = t.checkinAccounts == 0 && !accounts.isEmpty()
            ? accounts.size() + " 个余额账号" : "未签到 " + unchecked + "    待核实 " + t.unknown;
        TextView remaining = Ui.text(this, remainingText, 15);
        remaining.setTextColor(0xFFD9EAE3); hero.addView(remaining);
        if (!queryingSlots.isEmpty() || !queue.isEmpty()) {
            TextView loading = Ui.muted(this, "更新中 " + queryingSlots.size() + " · 排队 " + queue.size(), 12);
            loading.setTextColor(0xFFB9D6CB); hero.addView(loading);
        }
        overview.addView(hero);
        boolean hasRouters = false; for(Account a:accounts)if(a.site.router())hasRouters=true;
        if(hasRouters){overview.addView(Ui.button(this,"一键签到 · AgentRouter / AnyRouter",this::oneTap,true));Ui.gap(this,overview,8);}
        overview.addView(Ui.button(this, statisticsExpanded ? "收起分站统计  ▴" : "分站统计  ▾", () -> {
                statisticsExpanded = !statisticsExpanded; render();
            }));
        if (statisticsExpanded) renderStatistics(t);
        if (!cacheWarning.isEmpty()) overview.addView(expiryNotice(cacheWarning, false));
        Ui.gap(this, overview, 12); cards.removeAllViews();

        for (Account a : accounts) {
            Progress p = progress.get(a.slot);
            boolean balanceOnly = a.site.balanceOnly();
            int state = balanceOnly ? CheckinPresentation.UNKNOWN : CheckinPresentation.state(p, day);
            boolean checked = state == CheckinPresentation.CHECKED;
            LinearLayout card = Ui.column(this); Ui.card(this, card);
            LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.title(this, a.label, 19); top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView badge = Ui.title(this, balanceOnly ? "仅余额" : CheckinPresentation.label(state), 17);
            badge.setTextColor(checked ? Ui.GREEN : state == CheckinPresentation.UNCHECKED ? 0xFF9B5829 : Ui.MUTED);
            badge.setBackground(Ui.background(this, checked ? Ui.SOFT : state == CheckinPresentation.UNCHECKED ? 0xFFFFF1DB : Ui.BACKGROUND, 12));
            badge.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
            top.addView(badge); card.addView(top);
            card.addView(Ui.muted(this,a.site.label,12));
            accountBalance(card, a, p, day);
            String qualification = a.site == Site.NEXA ? CheckinPresentation.eligibilityLabel(p, day) : "";
            if (!qualification.isEmpty()) {
                Boolean eligible = CheckinPresentation.eligibility(p, day);
                TextView eligibility = Ui.title(this, qualification, 12);
                eligibility.setTextColor(eligible == null ? Ui.MUTED : eligible ? Ui.GREEN : 0xFF9B5829);
                card.addView(eligibility);
            }
            Ui.gap(this, card, 10);
            LinearLayout buttons = new LinearLayout(this);
            String openLabel = a.site.router() || balanceOnly ? "查看原站" : CheckinPresentation.action(state);
            String refreshLabel = "刷新";
            if (p != null && p.loading) refreshLabel = "更新中…";
            else if (balanceOnly) refreshLabel = "刷新余额";
            else if (a.site.router()) refreshLabel = "签到";
            Ui.weighted(this, buttons, Ui.button(this, openLabel, () -> open(a, false), !checked && !a.site.router() && !balanceOnly), false);
            Ui.weighted(this, buttons, Ui.button(this, refreshLabel, () -> refresh(a), balanceOnly || a.site.router() && !checked), false);
            Ui.weighted(this, buttons, Ui.button(this, "管理", () -> manage(a)), true);
            card.addView(buttons);
            int expiryLevel = ExpiryPolicy.level(a.expiresAt, now);
            if (expiryLevel >= ExpiryPolicy.SOON) {
                Ui.gap(this, card, 8);
                card.addView(expiryNotice(ExpiryPolicy.message(a, now), expiryLevel == ExpiryPolicy.EXPIRED));
            }
            if (p != null && !p.warning.isEmpty()) {
                Ui.gap(this, card, 8);
                String warning = p.warning;
                Button error = Ui.button(this, CheckinPresentation.shortWarning(p), () -> showText(a.label + " · 查询异常", warning));
                error.setTextColor(0xFF9B5829); card.addView(error);
            }
            Ui.gap(this, card, 8);
            String detailKey = CheckinPresentation.detailKey(a);
            boolean expanded = expandedAccounts.contains(detailKey);
            String detailsLabel = expanded ? "收起用量与订阅  ▴" : "用量与订阅  ▾";
            if (a.site.router() || balanceOnly) detailsLabel = expanded ? "收起详情 ▴" : "账号详情 ▾";
            card.addView(Ui.button(this, detailsLabel, () -> {
                if (!expandedAccounts.add(detailKey)) expandedAccounts.remove(detailKey);
                render();
            }));
            if (expanded) accountDetails(card, a, p, now);
            cards.addView(card);
        }
        if (accounts.isEmpty()) {
            LinearLayout empty = Ui.column(this); Ui.card(this, empty);
            empty.addView(Ui.title(this, "添加第一个账号", 20));
            empty.addView(Ui.muted(this, "登录后，在这里查看今日签到状态。", 14)); cards.addView(empty);
        }
        if (homeScroll != null) homeScroll.post(() -> { if (!isDestroyed()) homeScroll.scrollTo(0, scrollY); });
    }
    private void renderStatistics(Totals ignored) {
        for(Site site:Site.values()) {
            List<Account> selected=new ArrayList<>(); for(Account a:accounts)if(a.site==site)selected.add(a);
            if(selected.isEmpty())continue;
            Totals t=Totals.of(selected,progress,Policy.day()); int count=selected.size();
            LinearLayout stats=Ui.column(this);Ui.card(this,stats);
            stats.addView(Ui.title(this,site.label+(site.router()?" · USD":" · 原站单位"),16));
            stats.addView(Ui.muted(this,"查询快照 · 不跨站合并额度",12));
            stats.addView(metric(Totals.label("账号余额合计",t.balances,count),Totals.amount(t.balance,t.balances,count),false));
            if(!site.balanceOnly())stats.addView(metric(Totals.label("累计使用合计",t.usages,count),Totals.amount(t.used,t.usages,count),false));
            if(site==Site.NEXA)stats.addView(metric(Totals.label("今日消费合计",t.todays,count),Totals.amount(t.today,t.todays,count),false));
            stats.addView(Ui.muted(this,"余额已知 "+t.balances+"/"+count
                +(site.balanceOnly()?"":" · 累计已知 "+t.usages+"/"+count),11));
            overview.addView(stats);
        }
    }
    private void accountBalance(LinearLayout card, Account account, Progress p, String day) {
        Ui.gap(this, card, 8);
        boolean known = p != null && p.balance != null;
        LinearLayout balance = Ui.column(this);
        int padding = Ui.dp(this, 12); balance.setPadding(padding, padding, padding, padding);
        balance.setBackground(Ui.background(this, Ui.BACKGROUND, 14));

        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.title(this, "账号余额", 13), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(Ui.muted(this, account.site.router() ? "USD" : "原站单位", 11));
        balance.addView(heading);

        // 余额使用已核实快照的完整数值；缺失不当作零，也不把订阅额度加进来。
        TextView amount = Ui.title(this, Totals.money(known ? p.balance : null), 26);
        int amountColor = known ? Ui.GREEN : Ui.MUTED;
        if (known && p.balance.signum() < 0) amountColor = 0xFF9B5829;
        amount.setTextColor(amountColor);
        balance.addView(amount);

        String timestamp = p == null || p.syncedAt == 0 ? "尚未查询" : "上次查询 " + time(p.syncedAt);
        if (p != null && !p.dailyValid(day)) timestamp += " · 已跨天";
        if (known) timestamp += " · 非实时";
        else if (p != null) timestamp += " · 余额未取得";
        if (p != null && p.loading) timestamp += " · 更新中";
        balance.addView(Ui.muted(this, timestamp, 11));
        card.addView(balance);
    }
    private void accountDetails(LinearLayout card, Account a, Progress p, long now) {
        Ui.gap(this, card, 10);
        if (a.site.balanceOnly()) {
            card.addView(Ui.muted(this, "ID " + a.userId + (a.expiresAt > 0 ? " · 凭证到期 " + time(a.expiresAt) : ""), 11));
            card.addView(Ui.muted(this, "仅查询余额 · 登录失效时请重新登录", 12));
            return;
        }
        card.addView(metric("累计使用", Totals.money(p == null ? null : p.totalCost), false));
        if (a.site.router()) {
            card.addView(Ui.muted(this,"USD · 按原站 quota_per_unit 换算\nID " + a.userId + " · 会话失效后请重新登录",12));
            return;
        }
        card.addView(Ui.muted(this, "今日消费 " + Totals.money(p == null || !p.dailyValid(Policy.day()) ? null : p.cost), 13));
        subscriptionDetails(card, p, now);
        card.addView(Ui.muted(this, "ID " + a.userId + (a.expiresAt > 0 ? " · 凭证到期 " + time(a.expiresAt) : " · 到期时间未知"), 11));
        if (a.refreshToken.isEmpty()) card.addView(Ui.muted(this, "尚无续期凭证 · 请重新登录一次", 12));
    }
    private void subscriptionDetails(LinearLayout card, Progress p, long now) {
        Ui.gap(this, card, 8);
        LinearLayout title = new LinearLayout(this); title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(Ui.title(this, "我的订阅", 15), new LinearLayout.LayoutParams(0, -2, 1));
        if (p != null && p.subscriptions != null) title.addView(Ui.pill(this, p.subscriptions.size() + " 个套餐", true));
        card.addView(title);
        if (p == null || p.subscriptions == null) {
            card.addView(Ui.muted(this, "尚未取得订阅数据 · 请手动刷新", 12)); return;
        }
        if (p.subscriptions.isEmpty()) {
            card.addView(Ui.muted(this, "上次查询：无订阅", 12)); return;
        }
        Ui.gap(this, card, 8);
        for (Subscription s : p.subscriptions) {
            card.addView(SubscriptionCard.create(this, s, now)); Ui.gap(this, card, 10);
        }
        card.addView(Ui.muted(this, "USD · 查询快照 · 各周期限制重叠，不与账号余额相加", 11));
    }
    private void persistSnapshots() {
        try { snapshots.saveValues(SnapshotData.encode(accounts, progress)); cacheWarning = ""; }
        catch (Exception e) { cacheWarning = "统计缓存保存失败；本次可查看，重启后可能丢失。账号记录未更改。"; }
    }
    private String unavailable(Account a) {
        Progress p = progress.get(a.slot);
        if (accountStore.busy(a.slot)) return "此账号正在续期或查询，请稍候";
        if (visitingSlot == a.slot || deletingSlot == a.slot || pendingLogin != null && pendingLogin.slot == a.slot)
            return "此账号正在原站操作，请返回后刷新";
        return RefreshPolicy.isBusy(p) ? "此账号正在排队或查询，请稍候" : "";
    }
    private void enqueue(Account a) { enqueue(a, false); }
    private void enqueue(Account a, boolean priority) {
        Progress old = progress.get(a.slot);
        Progress loading = old == null ? new Progress(Policy.day()) : old.copy();
        loading.loading = true; loading.requestedAt = System.currentTimeMillis();
        progress.put(a.slot, loading);
        if (priority) queue.addFirst(a); else queue.addLast(a);
    }
    private void refreshAfterAction(Account a) {
        if (!ready || a == null || a.site.router()) return;
        if (!unavailable(a).isEmpty()) return; // Coalesce an already queued/running request.
        enqueue(a, true); persistSnapshots(); runNext(); render();
    }
    private void refreshAll() {
        if (!ready) return;
        syncAccounts();
        int added = 0;
        for (Account a : accounts) if (a.site.tokenSession() && unavailable(a).isEmpty()) { enqueue(a); added++; }
        if (added == 0) { message(accounts.isEmpty() ? "请先添加账号" : "没有可更新的 Nexa / Congee 账号；Router 请点击一键签到"); return; }
        persistSnapshots(); runNext(); render();
    }
    private void oneTap() {
        if (!ready) return;
        syncAccounts(); int added = 0;
        for (Account a : accounts) if (a.site.router() && unavailable(a).isEmpty()
                && CheckinPresentation.state(progress.get(a.slot),Policy.day()) != CheckinPresentation.CHECKED) {
            routerClaims.add(a.revision); enqueue(a); added++;
        }
        if (added == 0) { message("没有可处理的 Router 账号：请先添加，或已签到/正在处理"); return; }
        persistSnapshots(); runNext(); render();
    }
    private void claimRouter(Account a) {
        if (!ready) return;
        syncAccounts(); Account current = find(a.slot);
        if (current == null || !current.site.router()) return;
        String reason = unavailable(current); if (!reason.isEmpty()) { message(reason); return; }
        if (CheckinPresentation.state(progress.get(current.slot),Policy.day()) == CheckinPresentation.CHECKED) {
            message("今日已确认签到，未重复请求"); return;
        }
        routerClaims.add(current.revision); enqueue(current,true); persistSnapshots(); runNext(); render();
    }
    private void refresh(Account a) {
        if (a.site.router()) { claimRouter(a); return; }
        if (!ready) return;
        syncAccounts();
        Account latest = find(a.slot);
        if (latest == null) return;
        String reason = unavailable(latest); if (!reason.isEmpty()) { message(reason); return; }
        enqueue(latest); persistSnapshots(); runNext(); render();
    }
    private void runNext() {
        handler.removeCallbacks(drainQueue);
        if (isDestroyed()) return;
        while (RefreshPolicy.hasCapacity(queryingSlots.size()) && !queue.isEmpty()) {
            Account a = queue.peekFirst(); Progress loading = progress.get(a.slot); Account current = find(a.slot);
            if (current == null || !current.revision.equals(a.revision) || loading == null || !loading.loading) {
                queue.removeFirst(); continue;
            }
            try {
                if (!accountStore.tryBegin(a)) {
                    // Only drain already-requested work; a previous Activity may still own the lanes.
                    handler.postDelayed(drainQueue, 250); return;
                }
            } catch (Exception e) {
                queue.removeFirst();
                Progress failure = new Progress(Policy.day()); failure.warning = e.getMessage();
                progress.put(a.slot, failure); persistSnapshots(); render(); continue;
            }
            queue.removeFirst(); queryingSlots.add(a.slot);
            loading.requestedAt = System.currentTimeMillis();
            executeQuery(a, loading);
        }
    }
    private void executeQuery(Account a, Progress loading) {
        final boolean claim = routerClaims.remove(a.revision);
        worker.execute(() -> {
            Progress result;
            Account effective = a;
            try {
                if (a.site.router()) {
                    if (!claim) throw new Exception("请点击一键签到处理 Router 账号");
                    result = RouterApi.read(a, true);
                } else if (a.site.balanceOnly()) {
                    // 此站点只读取余额，不调用续期、签到、用量或订阅接口。
                    result = Progress.readBalance(a);
                } else {
                    effective = Renewal.renew(a, Api::refresh, accountStore::rotate, System::currentTimeMillis);
                    result = Progress.read(effective);
                }
            } catch (Exception e) {
                result = new Progress(Policy.day());
                result.warning = e.getMessage() == null ? "续期失败，请重新登录" : e.getMessage();
            } finally { accountStore.finish(a.slot); }
            final Progress completed = result; completed.requestedAt = loading.requestedAt;
            final String resultRevision = effective.revision;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                queryingSlots.remove(a.slot);
                accounts = accountStore.list();
                Account latest = find(a.slot);
                if (latest != null && progress.get(a.slot) == loading && latest.revision.equals(resultRevision)) {
                    if (a.site.supportsCheckin() && !completed.day.equals(Policy.day())) {
                        completed.cost = null; completed.checked = null; completed.eligible = null;
                        completed.warning = "已跨天，今日数据待手动刷新";
                    }
                    progress.put(a.slot, completed); persistSnapshots();
                }
                // No artificial delay: a completed account immediately releases its lane.
                runNext(); render();
            });
        });
    }
    private void invalidateCheckin(int slot) {
        queue.removeIf(a -> a.slot == slot);
        Progress old = progress.get(slot);
        Progress p = old == null ? new Progress(Policy.day()) : old.copy();
        p.checked = null; p.eligible = null; p.checkinPending = true; progress.put(slot, p);
        persistSnapshots(); render();
    }
    private void manage(Account a) {
        new AlertDialog.Builder(this).setTitle(a.label).setItems(new String[]{"修改备注", "重新登录", "删除账号及浏览器会话"}, (d, which) -> {
            if (which == 0) edit(a);
            else if (which == 1) startLogin(a.slot, a.label, a);
            else new AlertDialog.Builder(this).setTitle("删除 " + a.label + "？")
                .setMessage("会清除本机该账号的登录会话，不会删除原站账号。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (x, y) -> open(a, true)).show();
        }).show();
    }
    private void open(Account a, boolean clear) {
        if (!ready) return;
        syncAccounts();
        Account current = find(a.slot);
        if (current == null) return;
        if (accountStore.busy(a.slot)) { message("正在续期或查询，请完成后再打开原站"); return; }
        if (!clear && current.expiresAt > 0 && current.expiresAt <= System.currentTimeMillis()) {
            message(current.site.balanceOnly() ? "登录凭证已到期，请在管理中重新登录" : "访问凭证已到期，请先手动刷新续期，再打开原站"); return;
        }
        try {
            Intent intent = new Intent(this, Class.forName("com.nexacheckin.Slot" + a.slot + "Activity"));
            intent.putExtra("account", current.json().toString()); intent.putExtra("clear", clear);
            if (clear) deletingSlot = a.slot;
            else { visitingSlot = a.slot; visitingRevision = current.revision; }
            startActivityForResult(intent, clear ? 2 : 1);
            // Only the returning account will be queried; pre-visit work cannot confirm the outcome.
            invalidateCheckin(a.slot);
        } catch (Exception e) {
            if (clear) deletingSlot = 0; else { visitingSlot = 0; visitingRevision = ""; }
            message("无法打开签到窗口");
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (ready) syncAccounts();
        if (request == 3) {
            LoginRequest pending = pendingLogin; pendingLogin = null;
            try {
                if (result == RESULT_OK && ready && pending != null && data != null) {
                    Account candidate = Account.from(new JSONObject(data.getStringExtra("account")));
                    if (save(pending.accept(accounts, data.getStringExtra("nonce"), candidate))) {
                        progress.remove(candidate.slot); queue.removeIf(a -> a.slot == candidate.slot);
                        persistSnapshots(); message("账号已验证并加密保存");
                        if (candidate.site.router()) applyRouterSnapshot(candidate, data);
                        refreshAfterAction(find(candidate.slot));
                    }
                } else if (result == RESULT_OK) message("登录请求已失效，请重新添加");
            } catch (Exception e) { message(e.getMessage() == null ? "登录结果无效，请重试" : e.getMessage()); }
            finally { if (data != null) data.removeExtra("account"); }
        }
        if (request == 2) {
            if (result == RESULT_OK && deletingSlot > 0) {
                List<Account> next = new ArrayList<>(accounts); next.removeIf(a -> a.slot == deletingSlot);
                if (save(next)) { progress.remove(deletingSlot); persistSnapshots(); }
            } else message("会话未确认清除，已保留账号");
            deletingSlot = 0;
        }
        if (request == 1) {
            int slot = visitingSlot; String revision = visitingRevision;
            visitingSlot = 0; visitingRevision = "";
            Account returned = find(slot);
            // The browser return code is not proof of check-in success. Query the same revision once.
            if (RefreshPolicy.matchesVisit(returned, slot, revision)) {
                if (returned.site.router()) {
                    try {
                        if (result == RESULT_OK && data != null && data.hasExtra("routerAccount")) {
                            Account candidate = Account.from(new JSONObject(data.getStringExtra("routerAccount")));
                            if (save(Renewal.replace(accounts, returned, candidate))) applyRouterSnapshot(candidate,data);
                        }
                    } catch (Exception e) { message("原站会话结果未确认，请重新登录"); }
                    finally { if(data!=null){data.removeExtra("routerAccount");data.removeExtra("routerProgress");} }
                } else {
                    refreshAfterAction(returned);
                    message(returned.site.balanceOnly() ? "正在更新此账号余额" : "正在更新此账号卡片并核实签到结果");
                }
            }
        }
        render();
    }
    private void applyRouterSnapshot(Account a, Intent data) {
        try {
            if (a.site.router() && data != null && data.hasExtra("routerProgress")) {
                progress.put(a.slot, Progress.from(new JSONObject(data.getStringExtra("routerProgress"))));
                persistSnapshots();
            }
        } catch (Exception e) { message("账号已保存，但原站状态待核实"); }
        finally { if(data!=null)data.removeExtra("routerProgress"); }
    }
    private boolean save(List<Account> next) {
        try { accountStore.save(accounts, next); accounts = accountStore.list(); return true; }
        catch (Exception e) { message("保存失败或账号正在查询，未更改账号；请完成后重试"); return false; }
    }
    private void startLogin(int slot, String label, Account original) {
        startLogin(slot,label,original,original == null ? Site.NEXA : original.site);
    }
    private void startLogin(int slot, String label, Account original, Site site) {
        if (!ready || pendingLogin != null) return;
        if (accountStore.busy(slot)) { message("正在续期或查询，请完成后再登录"); return; }
        syncAccounts();
        if (original != null) original = find(slot);
        try {
            LoginRequest request = LoginRequest.create(slot, label, original, site);
            Intent intent = new Intent(this, Class.forName("com.nexacheckin.Slot" + slot + "Activity"));
            intent.putExtra("login", request.json().toString()); pendingLogin = request;
            startActivityForResult(intent, 3);
            if (original != null) invalidateCheckin(slot);
        } catch (Exception e) { pendingLogin = null; message("无法打开登录窗口，请重试"); }
    }
    private void edit(Account original) {
        if (!ready || pendingLogin != null) return;
        int free = 1; while (free <= 9 && find(free) != null) free++;
        if (original == null && free > 9) { message("最多支持 9 个账号"); return; }
        final int slot = original == null ? free : original.slot;
        LinearLayout layout = Ui.column(this);
        Spinner sites = new Spinner(this);
        String[] labels = new String[Site.values().length];
        for(int i=0;i<labels.length;i++)labels[i]=Site.values()[i].label;
        sites.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        if (original == null) { layout.addView(Ui.muted(this,"选择站点",13)); layout.addView(sites); }
        EditText label = new EditText(this); label.setHint("账号备注"); label.setSingleLine(true); label.setSaveEnabled(false);
        if (original != null) label.setText(original.label);
        layout.addView(label);
        layout.addView(Ui.muted(this, original == null
            ? "选择站点后正常网页登录。Router、Congee 支持 Linux DO 或站内账号；不会读取密码。"
            : "仅修改备注，不查询网络，也不更改登录状态。", 13));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "添加账号" : "修改备注")
            .setView(layout).setNegativeButton("取消", null).setPositiveButton(original == null ? "前往登录" : "保存备注", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = label.getText().toString().trim();
                if (name.isEmpty() || name.length() > 40) { label.setError("请填写 1～40 字备注"); return; }
                if (original == null) { startLogin(slot, name, null, Site.values()[sites.getSelectedItemPosition()]); dialog.dismiss(); return; }
                Account current = find(slot);
                if (current == null || !current.revision.equals(original.revision)) { message("账号已发生变化，请重试"); return; }
                Account renamed = current.renamed(name);
                List<Account> next = new ArrayList<>(accounts); next.set(next.indexOf(current), renamed);
                if (save(next)) {
                    Progress p = progress.get(slot); if (p != null) progress.put(slot, p.copy());
                    queue.removeIf(a -> a.slot == slot); persistSnapshots(); dialog.dismiss(); render();
                }
            });
        });
        dialog.show();
    }
}
