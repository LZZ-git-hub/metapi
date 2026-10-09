package com.nexacheckin;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.List;

/** One inexact local alarm for all accounts. No credentials in alarms, notifications or preferences. */
final class ExpiryNotifications {
    static final String CHANNEL = "credential-expiry";
    static final String ACTION = "com.nexacheckin.CHECK_CREDENTIAL_EXPIRY";
    private static final int ALARM_ID = 4000, NOTIFICATION_BASE = 4100;
    private static volatile String problem = "";
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("expiry-reminders", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }
    static boolean setEnabled(Context context, boolean enabled) {
        return prefs(context).edit().putBoolean("enabled", enabled).commit();
    }
    static boolean permissionAsked(Context context) { return prefs(context).getBoolean("permissionAsked", false); }
    static boolean markPermissionAsked(Context context) { return prefs(context).edit().putBoolean("permissionAsked", true).commit(); }
    static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "访问凭证到期提醒", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("到期前约 1 小时提醒；只在本地判断，不自动查询或续期。");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    static boolean needsAttention(Context context) {
        return !problem.isEmpty() || enabled(context) && !allowed(context);
    }
    static String status(Context context) {
        if (!problem.isEmpty()) return problem;
        if (!enabled(context)) return "系统提醒已关闭 · 首页仍显示到期提示";
        if (!allowed(context)) return "系统通知未开启 · 请允许通知并开启到期提醒渠道";
        return "系统提醒已开启 · 到期前约 1 小时；省电限制可能延迟";
    }
    private static PendingIntent alarm(Context context) {
        Intent intent = new Intent(context, ExpiryReminderReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, ALARM_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent openHome(Context context) {
        Intent intent = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static Notification notification(Context context, Account a, int level) {
        String title = level == ExpiryPolicy.EXPIRED ? "访问凭证已到期" : "访问凭证即将到期";
        String text = a.refreshToken.isEmpty() ? "请打开 Nexa，在账号管理中重新登录。" : "请打开 Nexa，手动刷新相应账号以续期。";
        Notification publicVersion = new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Nexa 提醒")
            .setContentText("请打开应用查看账号提醒").build();
        // Deliberately omit account labels/IDs, amounts, tokens and expiry metadata from the system surface.
        return new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(openHome(context)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion).build();
    }
    // Called while AccountStore's lock is held: a stale receiver cannot overwrite a new schedule.
    static void reconcile(Context context, List<Account> accounts, long now) {
        try {
            problem = ""; createChannel(context);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            PendingIntent pending = alarm(context);
            alarms.cancel(pending);
            boolean deliver = enabled(context) && allowed(context);
            SharedPreferences saved = prefs(context); SharedPreferences.Editor edit = saved.edit();
            long next = 0;
            for (int slot = 1; slot <= 9; slot++) {
                Account a = null;
                for (Account candidate : accounts) if (candidate.slot == slot) a = candidate;
                String keyName = "key-" + slot, levelName = "level-" + slot;
                int id = NOTIFICATION_BASE + slot;
                if (a == null || a.expiresAt <= 0) {
                    manager.cancel(id); edit.remove(keyName).remove(levelName); continue;
                }
                String key = ExpiryPolicy.key(a), lastKey = saved.getString(keyName, "");
                int level = ExpiryPolicy.level(a.expiresAt, now), lastLevel = saved.getInt(levelName, 0);
                if (!deliver || !key.equals(lastKey) || level == ExpiryPolicy.VALID) manager.cancel(id);
                if (deliver && ExpiryPolicy.shouldNotify(key, level, lastKey, lastLevel)) {
                    // Permission can be revoked between checks; failure remains visible in the home status.
                    manager.notify(id, notification(context, a, level));
                    edit.putString(keyName, key).putInt(levelName, level);
                }
                if (deliver) {
                    long candidate = ExpiryPolicy.nextCheck(a.expiresAt, now);
                    if (candidate > 0 && (next == 0 || candidate < next)) next = candidate;
                }
            }
            // An ordinary inexact alarm, not a foreground service or an exact-alarm permission request.
            if (next > 0) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
            if (!edit.commit()) problem = "提醒记录保存失败，重启后可能重复提示；账号凭证未受影响";
        } catch (RuntimeException e) {
            problem = "系统提醒安排失败，请检查通知设置；首页提醒仍可查看";
        }
    }
    static void accountReadFailed(Context context) {
        problem = "无法读取账号提醒，请打开应用检查；未发起网络请求";
        try {
            context.getSystemService(AlarmManager.class).cancel(alarm(context));
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            for (int slot = 1; slot <= 9; slot++) manager.cancel(NOTIFICATION_BASE + slot);
        } catch (RuntimeException ignored) { /* Status above remains an explicit failure. */ }
    }
}
