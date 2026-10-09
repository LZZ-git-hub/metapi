package com.nexacheckin;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RefreshPolicyTest {
    private Account a(int slot, String revision) {
        return new Account(slot, revision, "test", "dummy", "123", "{\"id\":123}");
    }
    @Test public void boundedFanoutHasThreeLanes() {
        assertEquals(3, RefreshPolicy.MAX_CONCURRENT);
        assertTrue(RefreshPolicy.hasCapacity(0)); assertTrue(RefreshPolicy.hasCapacity(2));
        assertFalse(RefreshPolicy.hasCapacity(3)); assertFalse(RefreshPolicy.hasCapacity(9));
    }
    @Test public void releasedLaneIsImmediatelyAvailableWithoutCooldown() {
        int active = 3; assertFalse(RefreshPolicy.hasCapacity(active));
        active--; assertTrue(RefreshPolicy.hasCapacity(active));
        Progress result = new Progress("2026-09-30"); result.requestedAt = System.currentTimeMillis();
        assertFalse(RefreshPolicy.isBusy(result));
        result.loading = true; assertTrue(RefreshPolicy.isBusy(result));
    }
    @Test public void sameVisitSelectsOnlyItsOwnSlotAndRevision() {
        assertTrue(RefreshPolicy.matchesVisit(a(2,"visited"),2,"visited"));
        assertFalse(RefreshPolicy.matchesVisit(a(1,"visited"),2,"visited"));
    }
    @Test public void changedOrDeletedAccountCannotBeRefreshedByOldVisit() {
        assertFalse(RefreshPolicy.matchesVisit(a(2,"replacement"),2,"visited"));
        assertFalse(RefreshPolicy.matchesVisit(null,2,"visited"));
    }
    @Test public void lostVisitMetadataIsNotGuessedFromOtherAccounts() {
        assertFalse(RefreshPolicy.matchesVisit(a(2,"r"),0,""));
        assertFalse(RefreshPolicy.matchesVisit(a(2,"r"),2,""));
    }
}
