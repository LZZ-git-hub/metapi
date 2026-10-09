package com.nexacheckin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RenewalTest {
    private Account account(String refresh) {
        return new Account(1, "r1", "test", "old-access", "123", "{\"id\":123}", refresh, 1000);
    }
    private JSONObject pair() throws Exception {
        return new JSONObject().put("access_token", "new-access").put("refresh_token", "new-refresh")
            .put("token_type", "Bearer").put("expires_in", 86400);
    }
    @Test public void everyManualRenewalExchangesOnceAndSavesBeforeReturning() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Account original = account("old-refresh");
        Account next = Renewal.renew(original, token -> {
            assertEquals("old-refresh", token); calls.incrementAndGet(); return pair();
        }, (expected, replacement) -> {
            assertSame(original, expected); assertEquals("new-refresh", replacement.refreshToken);
            calls.incrementAndGet();
        }, () -> 2000L);
        assertEquals(2, calls.get()); assertEquals("new-access", next.credential);
        assertEquals(86402000, next.expiresAt); assertEquals(original.userId, next.userId);
        assertNotEquals(original.revision, next.revision);
        assertEquals("old-refresh", original.refreshToken);
    }
    @Test public void repeatedManualClicksUseLastRotatedRefreshToken() throws Exception {
        Account first = Renewal.renew(account("old-refresh"), t -> pair(), (a,b) -> {}, () -> 2000);
        Renewal.renew(first, token -> { assertEquals("new-refresh", token); return pair(); }, (a,b) -> {}, () -> 3000);
    }
    @Test public void futureExpiryDoesNotSkipExplicitRefresh() throws Exception {
        Account a = new Account(1, "r", "test", "access", "123", "{\"id\":123}", "refresh", 90000000);
        AtomicInteger calls = new AtomicInteger();
        Renewal.renew(a, token -> { calls.incrementAndGet(); return pair(); }, (x,y) -> {}, () -> 1000);
        assertEquals(1, calls.get());
    }
    @Test public void legacyAccountDoesNotInventRefreshToken() throws Exception {
        Account a = account("");
        assertSame(a, Renewal.renew(a, token -> { fail("unexpected POST"); return null; },
            (x,y) -> fail("unexpected save"), () -> 1000));
    }
    @Test public void failureDoesNotRetryOrSave() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(Exception.class, () -> Renewal.renew(account("refresh"), token -> {
            calls.incrementAndGet(); throw new Exception("offline");
        }, (a,b) -> fail("save after failure"), () -> 1000));
        assertEquals(1, calls.get());
    }
    @Test public void malformedRotationDoesNotOverwriteStore() {
        assertThrows(Exception.class, () -> Renewal.renew(account("refresh"), t -> new JSONObject(),
            (a,b) -> fail("save invalid response"), () -> 1000));
    }
    @Test public void saveFailureStopsCallerBeforeItCanQuery() {
        assertThrows(Exception.class, () -> Renewal.renew(account("refresh"), t -> pair(),
            (a,b) -> { throw new Exception("disk failed"); }, () -> 1000));
    }
    @Test public void rejectsMissingTokenBadTypeAndExpiry() throws Exception {
        JSONObject j = pair(); j.remove("refresh_token");
        assertThrows(Exception.class, () -> TokenPair.fromRefresh(j, 1000));
        for (Object expiry : new Object[]{0, -1, "NaN", "1.2", Long.MAX_VALUE, true}) {
            JSONObject bad = pair().put("expires_in", expiry);
            assertThrows(Exception.class, () -> TokenPair.fromRefresh(bad, 1000));
        }
        JSONObject bad = pair().put("token_type", "Basic");
        assertThrows(Exception.class, () -> TokenPair.fromRefresh(bad, 1000));
    }
    @Test public void tokenValidationDoesNotEchoSecrets() {
        Exception e = assertThrows(Exception.class, () -> new TokenPair("secret\naccess", "refresh", 1));
        assertFalse(e.getMessage().contains("secret"));
    }
    @Test public void snapshotOfPairMustIncludeRefreshAndExpiryForEquality() throws Exception {
        TokenPair pair = TokenPair.fromStorage(new JSONObject().put("access_token", "a")
            .put("refresh_token", "r").put("expires_at", "86401000"));
        assertEquals(86401000, pair.expiresAt);
        assertTrue(pair.same(new TokenPair("a", "r", 86401000)));
        assertFalse(pair.same(new TokenPair("a", "r2", 86401000)));
        assertFalse(pair.same(new TokenPair("a", "r", 86401001)));
        assertFalse(pair.same(null));
    }
    @Test public void oldAccountJsonAndNewEncryptedPayloadRemainCompatible() throws Exception {
        Account a = account("secret-refresh");
        Account read = Account.from(new JSONObject(a.json().toString()));
        assertEquals(a.refreshToken, read.refreshToken); assertEquals(a.expiresAt, read.expiresAt);
        JSONObject legacy = a.json(); legacy.remove("refreshToken"); legacy.remove("expiresAt");
        Account old = Account.from(legacy); assertEquals("", old.refreshToken); assertEquals(0, old.expiresAt);
    }
    @Test public void renamePreservesPairAndExpiry() {
        Account a = account("refresh"), b = a.renamed("new name");
        assertEquals(a.refreshToken, b.refreshToken); assertEquals(a.expiresAt, b.expiresAt);
        assertEquals(a.credential, b.credential); assertNotEquals(a.revision, b.revision);
    }
    @Test public void rotationCannotOverwriteChangedAccountOrDifferentUser() throws Exception {
        Account a = account("refresh"), b = a.renamed("changed");
        assertThrows(Exception.class, () -> Renewal.replace(Collections.singletonList(b), a, a));
        assertThrows(Exception.class, () -> Renewal.replace(Collections.emptyList(), a, a));
        Account other = new Account(1,"new","test","token","456","{\"id\":456}");
        assertThrows(Exception.class, () -> Renewal.replace(Collections.singletonList(a), a, other));
    }
    @Test public void rotationPreservesOtherAccountsAndOriginBoundSnapshotsExcludePair() throws Exception {
        Account a = account("refresh"), other = new Account(2,"r","other","token","456","{\"id\":456}");
        Account next = Renewal.renew(a, t -> pair(), (x,y) -> {}, () -> 1000);
        List<Account> list = Renewal.replace(Arrays.asList(a, other), a, next);
        assertSame(other, list.get(1)); assertSame(next, list.get(0));
        String snapshot = SnapshotData.encode(list, Collections.singletonMap(1,new Progress("2026-09-30"))).toString();
        assertFalse(snapshot.contains("new-refresh")); assertFalse(snapshot.contains("new-access"));
    }
    @Test public void identityIsCheckedBeforeAnyUsageQueryWithRotatedPair() throws Exception {
        Account a = Renewal.renew(account("refresh"), t -> pair(), (x,y) -> {}, () -> 1000);
        AtomicInteger calls = new AtomicInteger();
        Progress result = Progress.read(a, (path, credential) -> {
            calls.incrementAndGet(); assertEquals("/auth/me", path); assertEquals("new-access", credential);
            return new JSONObject().put("id", 456);
        }, () -> "2026-09-30");
        assertEquals(1, calls.get()); assertNull(result.balance); assertNull(result.cost); assertNull(result.checked);
    }
}
