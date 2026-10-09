package com.nexacheckin;

import org.junit.Test;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class ProgressTest {
    private Account account() { return new Account(1, "r1", "test", "dummy", "123", "{\"id\":123}"); }
    private JSONObject response(String path) throws Exception {
        if (path.equals("/auth/me")) return new JSONObject().put("id", 123).put("balance", "12.34");
        if (path.equals("/check-in")) return new JSONObject().put("checked_in_today", true);
        return new JSONObject().put("today_actual_cost", "0.42").put("total_actual_cost", "9.876");
    }
    @Test public void confirmedUpstreamStateOnly() {
        Progress p = Progress.read(account(), (path, credential) -> response(path), () -> "2026-09-28");
        assertEquals(Boolean.TRUE, p.checked); assertEquals("0.42", p.cost.toPlainString());
        assertEquals("", p.warning); assertTrue(p.syncedAt > 0);
    }
    @Test public void wrongIdentityStopsFurtherQueries() {
        AtomicInteger calls = new AtomicInteger();
        Progress p = Progress.read(account(), (path, credential) -> {
            calls.incrementAndGet(); return new JSONObject().put("id", 456);
        }, () -> "2026-09-28");
        assertEquals(1, calls.get()); assertNull(p.cost); assertNull(p.checked); assertFalse(p.warning.isEmpty());
    }
    @Test public void failedUsageDoesNotInventZero() {
        Progress p = Progress.read(account(), (path, credential) -> {
            if (path.equals("/usage/dashboard/stats")) throw new Exception("offline");
            return response(path);
        }, () -> "2026-09-28");
        assertNull(p.cost); assertEquals(Boolean.TRUE, p.checked); assertFalse(p.warning.isEmpty());
    }
    @Test public void failedCheckinDoesNotInventUnchecked() {
        Progress p = Progress.read(account(), (path, credential) -> {
            if (path.equals("/check-in")) return new JSONObject();
            return response(path);
        }, () -> "2026-09-28");
        assertNull(p.checked); assertNotNull(p.cost); assertFalse(p.warning.isEmpty());
    }
    @Test public void lifetimeAndBalanceReuseExistingThreeQueries() {
        AtomicInteger calls = new AtomicInteger();
        Progress p = Progress.read(account(), (path, credential) -> { calls.incrementAndGet(); return response(path); }, () -> "2026-09-28");
        assertEquals(3, calls.get()); assertEquals("12.34", p.balance.toPlainString());
        assertEquals("9.876", p.totalCost.toPlainString());
    }
    @Test public void missingLifetimeIsNotStandardCostOrZero() {
        Progress p = Progress.read(account(), (path, credential) -> path.equals("/usage/dashboard/stats")
            ? new JSONObject().put("today_actual_cost", 1).put("total_cost", 100) : response(path), () -> "2026-09-28");
        assertNull(p.totalCost); assertNotNull(p.cost); assertTrue(p.warning.contains("累计"));
    }
    @Test public void invalidBalanceDoesNotDiscardOtherMetrics() {
        Progress p = Progress.read(account(), (path, credential) -> path.equals("/auth/me")
            ? new JSONObject().put("id", 123).put("balance", "NaN") : response(path), () -> "2026-09-28");
        assertNull(p.balance); assertNotNull(p.totalCost); assertEquals(Boolean.TRUE, p.checked);
    }
    @Test public void negativeBalanceIsPreserved() {
        Progress p = Progress.read(account(), (path, credential) -> path.equals("/auth/me")
            ? new JSONObject().put("id", 123).put("balance", "-0.125") : response(path), () -> "2026-09-28");
        assertEquals("-0.125", p.balance.toPlainString());
    }
    @Test public void eligibilityComesFromSameCheckinResponseWithoutExtraRequest() {
        AtomicInteger calls = new AtomicInteger();
        Progress p = Progress.read(account(), (path, credential) -> {
            calls.incrementAndGet();
            return path.equals("/check-in") ? new JSONObject().put("checked_in_today",false).put("eligible",true) : response(path);
        }, () -> "2026-09-28");
        assertEquals(3,calls.get()); assertEquals(Boolean.TRUE,p.eligible); assertEquals(Boolean.FALSE,p.checked);
    }
    @Test public void serverRejectionIsNotOverriddenByTodayConsumption() {
        Progress p = Progress.read(account(), (path, credential) -> path.equals("/check-in")
            ? new JSONObject().put("checked_in_today",false).put("eligible",false) : response(path), () -> "2026-09-28");
        assertEquals("0.42",p.cost.toPlainString()); assertEquals(Boolean.FALSE,p.eligible);
    }
    @Test public void missingOrMalformedEligibilityRemainsUnknown() {
        for (Object value : new Object[]{JSONObject.NULL,"true",1}) {
            Progress p = Progress.read(account(), (path,credential) -> path.equals("/check-in")
                ? new JSONObject().put("checked_in_today",false).put("eligible",value) : response(path), () -> "2026-09-28");
            assertEquals(Boolean.FALSE,p.checked); assertNull(p.eligible);
        }
    }
    @Test public void eligibilityRoundtripsAndLegacySnapshotsStayUnknown() throws Exception {
        Progress p = new Progress("2026-09-28"); p.eligible = false;
        assertEquals(Boolean.FALSE,Progress.from(new JSONObject(p.json().toString())).eligible);
        assertEquals(Boolean.FALSE,p.copy().eligible);
        JSONObject legacy = p.json(); legacy.remove("eligible"); assertNull(Progress.from(legacy).eligible);
    }
    @Test public void crossingMidnightInvalidatesEligibilityToo() {
        AtomicInteger clock = new AtomicInteger();
        Progress p = Progress.read(account(), (path, credential) -> path.equals("/check-in")
            ? new JSONObject().put("checked_in_today",false).put("eligible",true) : response(path),
            () -> clock.getAndIncrement() == 0 ? "2026-09-27" : "2026-09-28");
        assertNull(p.checked); assertNull(p.eligible);
    }
    @Test public void crossingMidnightInvalidatesBothFields() {
        AtomicInteger clock = new AtomicInteger();
        Progress p = Progress.read(account(), (path, credential) -> response(path),
            () -> clock.getAndIncrement() == 0 ? "2026-09-27" : "2026-09-28");
        assertNull(p.cost); assertNull(p.checked); assertTrue(p.syncedAt > 0);
        assertNotNull(p.balance); assertNotNull(p.totalCost);
    }
}
