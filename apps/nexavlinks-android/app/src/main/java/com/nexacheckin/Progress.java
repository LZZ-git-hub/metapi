package com.nexacheckin;

import java.math.BigDecimal;
import java.util.function.Supplier;
import org.json.JSONObject;

/** A dated snapshot, never a claim that cached values are current. */
final class Progress {
    interface Reader { JSONObject get(String path, String credential) throws Exception; }
    interface SubscriptionReader { org.json.JSONArray get(String credential) throws Exception; }
    final String day;
    java.util.List<Subscription> subscriptions; // null = unknown/failed, empty = confirmed no subscriptions
    BigDecimal cost, balance, totalCost;
    Boolean checked, eligible; // eligible is the server's usage-qualification verdict, not local 0.4 arithmetic
    String warning = "";
    boolean loading, checkinPending;
    long syncedAt, requestedAt;
    Progress(String day) { this.day = day; }
    boolean dailyValid(String today) { return day.equals(today); }
    boolean checkinValid(String today) { return dailyValid(today) && !checkinPending; }
    Progress copy() {
        Progress p = new Progress(day);
        p.cost = cost; p.balance = balance; p.totalCost = totalCost; p.checked = checked; p.eligible = eligible;
        p.subscriptions = subscriptions;
        p.warning = warning; p.checkinPending = checkinPending;
        p.syncedAt = syncedAt; p.requestedAt = requestedAt;
        return p;
    }
    private void warn(String value) { warning += (warning.isEmpty() ? "" : "\n") + value; }
    static Progress readBalance(Account account) {
        Progress p = new Progress(Policy.day());
        if (!account.site.balanceOnly()) { p.warning = "站点类型不匹配，未查询余额"; return p; }
        try {
            JSONObject user = Api.me(account.site, account.credential);
            if (!account.userId.equals(Policy.userId(user)))
                throw new Exception("登录身份与绑定账号不一致，请重新登录");
            p.balance = Policy.balance(user.opt("balance"));
            p.syncedAt = System.currentTimeMillis();
        } catch (Exception e) { p.warning = e.getMessage() == null ? "余额查询失败，请稍后重试" : e.getMessage(); }
        return p;
    }
    static Progress read(Account account) { return read(account, Api::get, Api::subscriptions, Policy::day); }
    static Progress read(Account account, Reader reader, Supplier<String> today) {
        return read(account, reader, null, today);
    }
    static Progress read(Account account, Reader reader, SubscriptionReader subscriptionReader, Supplier<String> today) {
        Progress p = new Progress(today.get());
        if (account.site != Site.NEXA) { p.warning = "站点类型不匹配，未发送 Nexa 请求"; return p; }
        JSONObject user;
        try {
            user = reader.get("/auth/me", account.credential);
            if (!account.userId.equals(Policy.userId(user)))
                throw new Exception("登录身份与绑定账号不一致，请重新绑定");
        } catch (Exception e) { p.warn(e.getMessage() == null ? "身份查询失败" : e.getMessage()); return p; }
        try { p.balance = Policy.balance(user.opt("balance")); }
        catch (Exception e) { p.warn("余额：未取得有效数据"); }
        try {
            JSONObject stats = reader.get("/usage/dashboard/stats", account.credential);
            try { p.cost = Policy.cost(stats.opt("today_actual_cost")); }
            catch (Exception e) { p.warn("今日消费：未取得有效数据"); }
            // Actual deduction, not total_cost (standard billing) or a local estimate.
            try { p.totalCost = Policy.cost(stats.opt("total_actual_cost")); }
            catch (Exception e) { p.warn("累计消费：原站未返回有效数据"); }
        } catch (Exception e) { p.warn("消费：" + e.getMessage()); }
        try {
            JSONObject checkin = reader.get("/check-in", account.credential);
            p.checked = Policy.checked(checkin.opt("checked_in_today"));
            // The site's UI uses eligible=false for CHECKIN_USAGE_REQUIRED. Never infer eligibility from today's cost alone.
            Object eligible = checkin.opt("eligible");
            p.eligible = eligible instanceof Boolean ? (Boolean) eligible : null;
        } catch (Exception e) { p.warn("签到：" + e.getMessage()); }
        if (subscriptionReader != null) {
            try { p.subscriptions = Subscription.read(subscriptionReader.get(account.credential), account.userId); }
            catch (Exception e) { p.warn("订阅：查询失败或数据异常，未取得额度"); }
        }
        p.syncedAt = System.currentTimeMillis();
        if (!p.day.equals(today.get())) {
            p.cost = null; p.checked = null; p.eligible = null; p.warn("已跨天，请手动刷新今日数据");
        }
        return p;
    }
    JSONObject json() throws Exception {
        // Store decimal text: Android's JSON parser otherwise round-trips numbers through double.
        return new JSONObject().put("day", day).put("cost", cost == null ? null : cost.toPlainString())
            .put("balance", balance == null ? null : balance.toPlainString())
            .put("totalCost", totalCost == null ? null : totalCost.toPlainString())
            .put("checked", checked).put("eligible", eligible).put("warning", warning)
            .put("subscriptions", subscriptions == null ? null : Subscription.json(subscriptions))
            .put("syncedAt", syncedAt).put("requestedAt", requestedAt).put("checkinPending", checkinPending);
    }
    static Progress from(JSONObject j) throws Exception {
        String day = j.getString("day");
        java.time.LocalDate.parse(day);
        Progress p = new Progress(day);
        if (j.has("cost") && !j.isNull("cost")) p.cost = Policy.cost(j.get("cost"));
        if (j.has("balance") && !j.isNull("balance")) p.balance = Policy.balance(j.get("balance"));
        if (j.has("totalCost") && !j.isNull("totalCost")) p.totalCost = Policy.cost(j.get("totalCost"));
        if (j.has("checked") && !j.isNull("checked")) p.checked = Policy.checked(j.get("checked"));
        p.eligible = j.opt("eligible") instanceof Boolean ? (Boolean) j.opt("eligible") : null;
        if (j.has("subscriptions") && !j.isNull("subscriptions")) p.subscriptions = Subscription.restore(j.getJSONArray("subscriptions"));
        p.warning = j.optString("warning", ""); p.checkinPending = j.optBoolean("checkinPending");
        p.syncedAt = Math.max(0, j.optLong("syncedAt")); p.requestedAt = Math.max(0, j.optLong("requestedAt"));
        return p;
    }
}
