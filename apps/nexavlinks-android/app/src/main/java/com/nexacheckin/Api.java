package com.nexacheckin;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

final class Api {
    private static final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "api-deadline"); thread.setDaemon(true); return thread;
    });
    static JSONObject get(String path, String credential) throws Exception {
        if (!path.equals("/auth/me") && !path.equals("/usage/dashboard/stats") && !path.equals("/check-in"))
            throw new IllegalArgumentException("Unsupported endpoint");
        return object(request(path, credential, null));
    }
    static JSONArray subscriptions(String credential) throws Exception {
        Object data = request("/subscriptions", credential, null);
        if (!(data instanceof JSONArray)) throw new Exception("原站未返回有效订阅列表");
        return (JSONArray) data;
    }
    static JSONObject me(Site site, String credential) throws Exception {
        return object(request(site, "/auth/me", credential, null));
    }
    private static JSONObject object(Object data) throws Exception {
        if (!(data instanceof JSONObject)) throw new Exception("原站未返回有效数据");
        return (JSONObject) data;
    }
    // The only native POST. No generic public POST API and never a check-in POST.
    static JSONObject refresh(String refreshToken) throws Exception {
        byte[] payload = new JSONObject().put("refresh_token", TokenPair.token(refreshToken))
            .toString().getBytes(StandardCharsets.UTF_8);
        return object(request("/auth/refresh", null, payload));
    }
    private static Object request(String path, String credential, byte[] payload) throws Exception {
        return request(Site.NEXA, path, credential, payload);
    }
    private static Object request(Site site, String path, String credential, byte[] payload) throws Exception {
        // Congee 只放行余额所在的 /auth/me，不继承 Nexa 的签到、用量、订阅或续期写请求。
        if (site == null) throw new IllegalArgumentException("Unsupported endpoint");
        if (payload == null) {
            if (!site.tokenApiPath(path)) throw new IllegalArgumentException("Unsupported endpoint");
        } else if (site != Site.NEXA || !"/auth/refresh".equals(path)) {
            throw new IllegalArgumentException("Unsupported endpoint");
        }
        HttpsURLConnection c = (HttpsURLConnection) new URL(site.origin + "/api/v1" + path + "?timezone=Asia%2FShanghai").openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12000); c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("X-User-UI-Request", "1");
        if (credential != null) c.setRequestProperty(Policy.cookie(credential) ? "Cookie" : "Authorization",
            Policy.cookie(credential) ? credential : "Bearer " + credential);
        if (payload != null) {
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(payload.length); // No redirect/replay of a rotating token.
        }
        ScheduledFuture<?> deadline = deadlines.schedule(c::disconnect, 20, TimeUnit.SECONDS);
        try {
            if (payload != null) try (OutputStream output = c.getOutputStream()) { output.write(payload); }
            int status = c.getResponseCode();
            if (payload != null && (status == 400 || status == 401 || status == 403))
                throw new Exception("续期凭证失效或被拒绝，请在管理中重新登录");
            if (status == 401) throw new Exception("登录凭证失效，请在管理中重新登录");
            if (status == 403) throw new Exception("访问被拒绝，请检查凭证或原站访问限制");
            if (status != 200) throw new Exception((payload == null ? "原站查询失败" : "原站续期失败") + "（HTTP " + status + "）");
            byte[] chunk = new byte[4096]; ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream stream = c.getInputStream()) {
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    if (output.size() + n > 1024 * 1024) throw new Exception("原站响应异常");
                    output.write(chunk, 0, n);
                }
            }
            JSONObject body;
            try { body = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)); }
            catch (Exception e) { throw new Exception("原站未返回有效数据，可能需要浏览器验证"); }
            Object code = body.opt("code");
            if (!(code instanceof Number) || ((Number) code).doubleValue() != 0 || !body.has("data") || body.isNull("data"))
                throw new Exception("原站未确认请求成功，请检查登录凭证");
            return body.get("data");
        } catch (java.io.IOException e) {
            throw new Exception(payload == null ? "网络连接失败，请稍后重试"
                : "续期网络异常，结果未确认；未自动重试，若再次失败请重新登录");
        } finally { deadline.cancel(false); c.disconnect(); }
    }
}
