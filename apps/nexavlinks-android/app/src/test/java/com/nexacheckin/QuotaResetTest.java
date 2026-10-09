package com.nexacheckin;

import java.time.Instant;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QuotaResetTest {
    private long at(String value) { return Instant.parse(value).toEpochMilli(); }
    private JSONObject subscription() throws Exception {
        return new JSONObject().put("id", 7).put("user_id", 123).put("status", "active")
            .put("starts_at", "2030-01-01T00:00:00Z").put("expires_at", "2031-01-01T00:00:00Z")
            .put("daily_window_start", "2030-01-31T10:15:00+08:00")
            .put("weekly_window_start", "2030-01-31T10:15:00+08:00")
            .put("monthly_window_start", "2030-01-31T10:15:00+08:00")
            .put("hourly_window_start", "2030-01-31T10:15:00+08:00");
    }
    @Test public void dailyWindowIs24HoursWithTimezoneNotLocalMidnight() throws Exception {
        QuotaReset r = QuotaReset.read(subscription(), new JSONObject(), "daily");
        assertEquals(QuotaReset.RESET, r.kind);
        assertEquals(at("2030-02-01T02:15:00Z"), r.at);
        assertEquals("预计重置 02-01 10:15", r.description(true, at("2030-01-31T03:00:00Z")));
    }
    @Test public void weeklyIsSevenDaysAndMonthlyIsRollingThirtyDays() throws Exception {
        assertEquals(at("2030-02-07T02:15:00Z"), QuotaReset.read(subscription(),new JSONObject(),"weekly").at);
        assertEquals(at("2030-03-02T02:15:00Z"), QuotaReset.read(subscription(),new JSONObject(),"monthly").at);
    }
    @Test public void hourlyUsesSiteIntervalAndDefaultOneHour() throws Exception {
        assertEquals(at("2030-01-31T07:15:00Z"), QuotaReset.read(subscription(),new JSONObject().put("hourly_reset_hours",5),"hourly").at);
        assertEquals(at("2030-01-31T03:15:00Z"), QuotaReset.read(subscription(),new JSONObject(),"hourly").at);
        assertEquals(at("2030-01-31T03:15:00Z"), QuotaReset.read(subscription(),new JSONObject().put("hourly_reset_hours",0),"hourly").at);
    }
    @Test public void invalidHourlyIntervalDoesNotGuess() throws Exception {
        for (Object value : new Object[]{-1, "1.5", true, "NaN", Long.MAX_VALUE}) {
            assertEquals(QuotaReset.UNKNOWN, QuotaReset.read(subscription(),new JSONObject().put("hourly_reset_hours",value),"hourly").kind);
        }
    }
    @Test public void missingNullOrInvalidWindowDoesNotInventReset() throws Exception {
        JSONObject j = subscription(); j.remove("daily_window_start");
        assertEquals(QuotaReset.UNKNOWN, QuotaReset.read(j,new JSONObject(),"daily").kind);
        j.put("daily_window_start",JSONObject.NULL);
        assertEquals(QuotaReset.UNSCHEDULED, QuotaReset.read(j,new JSONObject(),"daily").kind);
        j.put("daily_window_start","2030-01-31 10:15:00"); // No timezone, do not assume one.
        assertEquals(QuotaReset.UNKNOWN, QuotaReset.read(j,new JSONObject(),"daily").kind);
        assertEquals(QuotaReset.UNKNOWN, QuotaReset.read(subscription(),new JSONObject(),"other").kind);
    }
    @Test public void oneDayPlanEndsInsteadOfGettingAnotherDayOfQuota() throws Exception {
        JSONObject j = subscription().put("starts_at","2030-01-31T00:00:00Z").put("expires_at","2030-02-01T00:00:00Z");
        j.remove("daily_window_start");
        QuotaReset r = QuotaReset.read(j,new JSONObject(),"daily");
        assertEquals(QuotaReset.ENDS,r.kind); assertEquals(at("2030-02-01T00:00:00Z"),r.at);
        assertTrue(r.description(true,at("2030-01-31T01:00:00Z")).contains("不再重置"));
    }
    @Test public void expiryBeforeNextWindowEndsQuotaInsteadOfPromisingReset() throws Exception {
        JSONObject j = subscription().put("expires_at","2030-02-01T00:00:00Z");
        QuotaReset r = QuotaReset.read(j,new JSONObject(),"weekly");
        assertEquals(QuotaReset.ENDS,r.kind); assertEquals(at("2030-02-01T00:00:00Z"),r.at);
    }
    @Test public void pastBoundaryDoesNotRollForwardOrReplenishCachedAmounts() throws Exception {
        QuotaReset r = QuotaReset.read(subscription(),new JSONObject(),"daily");
        assertFalse(r.needsRefresh(r.at - 1)); assertTrue(r.needsRefresh(r.at));
        long next = r.at; assertTrue(r.description(true,next + 86400000L).contains("请刷新核实")); assertEquals(next,r.at);
        Subscription.Quota q = new Subscription.Quota("daily",new java.math.BigDecimal("4"),new java.math.BigDecimal("10"),r);
        assertEquals("6",q.remaining().toPlainString());
    }
    @Test public void inactiveSubscriptionNeverPromisesAFutureReset() throws Exception {
        QuotaReset r = QuotaReset.read(subscription(),new JSONObject(),"daily");
        assertTrue(r.description(false,r.at-1000).contains("不展示下次重置"));
    }
    @Test public void noPlanExpiryStillAllowsObservedWindowReset() throws Exception {
        JSONObject j = subscription().put("expires_at",JSONObject.NULL);
        assertEquals(QuotaReset.RESET,QuotaReset.read(j,new JSONObject(),"monthly").kind);
    }
    @Test public void backwardCompatibleSnapshotStoresAbsoluteResetNotRecomputedTime() throws Exception {
        QuotaReset r = QuotaReset.read(subscription(),new JSONObject(),"weekly");
        QuotaReset read = QuotaReset.restore(new JSONObject(r.json().toString()));
        assertEquals(r.at,read.at); assertEquals(r.kind,read.kind);
        assertEquals(QuotaReset.UNKNOWN,QuotaReset.restore(null).kind);
        assertEquals(QuotaReset.UNKNOWN,QuotaReset.restore(new JSONObject().put("kind",2).put("at",0)).kind);
    }
    @Test public void subscriptionIntegrationPreservesResetAndSupportsOldCache() throws Exception {
        JSONObject j = subscription().put("daily_usage_usd","1").put("group",new JSONObject().put("status","active")
            .put("billing_type","quota").put("daily_limit_usd","10"));
        List<Subscription> values = Subscription.read(new JSONArray().put(j),"123");
        assertEquals(QuotaReset.RESET,values.get(0).quotas.get(0).reset.kind);
        JSONArray saved = Subscription.json(values);
        assertEquals(values.get(0).quotas.get(0).reset.at,Subscription.restore(new JSONArray(saved.toString())).get(0).quotas.get(0).reset.at);
        saved.getJSONObject(0).getJSONArray("quotas").getJSONObject(0).remove("reset");
        assertEquals(QuotaReset.UNKNOWN,Subscription.restore(saved).get(0).quotas.get(0).reset.kind);
    }
}
