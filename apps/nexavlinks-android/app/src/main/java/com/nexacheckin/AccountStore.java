package com.nexacheckin;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Main-process owner. Survives Activity recreation so a rotated refresh token is not discarded. */
final class AccountStore {
    private static AccountStore instance;
    private final Vault vault;
    private final Context reminderContext;
    private List<Account> accounts;
    private final Set<Integer> active = new HashSet<>();
    static synchronized AccountStore get(Context context) throws Exception {
        if (instance == null) instance = new AccountStore(context.getApplicationContext());
        return instance;
    }
    private AccountStore(Context context) throws Exception { this(new Vault(context), context); }
    AccountStore(Vault vault) throws Exception { this(vault, null); }
    private AccountStore(Vault vault, Context context) throws Exception {
        this.vault = vault;
        // This process-scoped store must never retain an Activity/receiver context.
        reminderContext = context == null ? null : context.getApplicationContext();
        accounts = vault.load();
    }
    synchronized void rescheduleReminders() {
        if (reminderContext != null) ExpiryNotifications.reconcile(reminderContext, accounts, System.currentTimeMillis());
    }
    synchronized List<Account> list() { return new ArrayList<>(accounts); }
    synchronized boolean busy(int slot) { return active.contains(slot); }
    synchronized void begin(Account a) throws Exception {
        if (!tryBegin(a)) throw new Exception("正在查询其他账号，请稍候");
    }
    synchronized boolean tryBegin(Account a) throws Exception {
        if (active.contains(a.slot)) throw new Exception("此账号正在续期或查询，请稍候");
        boolean found = false;
        for (Account current : accounts) if (current.slot == a.slot && current.revision.equals(a.revision)) found = true;
        if (!found) throw new Exception("账号已变化，请再次手动刷新");
        // Process-wide bound, including jobs finishing after Activity recreation.
        if (!RefreshPolicy.hasCapacity(active.size())) return false;
        active.add(a.slot);
        return true;
    }
    synchronized void finish(int slot) { active.remove(slot); }
    synchronized void rotate(Account expected, Account next) throws Exception {
        List<Account> updated = Renewal.replace(accounts, expected, next);
        try { vault.save(updated); }
        catch (Exception e) { throw new Exception("新凭证加密保存失败，续期状态未确认；请重新登录，已停止查询"); }
        accounts = updated;
        rescheduleReminders();
    }
    synchronized void save(List<Account> expected, List<Account> next) throws Exception {
        // Merge changes to unrelated slots, never overwrite a concurrently rotated account.
        List<Account> merged = new ArrayList<>(accounts);
        for (int slot = 1; slot <= 9; slot++) {
            Account before = find(expected, slot), after = find(next, slot);
            if (same(before, after)) continue;
            if (active.contains(slot)) throw new Exception("正在续期或查询，请完成后再管理此账号");
            if (!same(before, find(accounts, slot))) throw new Exception("账号已变化，请重试");
            final int target = slot;
            merged.removeIf(a -> a.slot == target);
            if (after != null) merged.add(after);
        }
        Set<String> identities = new HashSet<>();
        for (Account a : merged) if (!identities.add(a.identityKey())) throw new Exception("该原站账号已添加");
        merged.sort(java.util.Comparator.comparingInt(a -> a.slot));
        vault.save(merged); accounts = merged;
        rescheduleReminders();
    }
    private static Account find(List<Account> values, int slot) {
        for (Account a : values) if (a.slot == slot) return a;
        return null;
    }
    private static boolean same(Account a, Account b) {
        return a == null ? b == null : b != null && a.site == b.site && a.revision.equals(b.revision) && a.userId.equals(b.userId);
    }
}
