package com.nexacheckin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One rotation per explicit query; no retry or fallback to an old access token on failure. */
final class Renewal {
    interface Exchange { JSONObject refresh(String token) throws Exception; }
    interface Store { void save(Account expected, Account replacement) throws Exception; }
    static Account renew(Account a, Exchange exchange, Store store, LongSupplier clock) throws Exception {
        if (a.site != Site.NEXA) throw new Exception("此站点使用网页登录会话，不支持 Nexa 令牌续期");
        if (a.refreshToken.isEmpty()) return a; // Legacy accounts remain usable, but cannot renew.
        TokenPair pair = TokenPair.fromRefresh(exchange.refresh(a.refreshToken), clock.getAsLong());
        Account next = new Account(a.slot, UUID.randomUUID().toString(), a.label,
            pair.access, a.userId, a.userJson, pair.refresh, pair.expiresAt);
        // Rotation may invalidate the previous refresh token. Persist BEFORE any further GET.
        // userId stays immutable; Progress.read verifies /auth/me before displaying any data.
        store.save(a, next);
        return next;
    }
    static List<Account> replace(List<Account> current, Account expected, Account next) throws Exception {
        if (expected.site != next.site || expected.slot != next.slot || !expected.userId.equals(next.userId))
            throw new Exception("续期账号身份不匹配，未更改账号");
        List<Account> result = new ArrayList<>(current);
        for (int i = 0; i < result.size(); i++) {
            Account a = result.get(i);
            if (a.site == expected.site && a.slot == expected.slot && a.revision.equals(expected.revision) && a.userId.equals(expected.userId)) {
                result.set(i, next); return result;
            }
        }
        throw new Exception("账号已变化，未覆盖新的登录状态");
    }
}
