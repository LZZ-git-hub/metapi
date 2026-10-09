package com.nexacheckin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SubscriptionTest {
    private JSONObject upstream() throws Exception {
        JSONObject group = new JSONObject().put("name", "测试套餐").put("status", "active").put("billing_type", "quota")
            .put("subscription_only", true).put("hourly_limit_usd", JSONObject.NULL)
            .put("daily_limit_usd", "10.5").put("weekly_limit_usd", "50").put("monthly_limit_usd", "200");
        return new JSONObject().put("id", 7).put("user_id", 123).put("status", "active")
            .put("expires_at", "2030-10-01T00:00:00Z").put("group", group)
            .put("daily_usage_usd", "1.2").put("weekly_usage_usd", "11.2").put("monthly_usage_usd", "22.3");
    }
    private Subscription read(JSONObject j) throws Exception { return Subscription.read(new JSONArray().put(j), "123").get(0); }
    private Account account() { return new Account(1,"r","test","dummy","123","{\"id\":123}"); }
    private JSONObject response(String path) throws Exception {
        if (path.equals("/auth/me")) return new JSONObject().put("id",123).put("balance","100");
        if (path.equals("/check-in")) return new JSONObject().put("checked_in_today",true);
        return new JSONObject().put("today_actual_cost","2").put("total_actual_cost","500");
    }
    @Test public void periodicLimitsAreDisplayedSeparatelyAndNeverAddedToWallet() throws Exception {
        Subscription s = read(upstream());
        assertEquals(3, s.quotas.size());
        assertEquals("9.3", s.quotas.get(0).remaining().toPlainString());
        assertEquals("38.8", s.quotas.get(1).remaining().toPlainString());
        assertTrue(s.quotaDescription().contains("日额度")); assertTrue(s.quotaDescription().contains("周额度"));
        Progress p = new Progress("2026-09-30"); p.balance = new BigDecimal("100"); p.subscriptions = Collections.singletonList(s);
        Totals t = Totals.of(Collections.singletonList(account()), Collections.singletonMap(1,p), p.day);
        assertEquals("100", t.balance.toPlainString());
    }
    @Test public void weeklyOverrideAppliesOnlyToSubscriptionOnlyGroups() throws Exception {
        JSONObject j = upstream().put("weekly_limit_usd","80");
        assertEquals("80", read(j).quotas.get(1).limit.toPlainString());
        j.getJSONObject("group").put("subscription_only",false);
        assertEquals("50", read(j).quotas.get(1).limit.toPlainString());
    }
    @Test public void zeroLimitIsNotUnlimitedAndOveruseClampsRemaining() throws Exception {
        JSONObject j = upstream(); j.getJSONObject("group").put("daily_limit_usd",0);
        assertEquals(BigDecimal.ZERO, read(j).quotas.get(0).remaining());
        j.getJSONObject("group").put("daily_limit_usd",1);
        assertEquals(BigDecimal.ZERO, read(j).quotas.get(0).remaining());
    }
    @Test public void missingUsageIsUnknownRatherThanZero() throws Exception {
        JSONObject j = upstream(); j.remove("daily_usage_usd");
        Subscription.Quota q = read(j).quotas.get(0);
        assertNull(q.used); assertNull(q.remaining()); assertNotNull(q.limit);
    }
    @Test public void malformedLimitCannotInventAvailability() throws Exception {
        JSONObject j = upstream(); j.getJSONObject("group").put("daily_limit_usd","NaN");
        Subscription s = read(j); assertNull(s.quotas.get(0).limit); assertNull(s.quotas.get(0).remaining());
        assertTrue(s.quotaDescription().contains("—"));
    }
    @Test public void emptyArrayAndMissingGroupAreDifferent() throws Exception {
        assertTrue(Subscription.read(new JSONArray(), "123").isEmpty());
        JSONObject j = upstream(); j.remove("group");
        assertEquals("unknown", read(j).mode); assertTrue(read(j).quotaDescription().contains("未取得"));
    }
    @Test public void explicitUnlimitedAndBalanceBillingAreNotMadeUpBalances() throws Exception {
        JSONObject j = upstream();
        for (String period : Subscription.PERIODS) j.getJSONObject("group").put(period + "_limit_usd", JSONObject.NULL);
        assertEquals("unlimited", read(j).mode);
        j.getJSONObject("group").put("billing_type","balance").put("subscription_only",false);
        assertEquals("balance", read(j).mode); assertTrue(read(j).quotas.isEmpty());
    }
    @Test public void unavailableOrExpiredSubscriptionsAreLabelled() throws Exception {
        JSONObject j = upstream();
        long expiry = Instant.parse("2030-10-01T00:00:00Z").toEpochMilli();
        assertEquals("已过期", read(j).state(expiry));
        j.getJSONObject("group").put("status","disabled");
        assertEquals("分组不可用", read(j).state(expiry - 1000));
        j.put("status","suspended"); assertEquals("已暂停",read(j).state(expiry - 1000));
    }
    @Test public void explicitNoExpiryAndUnknownExpiryRemainDistinct() throws Exception {
        JSONObject j = upstream().put("expires_at",JSONObject.NULL); assertEquals(0,read(j).expiresAt);
        j.remove("expires_at"); assertEquals(-1,read(j).expiresAt);
        j.put("expires_at","bad-date"); assertEquals(-1,read(j).expiresAt);
    }
    @Test public void wrongIdentityAndDuplicateSubscriptionRejected() throws Exception {
        JSONObject j = upstream().put("user_id",456);
        assertThrows(Exception.class, () -> read(j));
        JSONObject valid = upstream();
        assertThrows(Exception.class, () -> Subscription.read(new JSONArray().put(valid).put(valid),"123"));
    }
    @Test public void snapshotRoundtripPreservesDecimalAndOnlySanitizedFields() throws Exception {
        JSONObject j = upstream().put("internal_secret","must-not-cache");
        j.put("daily_usage_usd","0.123456789123456789");
        List<Subscription> before = Subscription.read(new JSONArray().put(j),"123");
        JSONArray saved = Subscription.json(before);
        assertFalse(saved.toString().contains("internal_secret")); assertFalse(saved.toString().contains("must-not-cache"));
        List<Subscription> after = Subscription.restore(new JSONArray(saved.toString()));
        assertEquals(before.get(0).quotas.get(0).used, after.get(0).quotas.get(0).used);
        Progress p = new Progress("2026-09-30"); p.subscriptions = before;
        assertEquals(before.get(0).name, Progress.from(new JSONObject(p.json().toString())).subscriptions.get(0).name);
        assertNull(Progress.from(new Progress("2026-09-30").json()).subscriptions);
        p.subscriptions = Collections.emptyList(); assertTrue(Progress.from(p.json()).subscriptions.isEmpty());
    }
    @Test public void oneAdditionalReadOnlyQueryPerManualAccountRefresh() {
        AtomicInteger calls = new AtomicInteger();
        Progress p = Progress.read(account(), (path,credential) -> { calls.incrementAndGet(); return response(path); },
            credential -> { calls.incrementAndGet(); return new JSONArray(); }, () -> "2026-09-30");
        assertEquals(4,calls.get()); assertTrue(p.subscriptions.isEmpty()); assertNotNull(p.balance);
    }
    @Test public void identityFailureDoesNotQuerySubscriptions() {
        Progress p = Progress.read(account(), (path,credential) -> new JSONObject().put("id",456),
            credential -> { fail("must not request other user's subscriptions"); return null; }, () -> "2026-09-30");
        assertNull(p.subscriptions); assertNull(p.balance);
    }
    @Test public void subscriptionFailureDoesNotEraseWalletOrLeakResponse() {
        Progress p = Progress.read(account(), (path,credential) -> response(path),
            credential -> { throw new Exception("secret upstream text"); }, () -> "2026-09-30");
        assertNull(p.subscriptions); assertNotNull(p.balance); assertNotNull(p.totalCost);
        assertTrue(p.warning.contains("订阅")); assertFalse(p.warning.contains("secret"));
    }
    @Test public void progressBarUsesDecimalRatioAndRetainsExactAmounts() {
        Subscription.Quota q = new Subscription.Quota("daily", new BigDecimal("1.2345"), new BigDecimal("5"));
        assertEquals(2469, q.usedBasisPoints()); assertEquals("已用 24.69%", q.usageLabel());
        assertEquals("3.7655", q.remaining().toPlainString());
    }
    @Test public void progressBarUnknownDoesNotDrawAnEmptyZeroBar() {
        Subscription.Quota q = new Subscription.Quota("daily", null, BigDecimal.TEN);
        assertEquals(-1, q.usedBasisPoints()); assertEquals("用量待核实", q.usageLabel());
        assertEquals(-1, new Subscription.Quota("daily", BigDecimal.ONE, null).usedBasisPoints());
    }
    @Test public void progressBarClampsOveruseAndHandlesZeroLimit() {
        Subscription.Quota over = new Subscription.Quota("daily", new BigDecimal("11"), BigDecimal.TEN);
        assertEquals(10000, over.usedBasisPoints()); assertEquals("已超出上限", over.usageLabel());
        assertEquals(BigDecimal.ZERO, over.remaining());
        Subscription.Quota zero = new Subscription.Quota("daily", BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(10000, zero.usedBasisPoints()); assertEquals("额度为 0", zero.usageLabel());
    }
    @Test public void emptyUsageAndFullUsageHaveStablePercentageLabels() {
        Subscription.Quota empty = new Subscription.Quota("daily", BigDecimal.ZERO, BigDecimal.TEN);
        assertEquals(0, empty.usedBasisPoints()); assertEquals("已用 0%", empty.usageLabel());
        Subscription.Quota full = new Subscription.Quota("daily", BigDecimal.TEN, BigDecimal.TEN);
        assertEquals(10000, full.usedBasisPoints()); assertEquals("已用 100%", full.usageLabel());
    }
    @Test public void hourlyWindowAndMultipleSubscriptionsSupported() throws Exception {
        JSONObject first = upstream(); first.getJSONObject("group").put("hourly_limit_usd", "3"); first.put("hourly_usage_usd","1");
        JSONObject second = upstream().put("id",8);
        List<Subscription> values = Subscription.read(new JSONArray().put(first).put(second),"123");
        assertEquals(2,values.size()); assertEquals("hourly",values.get(0).quotas.get(0).period);
        assertEquals("2",values.get(0).quotas.get(0).remaining().toPlainString());
    }
}
