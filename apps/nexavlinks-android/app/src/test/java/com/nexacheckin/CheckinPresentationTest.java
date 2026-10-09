package com.nexacheckin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CheckinPresentationTest {
    private Progress p(Boolean checked) { Progress p = new Progress("2026-09-30"); p.checked = checked; return p; }
    private Account a(int slot, String id) { return new Account(slot,"r","test","dummy",id,"{\"id\":"+id+"}"); }
    @Test public void missingCheckinDoesNotPretendUnchecked() {
        assertEquals(CheckinPresentation.UNKNOWN, CheckinPresentation.state(null,"2026-09-30"));
        assertEquals(CheckinPresentation.UNKNOWN, CheckinPresentation.state(p(null),"2026-09-30"));
        assertEquals("待核实", CheckinPresentation.label(CheckinPresentation.UNKNOWN));
    }
    @Test public void realResultsHaveDistinctLabelsAndActions() {
        assertEquals(CheckinPresentation.CHECKED, CheckinPresentation.state(p(true),"2026-09-30"));
        assertEquals(CheckinPresentation.UNCHECKED, CheckinPresentation.state(p(false),"2026-09-30"));
        assertEquals("已签到", CheckinPresentation.label(CheckinPresentation.CHECKED));
        assertEquals("未签到", CheckinPresentation.label(CheckinPresentation.UNCHECKED));
        assertEquals("查看原站", CheckinPresentation.action(CheckinPresentation.CHECKED));
        assertEquals("去签到", CheckinPresentation.action(CheckinPresentation.UNCHECKED));
    }
    @Test public void crossDayNeverShowsYesterdaysGreenStatus() {
        assertEquals(CheckinPresentation.UNKNOWN, CheckinPresentation.state(p(true),"2026-10-01"));
    }
    @Test public void invalidatedVisitRemainsUnknownEvenWithOldTrueFlag() {
        Progress p = p(true); p.checkinPending = true;
        assertEquals(CheckinPresentation.UNKNOWN, CheckinPresentation.state(p,"2026-09-30"));
    }
    @Test public void loadingDoesNotInventSuccessOrLoseLastKnownStatus() {
        Progress known = p(true); known.loading = true;
        assertEquals(CheckinPresentation.CHECKED, CheckinPresentation.state(known,"2026-09-30"));
        Progress unknown = p(null); unknown.loading = true;
        assertEquals(CheckinPresentation.UNKNOWN, CheckinPresentation.state(unknown,"2026-09-30"));
    }
    @Test public void summaryAndCardUseSameClassification() {
        Map<Integer, Progress> values = new HashMap<>(); values.put(1,p(true)); values.put(2,p(false)); values.put(3,p(null));
        Totals total = Totals.of(Arrays.asList(a(1,"1"),a(2,"2"),a(3,"3")), values, "2026-09-30");
        assertEquals(1,total.checked); assertEquals(1,total.unknown); assertEquals(1,3-total.checked-total.unknown);
    }
    @Test public void collapsedErrorsRemainVisibleWithoutRepeatingTheWholeMessage() {
        Progress p = p(null); p.warning = "network problem details";
        assertFalse(CheckinPresentation.shortWarning(p).isEmpty());
        assertFalse(CheckinPresentation.shortWarning(p).contains("network problem details"));
        assertEquals("", CheckinPresentation.shortWarning(p(true)));
    }
    @Test public void uncheckedAccountDisplaysServerUsageVerdict() {
        Progress p = p(false); p.eligible = true;
        assertTrue(CheckinPresentation.eligibilityLabel(p,"2026-09-30").startsWith("可签到"));
        p.eligible = false;
        assertTrue(CheckinPresentation.eligibilityLabel(p,"2026-09-30").startsWith("暂不可签到"));
        p.eligible = null;
        assertEquals("签到资格待核实",CheckinPresentation.eligibilityLabel(p,"2026-09-30"));
    }
    @Test public void checkedAndUnknownStatusDoNotPromiseAnotherCheckin() {
        Progress p = p(true); p.eligible = true;
        assertEquals("",CheckinPresentation.eligibilityLabel(p,"2026-09-30"));
        p.checked = null;
        assertEquals("",CheckinPresentation.eligibilityLabel(p,"2026-09-30"));
    }
    @Test public void crossDayOrReturnedBrowserDoesNotReuseOldEligibility() {
        Progress p = p(false); p.eligible = true;
        assertNull(CheckinPresentation.eligibility(p,"2026-10-01"));
        p.checkinPending = true;
        assertNull(CheckinPresentation.eligibility(p,"2026-09-30"));
    }
    @Test public void todayPointFourAloneDoesNotProveYesterdayQualification() {
        Progress p = p(false); p.cost = new java.math.BigDecimal("0.4");
        assertNull(CheckinPresentation.eligibility(p,"2026-09-30"));
        assertEquals("签到资格待核实",CheckinPresentation.eligibilityLabel(p,"2026-09-30"));
    }
    @Test public void expandedDetailsSurviveRenameButNotSlotReuse() {
        Account a = a(1,"1");
        assertEquals(CheckinPresentation.detailKey(a),CheckinPresentation.detailKey(a.renamed("new")));
        assertNotEquals(CheckinPresentation.detailKey(a),CheckinPresentation.detailKey(a(1,"2")));
    }
}
