package com.nexacheckin;

import java.math.BigDecimal;
import org.json.JSONObject;

/** Opaque credentials: never decode JWT payloads or infer identity from tokens. */
final class TokenPair {
    final String access, refresh;
    final long expiresAt;
    TokenPair(String access, String refresh, long expiresAt) {
        this.access = token(access);
        this.refresh = refresh.isEmpty() ? "" : token(refresh);
        if (expiresAt < 0 || expiresAt > 9007199254740991L) throw new IllegalArgumentException("无效的凭证到期时间");
        this.expiresAt = expiresAt;
    }
    static String token(String value) {
        if (value == null || value.isEmpty() || value.length() > 16384
                || value.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new IllegalArgumentException("无效的登录令牌");
        return value;
    }
    static TokenPair fromRefresh(JSONObject data, long now) throws Exception {
        try {
            if (!"Bearer".equalsIgnoreCase(data.getString("token_type"))) throw new Exception();
            long seconds = new BigDecimal(data.get("expires_in").toString()).longValueExact();
            if (seconds <= 0 || now <= 0) throw new Exception();
            long expires = Math.addExact(now, Math.multiplyExact(seconds, 1000L));
            String refresh = token(data.getString("refresh_token"));
            return new TokenPair(data.getString("access_token"), refresh, expires);
        } catch (Exception e) { throw new Exception("原站续期响应无效，请重新登录；未使用旧令牌继续查询"); }
    }
    static TokenPair fromStorage(JSONObject data) throws Exception {
        String access = data.getString("access_token"), refresh = data.optString("refresh_token", "");
        String expiry = data.optString("expires_at", "");
        return new TokenPair(access, refresh, expiry.isEmpty() ? 0 : new BigDecimal(expiry).longValueExact());
    }
    boolean same(TokenPair other) {
        return other != null && access.equals(other.access) && refresh.equals(other.refresh) && expiresAt == other.expiresAt;
    }
}
