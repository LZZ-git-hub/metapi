package com.nexacheckin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class Totals {
    BigDecimal balance = BigDecimal.ZERO, used = BigDecimal.ZERO, today = BigDecimal.ZERO;
    int balances, usages, todays, checked, unknown, checkinAccounts;
    static Totals of(List<Account> accounts, Map<Integer, Progress> snapshots, String day) {
        Totals t = new Totals();
        for (Account a : accounts) {
            boolean supportsCheckin = a.site.supportsCheckin();
            if (supportsCheckin) t.checkinAccounts++;
            Progress p = snapshots.get(a.slot);
            if (p == null) { if (supportsCheckin) t.unknown++; continue; }
            if (p.balance != null) { t.balance = t.balance.add(p.balance); t.balances++; }
            if (p.totalCost != null) { t.used = t.used.add(p.totalCost); t.usages++; }
            if (p.dailyValid(day) && p.cost != null) { t.today = t.today.add(p.cost); t.todays++; }
            if (supportsCheckin) {
                int state = CheckinPresentation.state(p, day);
                if (state == CheckinPresentation.UNKNOWN) t.unknown++;
                else if (state == CheckinPresentation.CHECKED) t.checked++;
            }
        }
        return t;
    }
    static String amount(BigDecimal value, int available, int count) {
        // Show known data immediately; the caller labels incomplete coverage as a subtotal.
        return count > 0 && available > 0 ? money(value) : "—";
    }
    static String label(String metric, int available, int count) {
        return metric + (available > 0 && available < count ? "（已知）" : "");
    }
    static String money(BigDecimal value) { return value == null ? "—" : value.stripTrailingZeros().toPlainString(); }
}
