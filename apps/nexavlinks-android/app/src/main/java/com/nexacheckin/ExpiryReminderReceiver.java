package com.nexacheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Protected system broadcasts and our explicit immutable alarm, all handled in the main process. */
public final class ExpiryReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!ExpiryNotifications.ACTION.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { AccountStore.get(app).rescheduleReminders(); }
            catch (Exception e) { ExpiryNotifications.accountReadFailed(app); }
            finally { pending.finish(); }
        }, "local-expiry-reminder").start();
    }
}
