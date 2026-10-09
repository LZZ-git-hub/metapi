package com.nexacheckin;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.TextView;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Uses the existing slot WebView. Never reads passwords or installs a native JS bridge. */
final class BrowserLogin {
    private final Activity activity;
    private final WebView web;
    private final TextView status;
    private final LoginRequest request;
    private final Consumer<Account> complete;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean active = true, ready, busy, closed;
    private int epoch;
    private TokenPair rejected;
    private long retryAt;
    private final Runnable poll = this::capture;
    BrowserLogin(Activity activity, WebView web, TextView status, LoginRequest request, Consumer<Account> complete) {
        if (!request.site.tokenSession()) throw new IllegalArgumentException("不支持的令牌登录站点");
        this.activity = activity; this.web = web; this.status = status; this.request = request; this.complete = complete;
    }
    void start() {
        // An unused slot may contain a cancelled login. Always begin a fresh session.
        status.setText("正在清理此账号的旧会话…");
        web.clearCache(true); WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(removed -> {
            if (closed || activity.isFinishing() || activity.isDestroyed()) return;
            CookieManager.getInstance().flush();
            status.setText("正在初始化原站登录环境…");
            // Keep the history URL same-origin too; null defaults to about:blank.
            web.loadDataWithBaseURL(request.site.origin + "/", "<html><body></body></html>", "text/html", "UTF-8", request.site.origin + "/");
        });
    }
    private boolean clearing = true, clearingStorage;
    boolean isLoading() { return !closed && !ready; }
    String loadingStage() { return clearing ? "清理旧会话/初始化登录环境" : "打开原站登录页"; }
    void pageStarted() {
        if (closed) return;
        ready = false; busy = false; clearingStorage = false; epoch++; handler.removeCallbacks(poll);
        if (!clearing) {
            status.setText("正在打开原站登录页，请稍候…");
            // Login mode only: old credentials have been cleared. Allow progressive rendering.
            if (active) web.setVisibility(View.VISIBLE);
        }
    }
    void pageFinished() {
        if (closed) return;
        if (!request.site.owns(web.getUrl())) {
            if (!clearing && request.site.balanceOnly() && request.site.loginUrl(web.getUrl())) {
                ready = true;
                status.setText("请在 Linux DO 完成授权，返回原站后自动核实账号");
                if (active) web.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (clearing) {
            if (!active || clearingStorage) return;
            clearingStorage = true;
            final int current = epoch;
            web.evaluateJavascript("(() => {try {if(location.origin!==" + JSONObject.quote(request.site.origin)
                + ") return false;localStorage.clear();sessionStorage.clear();return true;}catch(e){return false;}})()", value -> {
                if (closed || !active || !clearing || current != epoch) return;
                clearingStorage = false;
                if (!"true".equals(value)) {
                    close(); web.stopLoading(); web.setVisibility(View.INVISIBLE);
                    status.setText("无法清理旧会话，请取消登录后重试；未保存账号"); return;
                }
                clearing = false;
                status.setText("正在打开原站登录页，请稍候…");
                web.loadUrl(request.site.origin + "/login");
            });
            return;
        }
        ready = true;
        status.setText(request.site.balanceOnly()
            ? "请在原站正常登录（支持站内账号、Linux DO）；保存后仅查询余额。"
            : "请在原站页面正常登录；成功后自动验证并保存，无需复制 Token。\n仅支持原站内登录，外部授权跳转暂不支持。");
        if (active) { web.setVisibility(View.VISIBLE); schedule(0); }
    }
    private void schedule(long delay) { handler.removeCallbacks(poll); if (active && !closed && ready) handler.postDelayed(poll, delay); }
    private boolean valid(int current) {
        return !closed && active && ready && current == epoch && !activity.isFinishing()
            && !activity.isDestroyed() && request.site.owns(web.getUrl());
    }
    private String tokenScript() {
        String refresh = request.site.balanceOnly() ? "''" : "localStorage.getItem('refresh_token')||''";
        return "(() => {if(location.origin!==" + JSONObject.quote(request.site.origin)
            + ") return null;const t=localStorage.getItem('auth_token');const r=" + refresh + ";"
            + "const e=localStorage.getItem('token_expires_at')||'';"
            + "return typeof t==='string'&&t.length<=16384&&r.length<=16384&&e.length<=32"
            + "?JSON.stringify({access_token:t,refresh_token:r,expires_at:e}):null;})()";
    }
    private TokenPair decodeToken(String raw) throws Exception {
        Object value = new JSONTokener(raw).nextValue();
        return value instanceof String ? TokenPair.fromStorage(new JSONObject((String) value)) : null;
    }
    private void capture() {
        final int current = epoch;
        if (!valid(current) || busy) return;
        busy = true;
        web.evaluateJavascript(tokenScript(), raw -> {
            if (!valid(current)) return;
            TokenPair token;
            try { token = decodeToken(raw); }
            catch (Exception e) { busy = false; schedule(1500); return; }
            if (token == null || token.same(rejected) && android.os.SystemClock.elapsedRealtime() < retryAt) {
                busy = false; schedule(1500); return;
            }
            status.setText("检测到登录状态，正在向原站核实账号身份…");
            worker.execute(() -> {
                Account candidate = null;
                String problem = null;
                try {
                    JSONObject user = Api.me(request.site, token.access);
                    String id = Policy.userId(user);
                    if (!request.userId.isEmpty() && !request.userId.equals(id))
                        throw new Exception("登录的是其他账号，请返回后单独添加；原账号未更改");
                    String userJson = user.toString();
                    if (request.site.balanceOnly()) {
                        // 余额站只保留绑定身份及可用余额，不把完整资料或刷新令牌带入本地账号。
                        JSONObject profile = new JSONObject().put("id", id);
                        try { profile.put("balance", Policy.balance(user.opt("balance")).toPlainString()); }
                        catch (Exception ignored) { /* 缺失余额在查询卡片中标为未知。 */ }
                        userJson = profile.toString();
                    }
                    candidate = new Account(request.slot, UUID.randomUUID().toString(), request.label,
                        token.access, id, userJson, request.site.balanceOnly() ? "" : token.refresh,
                        token.expiresAt, request.site, "", "");
                } catch (Exception e) {
                    // Never surface raw response bodies or token-bearing exceptions.
                    problem = "未能验证当前登录，请检查网络或重新登录。重新登录已有账号时，必须使用原账号。";
                }
                final Account result = candidate;
                final String failure = problem;
                handler.post(() -> {
                    if (!valid(current)) return;
                    if (result == null) {
                        rejected = token; retryAt = android.os.SystemClock.elapsedRealtime() + 10000;
                        status.setText(failure); busy = false; schedule(1500); return;
                    }
                    // Do not bind a token that changed while the identity request was in flight.
                    web.evaluateJavascript(tokenScript(), latest -> {
                        if (!valid(current)) return;
                        try {
                            if (!token.same(decodeToken(latest))) { busy = false; schedule(0); return; }
                        } catch (Exception e) { busy = false; schedule(1500); return; }
                        CookieManager.getInstance().flush();
                        closed = true; handler.removeCallbacksAndMessages(null); worker.shutdownNow();
                        complete.accept(result);
                    });
                });
            });
        });
    }
    void pause() {
        active = false; epoch++; busy = false; clearingStorage = false;
        handler.removeCallbacksAndMessages(null);
    }
    void resume() {
        if (closed) return;
        active = true;
        // Retry invalidated initialization, or recover a completed page after backgrounding.
        if (!ready && request.site.loginUrl(web.getUrl()) && web.getProgress() == 100) pageFinished();
        if (ready) { web.setVisibility(View.VISIBLE); schedule(0); }
        else if (!clearing && request.site.loginUrl(web.getUrl())) web.setVisibility(View.VISIBLE);
    }
    void close() { closed = true; epoch++; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); }
}
