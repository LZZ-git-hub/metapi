package com.nexacheckin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

/** Same window arithmetic as the site's public subscription UI, not a new network query.
 * These are estimated boundaries from an observed window; never roll them forward or reset usage locally. */
final class QuotaReset {
    static final int UNKNOWN = 0, UNSCHEDULED = 1, RESET = 2, ENDS = 3;
    private static final long HOUR_MS = 3600000L, DAY_MS = 24 * HOUR_MS;
    private static final long MAX_TIME = 253402300799999L;
    final int kind;
    final long at;
    private QuotaReset(int kind, long at) { this.kind = kind; this.at = at; }
    static QuotaReset unknown() { return new QuotaReset(UNKNOWN, 0); }
    private static long timestamp(Object raw) {
        if (!(raw instanceof String)) return 0;
        try {
            long value = OffsetDateTime.parse((String) raw).toInstant().toEpochMilli();
            return value > 0 && value <= MAX_TIME ? value : 0;
        } catch (Exception e) { return 0; }
    }
    static QuotaReset read(JSONObject subscription, JSONObject group, String period) {
        try {
            long expires = timestamp(subscription.opt("expires_at"));
            long starts = timestamp(subscription.opt("starts_at"));
            // A one-day-or-shorter subscription's daily quota ends with the plan, not after another 24h.
            if ("daily".equals(period) && starts > 0 && expires > starts && expires - starts <= DAY_MS)
                return new QuotaReset(ENDS, expires);
            String key = period + "_window_start";
            if (!subscription.has(key)) return unknown();
            if (subscription.isNull(key)) return new QuotaReset(UNSCHEDULED, 0);
            long window = timestamp(subscription.opt(key));
            if (window == 0) return unknown();
            long hours;
            switch (period) {
                case "hourly":
                    Object raw = group.opt("hourly_reset_hours");
                    // The site defaults a missing/zero hourly interval to one hour.
                    if (raw == null || raw == JSONObject.NULL) hours = 1;
                    else if (raw instanceof Number || raw instanceof String) hours = new BigDecimal(raw.toString()).longValueExact();
                    else return unknown();
                    if (hours == 0) hours = 1;
                    if (hours < 0) return unknown();
                    break;
                case "daily": hours = 24; break;
                case "weekly": hours = 168; break;
                case "monthly": hours = 720; break; // Rolling 30 days, NOT a calendar month.
                default: return unknown();
            }
            long next = Math.addExact(window, Math.multiplyExact(hours, HOUR_MS));
            if (next <= 0 || next > MAX_TIME) return unknown();
            if (expires > 0 && expires <= next) return new QuotaReset(ENDS, expires);
            return new QuotaReset(RESET, next);
        } catch (Exception e) { return unknown(); }
    }
    boolean needsRefresh(long now) { return (kind == RESET || kind == ENDS) && at <= now; }
    String description(boolean active, long now) {
        if (!active) return "订阅未生效 · 不展示下次重置";
        if (kind == UNKNOWN) return "重置时间未取得";
        if (kind == UNSCHEDULED) return "周期起点未提供 · 重置时间待定";
        if (needsRefresh(now)) return "已过周期边界 · 请刷新核实额度";
        String time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(Policy.ZONE).format(Instant.ofEpochMilli(at));
        return kind == ENDS ? "到期结束 " + time + " · 不再重置" : "预计重置 " + time;
    }
    JSONObject json() throws Exception { return new JSONObject().put("kind", kind).put("at", at); }
    static QuotaReset restore(JSONObject j) {
        if (j == null) return unknown();
        try {
            int kind = j.getInt("kind"); long at = j.getLong("at");
            if (kind < UNKNOWN || kind > ENDS || at < 0 || at > MAX_TIME) return unknown();
            if ((kind == RESET || kind == ENDS) != (at > 0)) return unknown();
            return new QuotaReset(kind, at);
        } catch (Exception e) { return unknown(); }
    }
}
