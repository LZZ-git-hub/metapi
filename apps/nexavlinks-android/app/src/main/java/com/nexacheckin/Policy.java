package com.nexacheckin;

import java.net.URI;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;
import org.json.JSONObject;

final class Policy {
    static final String ORIGIN = "https://asia.nexavlinks.com";
    private static final String HOST = URI.create(ORIGIN).getHost();
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static String day() { return day(Instant.now()); }
    static String day(Instant now) { return now.atZone(ZONE).toLocalDate().toString(); }
    static boolean siteUrl(String value) {
        try {
            URI u = new URI(value);
            return "https".equalsIgnoreCase(u.getScheme()) && HOST.equalsIgnoreCase(u.getHost())
                && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }
    static String credential(String input) {
        if (input == null || input.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("登录凭证为空或格式不正确");
        String value = input.trim().replaceFirst("(?i)^(Bearer|Cookie):?\\s+", "");
        if (value.isEmpty() || value.length() > 16384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("登录凭证为空或格式不正确");
        return value;
    }
    // A padded bearer token may contain '='. Only a complete name=value pair is a cookie.
    static boolean cookie(String value) {
        return value.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+=[^=;\\s][^;\\s]*(?:;.*)?");
    }
    static String profileSuffix(String packageName, String processName) {
        if (packageName.equals(processName)) return null;
        String prefix = packageName + ":account";
        if (!processName.matches(Pattern.quote(prefix) + "[1-9]"))
            throw new IllegalStateException("Invalid profile");
        return "account-" + processName.substring(prefix.length());
    }
    static String userId(JSONObject user) throws Exception {
        Object id = user.opt("id");
        if (!(id instanceof Number) && !(id instanceof String)) throw new Exception("无法核实账号身份");
        String text = id.toString();
        if (!Pattern.matches("[1-9][0-9]*", text)) throw new Exception("无法核实账号身份");
        return text;
    }
    static BigDecimal cost(Object raw) throws Exception {
        if (!(raw instanceof Number) && !(raw instanceof String)) throw new Exception("消费数据格式异常");
        try {
            BigDecimal value = new BigDecimal(raw.toString().trim());
            if (value.signum() < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new Exception("消费数据格式异常"); }
    }
    static BigDecimal balance(Object raw) throws Exception {
        // A negative upstream balance is a real debt, not an invalid consumption value.
        if (!(raw instanceof Number) && !(raw instanceof String)) throw new Exception("余额数据格式异常");
        try { return new BigDecimal(raw.toString().trim()); }
        catch (NumberFormatException e) { throw new Exception("余额数据格式异常"); }
    }
    static boolean checked(Object raw) throws Exception {
        if (!(raw instanceof Boolean)) throw new Exception("签到数据格式异常");
        return (Boolean) raw;
    }
    static boolean acceptResult(String currentRevision, String requestRevision, String requestDay, String nowDay) {
        return currentRevision.equals(requestRevision) && requestDay.equals(nowDay);
    }
}
