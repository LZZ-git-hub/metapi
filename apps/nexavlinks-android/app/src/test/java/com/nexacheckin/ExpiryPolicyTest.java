package com.nexacheckin;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ExpiryPolicyTest {
    private final long now = 10000000L;
    private Account account(long expiry) {
        return new Account(1, "r", "test", "dummy-access", "123", "{\"id\":123}", "dummy-refresh", expiry);
    }
    @Test public void exactOneHourBoundaryAndExpiry() {
        assertEquals(ExpiryPolicy.VALID, ExpiryPolicy.level(now + ExpiryPolicy.LEAD_MS + 1, now));
        assertEquals(ExpiryPolicy.SOON, ExpiryPolicy.level(now + ExpiryPolicy.LEAD_MS, now));
        assertEquals(ExpiryPolicy.SOON, ExpiryPolicy.level(now + 1, now));
        assertEquals(ExpiryPolicy.EXPIRED, ExpiryPolicy.level(now, now));
        assertEquals(ExpiryPolicy.EXPIRED, ExpiryPolicy.level(now - 1, now));
    }
    @Test public void missingExpiryNeverSchedulesOrPretendsExpired() {
        assertEquals(ExpiryPolicy.UNKNOWN, ExpiryPolicy.level(0, now));
        assertEquals(0, ExpiryPolicy.nextCheck(0, now));
        assertFalse(ExpiryPolicy.shouldNotify("a", ExpiryPolicy.UNKNOWN, "", 0));
        assertTrue(ExpiryPolicy.message(account(0), now).contains("无法提前提醒"));
    }
    @Test public void scheduleUsesWarningThenExpiryAndStopsAfterExpiry() {
        long expiry = now + 2 * ExpiryPolicy.LEAD_MS;
        assertEquals(expiry - ExpiryPolicy.LEAD_MS, ExpiryPolicy.nextCheck(expiry, now));
        assertEquals(expiry, ExpiryPolicy.nextCheck(expiry, expiry - ExpiryPolicy.LEAD_MS));
        assertEquals(0, ExpiryPolicy.nextCheck(expiry, expiry));
    }
    @Test public void shortLivedTokenImmediatelyQualifiesForWarning() {
        assertEquals(ExpiryPolicy.SOON, ExpiryPolicy.level(now + 1000, now));
        assertEquals(now + 1000, ExpiryPolicy.nextCheck(now + 1000, now));
        assertEquals(1, ExpiryPolicy.minutesLeft(now + 1000, now));
    }
    @Test public void countdownRoundsUpAndNeverNegative() {
        assertEquals(60, ExpiryPolicy.minutesLeft(now + ExpiryPolicy.LEAD_MS, now));
        assertEquals(2, ExpiryPolicy.minutesLeft(now + 60001, now));
        assertEquals(1, ExpiryPolicy.minutesLeft(now + 60000, now));
        assertEquals(0, ExpiryPolicy.minutesLeft(now - 1, now));
    }
    @Test public void deduplicatesRepeatedBroadcastsAndHomeResumes() {
        assertTrue(ExpiryPolicy.shouldNotify("cycle1", ExpiryPolicy.SOON, "", 0));
        assertFalse(ExpiryPolicy.shouldNotify("cycle1", ExpiryPolicy.SOON, "cycle1", ExpiryPolicy.SOON));
        assertTrue(ExpiryPolicy.shouldNotify("cycle1", ExpiryPolicy.EXPIRED, "cycle1", ExpiryPolicy.SOON));
        assertFalse(ExpiryPolicy.shouldNotify("cycle1", ExpiryPolicy.EXPIRED, "cycle1", ExpiryPolicy.EXPIRED));
    }
    @Test public void delayedAlarmReportsExpiredRatherThanAnOldWarning() {
        long expiry = now - 1000;
        int level = ExpiryPolicy.level(expiry, now);
        assertEquals(ExpiryPolicy.EXPIRED, level);
        assertTrue(ExpiryPolicy.shouldNotify("key", level, "", 0));
        assertEquals(0, ExpiryPolicy.nextCheck(expiry, now));
    }
    @Test public void clockRollbackDoesNotRepeatEarlierStage() {
        assertFalse(ExpiryPolicy.shouldNotify("key", ExpiryPolicy.SOON, "key", ExpiryPolicy.EXPIRED));
        assertFalse(ExpiryPolicy.shouldNotify("key", ExpiryPolicy.VALID, "key", ExpiryPolicy.EXPIRED));
        long futureExpiry = now + 2 * ExpiryPolicy.LEAD_MS;
        assertEquals(futureExpiry - ExpiryPolicy.LEAD_MS, ExpiryPolicy.nextCheck(futureExpiry, now));
    }
    @Test public void renewalReplacesOldWarningScheduleAndDedupKey() {
        Account old = account(now + 1000), renewed = account(now + 86400000);
        assertNotEquals(ExpiryPolicy.key(old), ExpiryPolicy.key(renewed));
        assertEquals(ExpiryPolicy.VALID, ExpiryPolicy.level(renewed.expiresAt, now));
        assertFalse(ExpiryPolicy.shouldNotify(ExpiryPolicy.key(renewed), ExpiryPolicy.VALID,
            ExpiryPolicy.key(old), ExpiryPolicy.SOON));
        assertEquals(renewed.expiresAt - ExpiryPolicy.LEAD_MS, ExpiryPolicy.nextCheck(renewed.expiresAt, now));
    }
    @Test public void renameDoesNotRepeatNotificationButSlotReuseDoes() {
        Account a = account(now + 1000);
        assertEquals(ExpiryPolicy.key(a), ExpiryPolicy.key(a.renamed("different label")));
        Account other = new Account(1, "new", "other", "token", "456", "{\"id\":456}", "refresh", a.expiresAt);
        assertNotEquals(ExpiryPolicy.key(a), ExpiryPolicy.key(other));
        assertTrue(ExpiryPolicy.shouldNotify(ExpiryPolicy.key(other), ExpiryPolicy.SOON, ExpiryPolicy.key(a), ExpiryPolicy.SOON));
    }
    @Test public void keyDoesNotExposeTokensOrUserIdAndDiffersAcrossSlots() {
        Account a = account(now + 1000);
        String key = ExpiryPolicy.key(a);
        assertEquals(64, key.length()); assertTrue(key.matches("[0-9a-f]{64}"));
        assertFalse(key.contains("dummy"));
        Account b = new Account(2, "r", "test", a.credential, a.userId, a.userJson, a.refreshToken, a.expiresAt);
        assertNotEquals(key, ExpiryPolicy.key(b));
    }
    @Test public void guidanceDistinguishesRefreshableAndLegacyAccounts() {
        assertTrue(ExpiryPolicy.message(account(now), now).contains("刷新续期"));
        Account legacy = new Account(1, "r", "test", "dummy", "123", "{\"id\":123}", "", now);
        assertTrue(ExpiryPolicy.message(legacy, now).contains("重新登录"));
        assertEquals("", ExpiryPolicy.message(account(now + 2 * ExpiryPolicy.LEAD_MS), now));
    }
}
