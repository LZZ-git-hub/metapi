package com.nexacheckin;

/** Explicit refresh or a completed user action; never a home-resume timer. */
final class RefreshPolicy {
    static final int MAX_CONCURRENT = 3;
    static boolean hasCapacity(int running) { return running < MAX_CONCURRENT; }
    static boolean isBusy(Progress p) { return p != null && p.loading; }
    static boolean matchesVisit(Account current, int slot, String revision) {
        return current != null && current.slot == slot && current.revision.equals(revision);
    }
}
