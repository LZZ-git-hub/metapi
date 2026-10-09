package com.nexacheckin;

import org.junit.Test;
import org.json.JSONObject;
import java.time.Instant;
import static org.junit.Assert.*;

public final class PolicyTest {
    @Test public void shanghaiMidnight() {
        assertEquals("2026-09-27", Policy.day(Instant.parse("2026-09-27T15:59:59Z")));
        assertEquals("2026-09-28", Policy.day(Instant.parse("2026-09-27T16:00:00Z")));
    }
    @Test public void strictOrigin() {
        assertEquals("https://asia.nexavlinks.com", Policy.ORIGIN);
        assertTrue(Policy.siteUrl(Policy.ORIGIN + "/check-in"));
        assertTrue(Policy.siteUrl(Policy.ORIGIN + "/doc"));
        assertTrue(Policy.siteUrl("https://asia.nexavlinks.com:443/home"));
        for (String url : new String[]{"https://www.nexavlinks.com/check-in", "http://asia.nexavlinks.com", "https://asia.nexavlinks.com.evil.test", "https://evil@asia.nexavlinks.com", "https://asia.nexavlinks.com:444", "javascript:alert(1)", "file:///tmp/a", "https://nexavlinks.com"})
            assertFalse(url, Policy.siteUrl(url));
    }
    @Test public void credentialsDoNotAllowHeaderInjection() {
        assertEquals("abc", Policy.credential("Bearer abc"));
        assertEquals("session=abc", Policy.credential("Cookie: session=abc"));
        assertThrows(IllegalArgumentException.class, () -> Policy.credential("a\r\nX-Test: b"));
        assertThrows(IllegalArgumentException.class, () -> Policy.credential("  "));
    }
    @Test public void malformedConsumptionIsNotZero() throws Exception {
        assertEquals("0.32", Policy.cost("0.32").toPlainString());
        assertEquals(0, Policy.cost(0).signum());
        for (Object value : new Object[]{JSONObject.NULL, "", "NaN", "Infinity", -1, true})
            assertThrows(Exception.class, () -> Policy.cost(value));
    }
    @Test public void malformedCheckinIsNotUnchecked() throws Exception {
        assertTrue(Policy.checked(true)); assertFalse(Policy.checked(false));
        for (Object value : new Object[]{JSONObject.NULL, "false", 0})
            assertThrows(Exception.class, () -> Policy.checked(value));
    }
    @Test public void identityMustBeExplicit() throws Exception {
        assertEquals("123", Policy.userId(new JSONObject().put("id", 123)));
        assertEquals("123", Policy.userId(new JSONObject().put("id", "123")));
        assertThrows(Exception.class, () -> Policy.userId(new JSONObject()));
        assertThrows(Exception.class, () -> Policy.userId(new JSONObject().put("id", true)));
    }
    @Test public void staleResultsAreRejected() {
        assertTrue(Policy.acceptResult("a", "a", "2026-09-28", "2026-09-28"));
        assertFalse(Policy.acceptResult("b", "a", "2026-09-28", "2026-09-28"));
        assertFalse(Policy.acceptResult("a", "a", "2026-09-27", "2026-09-28"));
    }
    @Test public void cookieDetectionDoesNotConfusePaddedBearerTokens() {
        assertFalse(Policy.cookie("YWJjZA=="));
        assertFalse(Policy.cookie("YWJjZA="));
        assertTrue(Policy.cookie("session=YWJjZA=="));
        assertTrue(Policy.cookie("session=abc; other=def"));
    }
    @Test public void allProfilesHaveUniqueDirectories() {
        java.util.Set<String> suffixes = new java.util.HashSet<>();
        assertNull(Policy.profileSuffix("com.nexacheckin", "com.nexacheckin"));
        for (int slot = 1; slot <= 9; slot++)
            assertTrue(suffixes.add(Policy.profileSuffix("com.nexacheckin", "com.nexacheckin:account" + slot)));
        for (String suffix : new String[]{"0", "10", "01", "1:other", ""})
            assertThrows(IllegalStateException.class, () -> Policy.profileSuffix("com.nexacheckin", "com.nexacheckin:account" + suffix));
    }
    @Test public void accountRoundtrip() throws Exception {
        Account a = new Account(9, "revision", "test", "dummy", "123", "{\"id\":123}");
        Account b = Account.from(a.json()); assertEquals(a.slot, b.slot); assertEquals(a.userId, b.userId);
        assertThrows(Exception.class, () -> Account.from(a.json().put("slot", 10)));
    }
}
