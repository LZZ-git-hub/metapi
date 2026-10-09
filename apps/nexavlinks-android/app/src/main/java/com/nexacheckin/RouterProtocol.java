package com.nexacheckin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.json.JSONObject;

/** Provider-specific, fail-closed response interpretation. No balance-delta or HTTP-200 success guesses. */
final class RouterProtocol {
    static JSONObject user(JSONObject body, String expectedId) throws Exception {
        if (!Boolean.TRUE.equals(body.opt("success")) || !(body.opt("data") instanceof JSONObject))
            throw new Exception("原站未确认登录，请打开原站重新登录或验证");
        JSONObject user = body.getJSONObject("data");
        String id = Policy.userId(user);
        if (expectedId != null && !expectedId.isEmpty() && !expectedId.equals(id)) throw new Exception("站点账号身份不匹配，已停止操作");
        return user;
    }
    static Boolean checked(JSONObject user) {
        if (user.opt("checked_in_today") instanceof Boolean) return (Boolean) user.opt("checked_in_today");
        // AgentRouter's checked_in flag means a reward was granted NOW; false is not proof of missing today's reward.
        return Boolean.TRUE.equals(user.opt("checked_in")) ? Boolean.TRUE : null;
    }
    static boolean claimConfirmed(JSONObject body) {
        return Boolean.TRUE.equals(body.opt("success"));
    }
    static Progress snapshot(Account a, JSONObject user, String day) {
        Progress p = new Progress(day); p.checked = checked(user); p.syncedAt = System.currentTimeMillis();
        if (a.routerUnit.isEmpty()) { p.warning = "未取得原站额度换算单位，暂不展示金额"; return p; }
        try {
            BigDecimal unit = Policy.cost(a.routerUnit);
            if (unit.signum() <= 0) throw new Exception();
            p.balance = Policy.cost(user.opt("quota")).divide(unit, 8, RoundingMode.HALF_UP);
            p.totalCost = Policy.cost(user.opt("used_quota")).divide(unit, 8, RoundingMode.HALF_UP);
        } catch (Exception e) { p.warning = "原站额度数据异常，未伪造金额"; }
        return p;
    }
}
