package com.nexacheckin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Local access-token expiry only. Refresh-token lifetime is not supplied by the site. */
final class ExpiryPolicy {
    static final long LEAD_MS = 60 * 60 * 1000L;
    static final int UNKNOWN = -1, VALID = 0, SOON = 1, EXPIRED = 2;
    static int level(long expiresAt, long now) {
        if (expiresAt <= 0) return UNKNOWN;
        if (expiresAt <= now) return EXPIRED;
        return expiresAt - now <= LEAD_MS ? SOON : VALID;
    }
    static long nextCheck(long expiresAt, long now) {
        int state = level(expiresAt, now);
        if (state == VALID) return expiresAt - LEAD_MS;
        if (state == SOON) return expiresAt;
        return 0;
    }
    static long minutesLeft(long expiresAt, long now) {
        return expiresAt <= now ? 0 : (expiresAt - now + 59999) / 60000;
    }
    static boolean shouldNotify(String currentKey, int level, String lastKey, int lastLevel) {
        return level >= SOON && (!currentKey.equals(lastKey) || level > lastLevel);
    }
    static String key(Account a) {
        // Non-secret deduplication metadata, stable across rename but not slot reuse/renewal.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (a.site.origin + ":" + a.slot + ":" + a.userId + ":" + a.expiresAt).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (Exception e) { throw new IllegalStateException("无法计算提醒标识"); }
    }
    static String message(Account a, long now) {
        int state = level(a.expiresAt, now);
        String action = a.refreshToken.isEmpty() ? "请在管理中重新登录。" : "请点击刷新续期。";
        if (state == EXPIRED) return "访问凭证已过期 · " + action;
        if (state == SOON) return "访问凭证即将到期 · 剩余约 " + minutesLeft(a.expiresAt, now) + " 分钟。" + action;
        if (state == UNKNOWN) return "未保存访问凭证到期时间，无法提前提醒。";
        return "";
    }
}
