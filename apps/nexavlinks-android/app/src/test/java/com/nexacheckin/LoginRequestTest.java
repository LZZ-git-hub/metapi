package com.nexacheckin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LoginRequestTest {
    private Account account(int slot, String revision, String id) {
        return new Account(slot, revision, "test", "dummy-token", id, "{\"id\":\"" + id + "\"}");
    }
    @Test public void freshLoginReturnsSortedCopy() throws Exception {
        Account old = account(3, "old", "300");
        LoginRequest r = LoginRequest.create(1, "test", null);
        List<Account> before = Collections.singletonList(old);
        List<Account> after = r.accept(before, r.nonce, account(1, "new", "100"));
        assertEquals(1, before.size()); assertEquals(2, after.size());
        assertEquals(1, after.get(0).slot); assertSame(old, after.get(1));
    }
    @Test public void roundtripContainsNoCredentials() throws Exception {
        LoginRequest r = LoginRequest.create(1, "test", account(1, "old", "100"));
        LoginRequest restored = LoginRequest.from(r.json().toString());
        assertEquals(r.nonce, restored.nonce); assertEquals(r.userId, restored.userId);
        assertFalse(r.json().has("credential")); assertFalse(r.json().has("userJson"));
        assertFalse(r.json().toString().contains("dummy-token"));
    }
    @Test public void rejectsMismatchedTransactionSlotAndLabel() {
        LoginRequest r = LoginRequest.create(1, "test", null);
        assertThrows(Exception.class, () -> r.accept(Collections.emptyList(), "wrong", account(1, "new", "100")));
        assertThrows(Exception.class, () -> r.accept(Collections.emptyList(), r.nonce, account(2, "new", "100")));
        Account wrongLabel = new Account(1, "new", "different", "dummy", "100", "{\"id\":100}");
        assertThrows(Exception.class, () -> r.accept(Collections.emptyList(), r.nonce, wrongLabel));
    }
    @Test public void duplicateUserIsRejectedWithoutMutatingAccounts() {
        List<Account> accounts = Collections.singletonList(account(2, "old", "100"));
        LoginRequest r = LoginRequest.create(1, "test", null);
        assertThrows(Exception.class, () -> r.accept(accounts, r.nonce, account(1, "new", "100")));
        assertEquals("old", accounts.get(0).revision);
    }
    @Test public void reloginCannotReplaceAnotherIdentity() {
        Account old = account(1, "old", "100");
        LoginRequest r = LoginRequest.create(1, "test", old);
        assertThrows(Exception.class, () -> r.accept(Collections.singletonList(old), r.nonce, account(1, "new", "200")));
        assertEquals("old", old.revision);
    }
    @Test public void reloginUpdatesOnlyRequestedAccount() throws Exception {
        Account old = account(1, "old", "100"), other = account(2, "other", "200");
        LoginRequest r = LoginRequest.create(1, "test", old);
        List<Account> next = r.accept(Arrays.asList(old, other), r.nonce, account(1, "new", "100"));
        assertEquals("new", next.get(0).revision); assertSame(other, next.get(1));
    }
    @Test public void staleDeletedAndOccupiedSlotsAreRejected() {
        LoginRequest r = LoginRequest.create(1, "test", account(1, "old", "100"));
        assertThrows(Exception.class, () -> r.accept(Collections.emptyList(), r.nonce, account(1, "new", "100")));
        assertThrows(Exception.class, () -> r.accept(Collections.singletonList(account(1, "changed", "100")), r.nonce, account(1, "new", "100")));
        LoginRequest fresh = LoginRequest.create(1, "test", null);
        assertThrows(Exception.class, () -> fresh.accept(Collections.singletonList(account(1, "occupied", "200")), fresh.nonce, account(1, "new", "100")));
    }
    @Test public void validatesSlotAndMetadata() {
        for (int slot : new int[]{0, 10}) assertThrows(Exception.class, () -> LoginRequest.create(slot, "test", null));
        assertThrows(Exception.class, () -> LoginRequest.create(1, " ", null));
        assertThrows(Exception.class, () -> LoginRequest.create(2, "test", account(1, "old", "100")));
        assertThrows(Exception.class, () -> new LoginRequest(1, "test", "", "", ""));
        assertThrows(Exception.class, () -> new LoginRequest(1, "test", "nonce", "revision", ""));
    }
}
