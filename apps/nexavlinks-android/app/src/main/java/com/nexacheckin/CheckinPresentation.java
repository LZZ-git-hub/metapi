package com.nexacheckin;

/** Presentation only: a missing, invalidated or old daily result is never treated as unchecked. */
final class CheckinPresentation {
    static final int UNKNOWN = 0, UNCHECKED = 1, CHECKED = 2;
    static int state(Progress p, String day) {
        if (p == null || !p.checkinValid(day) || p.checked == null) return UNKNOWN;
        return p.checked ? CHECKED : UNCHECKED;
    }
    static String label(int state) {
        return state == CHECKED ? "已签到" : state == UNCHECKED ? "未签到" : "待核实";
    }
    static String action(int state) { return state == CHECKED ? "查看原站" : "去签到"; }
    static Boolean eligibility(Progress p, String day) {
        return state(p, day) == UNCHECKED ? p.eligible : null;
    }
    static String eligibilityLabel(Progress p, String day) {
        if (state(p, day) != UNCHECKED) return "";
        Boolean eligible = eligibility(p, day);
        if (eligible == null) return "签到资格待核实";
        return eligible ? "可签到 · 原站已判定满足消费条件" : "暂不可签到 · 原站判定消费条件未满足";
    }
    static String detailKey(Account a) { return a.site.id + ":" + a.slot + ":" + a.userId; }
    static String shortWarning(Progress p) {
        if (p == null || p.warning.isEmpty()) return "";
        return "查询异常 · 查看原因";
    }
}
