package com.nexacheckin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sanitized immutable subscription snapshot. Period limits overlap; they are NOT wallet balances. */
final class Subscription {
    static final String[] PERIODS = {"hourly", "daily", "weekly", "monthly"};
    static final String[] LABELS = {"小时", "日", "周", "月"};
    final String id, name, status, mode;
    final long expiresAt; // 0 = explicitly no expiry, -1 = missing/invalid upstream expiry
    final List<Quota> quotas;
    static final class Quota {
        final String period;
        final BigDecimal used, limit;
        final QuotaReset reset;
        Quota(String period, BigDecimal used, BigDecimal limit) { this(period, used, limit, QuotaReset.unknown()); }
        Quota(String period, BigDecimal used, BigDecimal limit, QuotaReset reset) {
            this.period = period; this.used = used; this.limit = limit; this.reset = reset;
        }
        BigDecimal remaining() { return used == null || limit == null ? null : limit.subtract(used).max(BigDecimal.ZERO); }
        int usedBasisPoints() {
            if (used == null || limit == null) return -1;
            if (limit.signum() == 0 || used.compareTo(limit) >= 0) return 10000;
            return used.multiply(BigDecimal.valueOf(10000)).divide(limit, 0, java.math.RoundingMode.DOWN).intValue();
        }
        String usageLabel() {
            int value = usedBasisPoints();
            if (value < 0) return "用量待核实";
            if (limit.signum() == 0) return "额度为 0";
            if (used.compareTo(limit) > 0) return "已超出上限";
            return "已用 " + BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString() + "%";
        }
        String label() {
            for (int i = 0; i < PERIODS.length; i++) if (PERIODS[i].equals(period)) return LABELS[i];
            return period;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("period", period).put("used", used == null ? null : used.toPlainString())
                .put("limit", limit == null ? null : limit.toPlainString()).put("reset", reset.json());
        }
    }
    private Subscription(String id, String name, String status, String mode, long expiresAt, List<Quota> quotas) {
        this.id = id; this.name = name; this.status = status; this.mode = mode; this.expiresAt = expiresAt;
        this.quotas = Collections.unmodifiableList(new ArrayList<>(quotas));
    }
    private static BigDecimal number(Object value) {
        try { return Policy.balance(value); } catch (Exception e) { return null; }
    }
    private static String text(JSONObject j, String key, String fallback, int max) {
        Object value = j.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) return fallback;
        String s = ((String) value).trim(); return s.length() <= max ? s : s.substring(0, max);
    }
    private static long expiry(JSONObject j) {
        if (!j.has("expires_at")) return -1;
        if (j.isNull("expires_at")) return 0;
        try { return Instant.parse(j.getString("expires_at")).toEpochMilli(); }
        catch (Exception e) { return -1; }
    }
    static List<Subscription> read(JSONArray data, String userId) throws Exception {
        if (data.length() > 100) throw new Exception("订阅数量异常，未展示不完整数据");
        List<Subscription> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject j = data.getJSONObject(i);
            String id = Policy.userId(j);
            if (!ids.add(id)) throw new Exception("订阅数据重复，未重复统计");
            if (j.has("user_id") && !userId.equals(String.valueOf(j.get("user_id"))))
                throw new Exception("订阅身份不匹配，未展示数据");
            JSONObject group = j.optJSONObject("group");
            String name = text(j, "plan_name", "", 80);
            if (name.isEmpty()) name = text(j, "group_name", "", 80);
            if (name.isEmpty() && group != null) name = text(group, "name", "", 80);
            if (name.isEmpty()) name = "订阅 #" + id;
            String mode = "unknown";
            List<Quota> quotas = new ArrayList<>();
            if (group != null) {
                if (!"active".equals(group.optString("status"))) mode = "unavailable";
                else {
                    boolean subscriptionOnly = Boolean.TRUE.equals(group.opt("subscription_only"));
                    boolean quota = "quota".equals(group.optString("billing_type"))
                        || "subscription".equals(group.optString("subscription_type")) || subscriptionOnly;
                    boolean explicitBalance = "balance".equals(group.optString("billing_type"))
                        || "standard".equals(group.optString("subscription_type"));
                    if (quota) {
                        mode = "quota"; boolean allLimitsKnown = true;
                        for (String period : PERIODS) {
                            JSONObject source = "weekly".equals(period) && subscriptionOnly && j.has("weekly_limit_usd")
                                && !j.isNull("weekly_limit_usd") ? j : group;
                            String key = period + "_limit_usd";
                            if (!source.has(key)) { allLimitsKnown = false; continue; }
                            if (source.isNull(key)) continue; // Explicitly no limit for this period.
                            BigDecimal limit = number(source.opt(key));
                            if (limit == null) { quotas.add(new Quota(period, null, null, QuotaReset.read(j, group, period))); allLimitsKnown = false; continue; }
                            if (limit.signum() < 0) continue; // Upstream uses null/negative as an unbounded period.
                            BigDecimal used = number(j.opt(period + "_usage_usd"));
                            if (used != null && used.signum() < 0) used = null;
                            quotas.add(new Quota(period, used, limit, QuotaReset.read(j, group, period)));
                        }
                        if (quotas.isEmpty()) mode = allLimitsKnown ? "unlimited" : "unknown";
                    } else if (explicitBalance) mode = "balance";
                }
            }
            result.add(new Subscription(id, name, text(j, "status", "unknown", 24), mode, expiry(j), quotas));
        }
        return Collections.unmodifiableList(result);
    }
    String state(long now) {
        if ("active".equals(status) && expiresAt > 0 && expiresAt <= now) return "已过期";
        if ("active".equals(status)) return "unavailable".equals(mode) ? "分组不可用" : "生效中";
        if ("expired".equals(status)) return "已过期";
        if ("revoked".equals(status)) return "已撤销";
        if ("suspended".equals(status)) return "已暂停";
        return "状态待核实";
    }
    String quotaDescription() {
        if ("balance".equals(mode)) return "按账号余额计费，不另加订阅额度";
        if ("unlimited".equals(mode)) return "未设置周期金额上限（仍受分组和订阅有效期限制）";
        if ("unavailable".equals(mode)) return "订阅分组当前不可用";
        if (quotas.isEmpty()) return "未取得订阅额度，请以原站为准";
        StringBuilder s = new StringBuilder();
        for (Quota q : quotas) {
            if (s.length() > 0) s.append('\n');
            s.append(q.label()).append("额度 · 剩余 ").append(Totals.money(q.remaining()))
                .append(" / 上限 ").append(Totals.money(q.limit)).append(" · 已用 ").append(Totals.money(q.used));
        }
        return s.toString();
    }
    JSONObject json() throws Exception {
        JSONArray values = new JSONArray(); for (Quota q : quotas) values.put(q.json());
        return new JSONObject().put("id", id).put("name", name).put("status", status).put("mode", mode)
            .put("expiresAt", expiresAt).put("quotas", values);
    }
    static JSONArray json(List<Subscription> values) throws Exception {
        JSONArray array = new JSONArray(); for (Subscription s : values) array.put(s.json()); return array;
    }
    static List<Subscription> restore(JSONArray array) throws Exception {
        if (array.length() > 100) throw new Exception("Invalid subscription snapshot");
        List<Subscription> values = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject j = array.getJSONObject(i); String id = Policy.userId(j);
            if (!ids.add(id)) throw new Exception("Duplicate subscription snapshot");
            List<Quota> quotas = new ArrayList<>(); JSONArray raw = j.getJSONArray("quotas");
            Set<String> periods = new HashSet<>();
            for (int n = 0; n < raw.length(); n++) {
                JSONObject q = raw.getJSONObject(n); String period = q.getString("period");
                if (!java.util.Arrays.asList(PERIODS).contains(period) || !periods.add(period)) throw new Exception("Invalid quota period");
                quotas.add(new Quota(period, q.isNull("used") ? null : Policy.cost(q.get("used")),
                    q.isNull("limit") ? null : Policy.cost(q.get("limit")), QuotaReset.restore(q.optJSONObject("reset"))));
            }
            values.add(new Subscription(id, text(j,"name","订阅 #" + id,80), text(j,"status","unknown",24),
                text(j,"mode","unknown",24), j.getLong("expiresAt"), quotas));
        }
        return Collections.unmodifiableList(values);
    }
}
