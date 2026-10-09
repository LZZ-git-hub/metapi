package com.nexacheckin;

import org.junit.Test;
import org.json.JSONArray;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

public final class SnapshotTest {
    private Account a(int slot, String revision, String id) { return new Account(slot, revision, "test", "dummy-secret", id, "{\"id\":" + id + "}"); }
    private Progress p() {
        Progress p = new Progress("2026-09-28"); p.cost = new BigDecimal("0.42");
        p.totalCost = new BigDecimal("1.23456789"); p.balance = new BigDecimal("5.1");
        p.checked = true; p.syncedAt = 12345; p.requestedAt = 12000; return p;
    }
    @Test public void roundtripKeepsPrecisionButNotLoadingOrSecrets() throws Exception {
        Account a = a(1, "r", "1"); Progress p = p(); p.loading = true;
        JSONArray j = SnapshotData.encode(Arrays.asList(a), Collections.singletonMap(1, p));
        assertFalse(j.toString().contains("dummy-secret"));
        assertTrue(j.getJSONObject(0).getJSONObject("progress").get("totalCost") instanceof String);
        Progress read = SnapshotData.decode(Arrays.asList(a), new JSONArray(j.toString())).get(1);
        assertEquals(p.totalCost, read.totalCost); assertEquals(p.requestedAt, read.requestedAt);
        assertFalse(read.loading); assertEquals(Boolean.TRUE, read.checked);
    }
    @Test public void revisionSlotOriginAndIdentityMustMatch() throws Exception {
        Account a = a(1, "r", "1"); JSONArray j = SnapshotData.encode(Arrays.asList(a), Collections.singletonMap(1, p()));
        assertTrue(SnapshotData.decode(Arrays.asList(a(1, "new", "1")), j).isEmpty());
        assertTrue(SnapshotData.decode(Arrays.asList(a(1, "r", "2")), j).isEmpty());
        assertTrue(SnapshotData.decode(Arrays.asList(a(2, "r", "1")), j).isEmpty());
        j.getJSONObject(0).put("origin", "https://other.example");
        assertTrue(SnapshotData.decode(Arrays.asList(a), j).isEmpty());
    }
    @Test public void missingValuesStayUnknownAcrossRestart() throws Exception {
        Progress read = Progress.from(new Progress("2026-09-28").json());
        assertNull(read.cost); assertNull(read.balance); assertNull(read.totalCost); assertNull(read.checked);
    }
    @Test public void oldDateDoesNotPretendToBeToday() {
        Progress p = p(); assertFalse(p.dailyValid("2026-09-29")); assertFalse(p.checkinValid("2026-09-29"));
        Totals t = Totals.of(Arrays.asList(a(1, "r", "1")), Collections.singletonMap(1, p), "2026-09-29");
        assertEquals(0, t.todays); assertEquals(0, t.checked); assertEquals(1, t.unknown); assertEquals(1, t.balances);
    }
    @Test public void browserVisitInvalidatesCheckinNotHistoricalMoney() throws Exception {
        Progress p = p(); p.checkinPending = true;
        Progress read = Progress.from(p.json()); assertFalse(read.checkinValid(p.day)); assertEquals(p.balance, read.balance);
    }
    @Test public void totalsAreDecimalAndDoNotIncludeDeletedAccounts() {
        Account a = a(1, "r", "1"), b = a(2, "r", "2"); Map<Integer, Progress> values = new HashMap<>();
        values.put(1, p()); values.put(2, p()); values.put(3, p());
        Totals t = Totals.of(Arrays.asList(a, b), values, "2026-09-28");
        assertEquals("2.46913578", t.used.toPlainString()); assertEquals("10.2", t.balance.toPlainString()); assertEquals(2, t.balances);
    }
    @Test public void partialSumAppearsImmediatelyWithExplicitCoverage() {
        Account a = a(1, "r", "1"), b = a(2, "r", "2");
        Totals t = Totals.of(Arrays.asList(a,b), Collections.singletonMap(1, p()), "2026-09-28");
        assertEquals(1, t.balances); assertEquals("5.1", Totals.amount(t.balance, t.balances, 2));
        assertEquals("余额合计（已知）", Totals.label("余额合计", t.balances, 2));
        assertEquals("余额合计", Totals.label("余额合计", 2, 2));
        assertEquals("—", Totals.amount(BigDecimal.ZERO, 0, 0));
        assertEquals("—", Totals.amount(BigDecimal.ZERO, 0, 2));
        assertEquals("0", Totals.amount(BigDecimal.ZERO, 1, 2));
    }
    @Test public void cachedTotalsSurviveRestartWithoutRefreshingAll() throws Exception {
        Account a = a(1, "r", "1"), b = a(2, "r", "2");
        List<Account> accounts = Arrays.asList(a, b);
        JSONArray saved = SnapshotData.encode(accounts, Collections.singletonMap(1, p()));
        Map<Integer, Progress> restored = SnapshotData.decode(accounts, new JSONArray(saved.toString()));
        Totals t = Totals.of(accounts, restored, "2026-09-28");
        assertEquals("5.1", Totals.amount(t.balance, t.balances, 2));
        restored.put(2, p());
        t = Totals.of(accounts, restored, "2026-09-28");
        assertEquals("10.2", Totals.amount(t.balance, t.balances, 2));
        assertEquals(2, t.balances);
    }
    @Test public void refreshOnlyBlocksQueuedOrRunningRequests() {
        assertFalse(RefreshPolicy.isBusy(null));
        Progress p = p(); p.requestedAt = System.currentTimeMillis();
        assertFalse(RefreshPolicy.isBusy(p)); // Just completed: no cooldown.
        p.loading = true; assertTrue(RefreshPolicy.isBusy(p));
        p.loading = false; p.warning = "网络连接失败";
        assertFalse(RefreshPolicy.isBusy(p)); // Failure can be retried manually immediately.
        p.requestedAt = Long.MAX_VALUE;
        assertFalse(RefreshPolicy.isBusy(p)); // No wall-clock restriction.
    }
    @Test public void oldSnapshotTimestampDoesNotRestrictManualRefresh() throws Exception {
        Progress p = p(); p.requestedAt = System.currentTimeMillis(); p.loading = true;
        Progress read = Progress.from(p.json());
        assertEquals(p.requestedAt, read.requestedAt);
        assertFalse(RefreshPolicy.isBusy(read));
    }
}
