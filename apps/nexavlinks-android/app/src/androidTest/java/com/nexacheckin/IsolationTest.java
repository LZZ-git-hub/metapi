package com.nexacheckin;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.test.AndroidTestCase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Run on a disposable emulator/test installation: uses a dedicated vault subdirectory. */
@SuppressWarnings("deprecation")
public final class IsolationTest extends AndroidTestCase {
    public void testPrivateSlotProcesses() throws Exception {
        String pkg = getContext().getPackageName();
        PackageInfo info = getContext().getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
        Set<String> processes = new HashSet<>();
        int slots = 0;
        for (ActivityInfo activity : info.activities) {
            if (!activity.name.matches("com\\.nexacheckin\\.Slot[1-9]Activity")) continue;
            slots++;
            assertFalse(activity.exported);
            assertTrue(processes.add(Policy.profileSuffix(pkg, activity.processName)));
            String slot = activity.name.substring("com.nexacheckin.Slot".length(), "com.nexacheckin.Slot".length() + 1);
            assertEquals(pkg + ":account" + slot, activity.processName);
        }
        assertEquals(9, slots);
    }
    public void testExpiryReceiverStaysPrivateAndDoesNotRequireExactAlarms() throws Exception {
        String pkg = getContext().getPackageName();
        android.content.ComponentName name = new android.content.ComponentName(pkg, "com.nexacheckin.ExpiryReminderReceiver");
        ActivityInfo receiver = getContext().getPackageManager().getReceiverInfo(name, 0);
        assertFalse(receiver.exported); assertEquals(pkg, receiver.processName);
        PackageInfo info = getContext().getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
        java.util.List<String> permissions = java.util.Arrays.asList(info.requestedPermissions);
        assertTrue(permissions.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED"));
        assertFalse(permissions.contains("android.permission.SCHEDULE_EXACT_ALARM"));
        assertFalse(permissions.contains("android.permission.USE_EXACT_ALARM"));
    }
    public void testSnapshotCacheIsSeparateAndEncrypted() throws Exception {
        Context context = new android.content.ContextWrapper(getContext()) {
            @Override public File getNoBackupFilesDir() {
                File dir = new File(super.getNoBackupFilesDir(), "instrumentation-snapshots");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Test directory unavailable");
                return dir;
            }
        };
        Account a = new Account(1, "test-r", "test", "dummy-secret", "123", "{\"id\":123}");
        Vault accounts = new Vault(context), cache = new Vault(context, "snapshots.enc");
        accounts.save(Collections.singletonList(a));
        Progress p = new Progress("2026-09-28"); p.balance = new java.math.BigDecimal("123456789.123456789");
        p.totalCost = new java.math.BigDecimal("0.000000001"); p.syncedAt = 12345;
        cache.saveValues(SnapshotData.encode(Collections.singletonList(a), Collections.singletonMap(1, p)));
        Progress read = SnapshotData.decode(Collections.singletonList(a), cache.loadValues()).get(1);
        assertEquals(p.balance, read.balance); assertEquals(p.totalCost, read.totalCost);
        File file = new File(context.getNoBackupFilesDir(), "snapshots.enc");
        byte[] blob = Files.readAllBytes(file.toPath());
        assertFalse(new String(blob, StandardCharsets.ISO_8859_1).contains("123456789.123456789"));
        blob[blob.length - 1] ^= 1; Files.write(file.toPath(), blob);
        try { cache.loadValues(); fail("Tampered snapshot accepted"); }
        catch (javax.crypto.AEADBadTagException expected) { /* fail closed */ }
        finally { cache.saveValues(new org.json.JSONArray()); }
        assertEquals(a.credential, accounts.load().get(0).credential);
    }
    public void testRotationStoreProtectsBusySlotAndMergesUnrelatedRename() throws Exception {
        Context context = new android.content.ContextWrapper(getContext()) {
            @Override public File getNoBackupFilesDir() {
                File dir = new File(super.getNoBackupFilesDir(), "instrumentation-rotation");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Test directory unavailable");
                return dir;
            }
        };
        Vault vault = new Vault(context);
        Account a = new Account(1,"r1","a","access","123","{\"id\":123}","refresh",1000);
        Account b = new Account(2,"r2","b","access-b","456","{\"id\":456}");
        java.util.List<Account> old = java.util.Arrays.asList(a,b);
        vault.save(old); AccountStore store = new AccountStore(vault);
        store.begin(a);
        try { store.begin(a); fail("Duplicate rotation accepted"); } catch (Exception expected) { }
        Account rotated = new Account(1,"r3","a","new-access","123","{\"id\":123}","new-refresh",90000);
        store.rotate(a, rotated);
        Account renamed = b.renamed("new b");
        store.save(old, java.util.Arrays.asList(a,renamed));
        assertEquals("new-refresh", vault.load().get(0).refreshToken);
        assertEquals("new b", vault.load().get(1).label);
        try { store.save(store.list(), java.util.Arrays.asList(rotated.renamed("bad"),renamed)); fail("Busy edit accepted"); }
        catch (Exception expected) { }
        store.finish(a.slot); assertFalse(store.busy(a.slot));
        assertEquals("new-refresh", new AccountStore(vault).list().get(0).refreshToken);
    }
    public void testThreeConcurrentSlotsMergeRotationsAndReleaseCapacity() throws Exception {
        Context context = new android.content.ContextWrapper(getContext()) {
            @Override public File getNoBackupFilesDir() {
                File dir = new File(super.getNoBackupFilesDir(), "instrumentation-batch");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Test directory unavailable");
                return dir;
            }
        };
        Vault vault = new Vault(context);
        java.util.List<Account> list = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) list.add(new Account(i, "r" + i, "test", "access" + i,
            String.valueOf(i), "{\"id\":" + i + "}", "refresh" + i, 1000));
        vault.save(list); AccountStore store = new AccountStore(vault);
        assertTrue(store.tryBegin(list.get(0))); assertTrue(store.tryBegin(list.get(1))); assertTrue(store.tryBegin(list.get(2)));
        assertFalse(store.tryBegin(list.get(3)));
        try { store.tryBegin(list.get(0)); fail("Duplicate slot accepted"); } catch (Exception expected) { }
        for (int i : new int[]{2,0,1}) {
            Account old = list.get(i);
            store.rotate(old, new Account(old.slot, "new" + i, old.label, "new-access" + i, old.userId, old.userJson,
                "new-refresh" + i, 90000));
        }
        store.finish(2); assertTrue(store.tryBegin(list.get(3)));
        store.finish(1); store.finish(3); store.finish(4);
        for (int i = 0; i < 3; i++) assertEquals("new-refresh" + i, vault.load().get(i).refreshToken);
        assertEquals("refresh4", vault.load().get(3).refreshToken);
    }
    public void testEncryptedVaultRoundtripAndTamperRejection() throws Exception {
        Context context = new android.content.ContextWrapper(getContext()) {
            @Override public File getNoBackupFilesDir() {
                File dir = new File(super.getNoBackupFilesDir(), "instrumentation-vault");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Test directory unavailable");
                return dir;
            }
        };
        Vault vault = new Vault(context);
        Account a = new Account(1, "test-revision", "test", "dummy-secret-123", "123", "{\"id\":123}", "dummy-refresh-secret", 86401000);
        vault.save(Collections.singletonList(a));
        assertEquals(a.credential, vault.load().get(0).credential);
        assertEquals(a.refreshToken, vault.load().get(0).refreshToken);
        assertEquals(a.expiresAt, vault.load().get(0).expiresAt);
        File file = new File(context.getNoBackupFilesDir(), "accounts.enc");
        byte[] blob = Files.readAllBytes(file.toPath());
        assertFalse(new String(blob, StandardCharsets.ISO_8859_1).contains(a.credential));
        assertFalse(new String(blob, StandardCharsets.ISO_8859_1).contains(a.refreshToken));
        blob[blob.length - 1] ^= 1;
        Files.write(file.toPath(), blob);
        try { vault.load(); fail("Tampered ciphertext accepted"); }
        catch (javax.crypto.AEADBadTagException expected) { /* fail closed */ }
        finally { vault.save(Collections.emptyList()); }
    }
}
