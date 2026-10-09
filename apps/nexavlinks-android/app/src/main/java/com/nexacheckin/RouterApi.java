package com.nexacheckin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Cookie session is sent only to its fixed router origin. No OAuth credentials or WAF bypass. */
final class RouterApi {
    interface Transport { JSONObject request(Account a, boolean claim) throws Exception; }
    private static final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r,"router-deadline"); t.setDaemon(true); return t;
    });
    static Progress read(Account a, boolean claim) { return read(a, claim, RouterApi::request, Policy.day()); }
    static Progress read(Account a, boolean claim, Transport transport, String day) {
        try {
            if (!a.site.router()) throw new Exception("此站点需要手动完成原站验证");
            JSONObject user = RouterProtocol.user(transport.request(a, false), a.userId);
            boolean confirmed = false;
            if (claim && a.site == Site.ANY && !Boolean.TRUE.equals(RouterProtocol.checked(user))) {
                confirmed = RouterProtocol.claimConfirmed(transport.request(a, true));
                // Verify identity again after the one and only claim attempt, never retry a POST.
                user = RouterProtocol.user(transport.request(a, false), a.userId);
            }
            Progress p = RouterProtocol.snapshot(a, user, day);
            if (confirmed && Boolean.FALSE.equals(p.checked)) {
                p.checked = null;
                p.warning = "签到响应与查询状态不一致，请在原站核实";
            } else if (confirmed) p.checked = true;
            if (claim && !Boolean.TRUE.equals(p.checked))
                p.warning += (p.warning.isEmpty() ? "" : "\n") + "已尝试签到，但原站未明确确认今日结果；请查看原站";
            return p;
        } catch (Exception e) {
            Progress p = new Progress(day); p.warning = e.getMessage() == null ? "原站操作失败，请打开原站检查" : e.getMessage();
            return p;
        }
    }
    private static JSONObject request(Account a, boolean claim) throws Exception {
        if (!a.site.router() || claim && a.site != Site.ANY) throw new Exception("不支持的签到请求");
        String path = claim ? "/api/user/sign_in" : "/api/user/self";
        HttpsURLConnection c = (HttpsURLConnection)new URL(a.site.origin + path).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(12000); c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Cookie", a.credential);
        c.setRequestProperty("New-Api-User", a.userId); c.setRequestProperty("Origin", a.site.origin);
        c.setRequestProperty("Referer", a.site.origin + "/console");
        if (!a.userAgent.isEmpty()) c.setRequestProperty("User-Agent", a.userAgent);
        if (claim) {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(0);
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        }
        ScheduledFuture<?> timer = deadlines.schedule(c::disconnect, 20, TimeUnit.SECONDS);
        try {
            if (claim) c.getOutputStream().close();
            int status = c.getResponseCode();
            if (status == 401 || status == 403) throw new Exception("登录过期或需要原站验证，请在管理中重新登录");
            if (status != 200) throw new Exception("原站请求失败（HTTP " + status + "），未自动重试");
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("application/json"))
                throw new Exception("原站要求浏览器验证，请打开原站处理后重试");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[4096];
            try (InputStream in = c.getInputStream()) {
                int count;
                while ((count = in.read(block)) != -1) {
                    if (bytes.size() + count > 1024*1024) throw new Exception("原站响应过大");
                    bytes.write(block,0,count);
                }
            }
            try { return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new Exception("原站未返回有效 JSON，结果待核实"); }
        } catch (java.io.IOException e) { throw new Exception("网络异常，签到结果未确认；未自动重试"); }
        finally { timer.cancel(false); c.disconnect(); }
    }
}
