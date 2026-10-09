package com.nexacheckin;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.http.SslError;
import android.view.View;
import android.webkit.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Private activity; one process/data directory per slot. Never sends a check-in POST. */
public abstract class CheckinActivity extends Activity {
    private WebView web;
    private TextView status;
    private Account account;
    private LoginRequest loginRequest;
    private BrowserLogin login;
    private RouterBrowser router;
    private Site site;
    private boolean seed, clearOnly, failed, paused, bootstrap = true;
    private int generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable loadTimeout = () -> {
        if (isDestroyed() || isFinishing() || paused || failed) return;
        if (login != null) {
            if (login.isLoading()) fail("登录加载超时（" + login.loadingStage()
                + "），请取消后重试；账号尚未保存");
        } else fail("页面或身份核实超时，请返回重试；可能是凭证失效或原站验证不兼容");
    };
    private void armLoadTimeout() {
        handler.removeCallbacks(loadTimeout);
        if (!paused && !failed) handler.postDelayed(loadTimeout, 45000);
    }
    private String fingerprint;
    private static final String PREF = "profile-identity";
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (getIntent().hasExtra("login")) {
                loginRequest = LoginRequest.from(getIntent().getStringExtra("login"));
                if (!Application.getProcessName().equals(getPackageName() + ":account" + loginRequest.slot)) throw new Exception();
            } else {
                account = Account.from(new JSONObject(getIntent().getStringExtra("account")));
                if (!Application.getProcessName().equals(getPackageName() + ":account" + account.slot)) throw new Exception();
                clearOnly = getIntent().getBooleanExtra("clear", false);
                fingerprint = fingerprint(account);
            }
        } catch (Exception e) { finish(); return; }
        site = loginRequest != null ? loginRequest.site : account.site;
        if (site.router()) { router = new RouterBrowser(this, loginRequest, account, clearOnly); return; }
        if (!site.tokenSession()) { finish(); return; }
        LinearLayout root = Ui.column(this);
        root.addView(Ui.text(this, loginRequest == null ? account.label + " · ID " + account.userId : loginRequest.label + " · 原站登录", 19));
        status = Ui.text(this, "正在加载，请稍候…", 13); root.addView(status);
        root.addView(Ui.button(this, loginRequest == null ? "返回账号列表" : "取消登录并返回", this::finish));
        WebView.setWebContentsDebuggingEnabled(false);
        web = new WebView(this); web.setVisibility(View.INVISIBLE);
        web.setSaveEnabled(false);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSafeBrowsingEnabled(true); settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
        });
        web.setDownloadListener((url, agent, disposition, type, length) -> fail("签到窗口不支持下载"));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (!allowedNavigation(request.getUrl().toString())) { fail("已阻止跳转到其他网站"); return true; }
                return false;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (failed) return;
                generation++; web.setVisibility(View.INVISIBLE);
                armLoadTimeout();
                if (!allowedNavigation(url)) { fail("原站页面地址异常"); return; }
                if (login != null) login.pageStarted();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (failed || !allowedNavigation(url)) return;
                if (login != null) {
                    login.pageFinished();
                    // The inert bootstrap finishing does not mean the login page is ready.
                    if (!login.isLoading()) handler.removeCallbacks(loadTimeout);
                    return;
                }
                if (clearOnly) { clearLocalAndFinish(); return; }
                if (seed) seedStorage(); else verifyBrowser(generation);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                h.cancel(); fail("原站证书验证失败，已停止连接");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame()) fail("页面加载失败，请返回后重试");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse response) {
                if (req.isForMainFrame() && response.getStatusCode() >= 400) fail("原站访问被拒绝，请返回后重试");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                fail("浏览器进程已退出，请返回重试");
                ((LinearLayout) view.getParent()).removeView(view); view.destroy(); web = null;
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); Ui.secure(this, root);
        if (loginRequest != null) {
            // Native owns refresh-token rotation. Always prepare a fresh access-only browser session.
            getPreferences(MODE_PRIVATE).edit().remove(PREF).commit();
            login = new BrowserLogin(this, web, status, loginRequest, value -> {
                try {
                    // Only the private caller validates the transaction and writes the vault.
                    getPreferences(MODE_PRIVATE).edit().remove(PREF).commit();
                    android.content.Intent result = new android.content.Intent();
                    result.putExtra("nonce", loginRequest.nonce);
                    result.putExtra("account", value.json().toString());
                    // End the page's timers before handing the rotating pair to the main process.
                    web.stopLoading(); ((LinearLayout) web.getParent()).removeView(web); web.destroy(); web = null;
                    setResult(RESULT_OK, result); finish();
                } catch (Exception e) { fail("无法返回登录结果，请返回重试"); }
            });
            login.start(); handler.postDelayed(loadTimeout, 45000); return;
        }
        seed = !account.refreshToken.isEmpty() || !fingerprint.equals(getPreferences(MODE_PRIVATE).getString(PREF, ""));
        if (clearOnly || seed) {
            // All operations apply only to this slot's process-specific WebView profile.
            getPreferences(MODE_PRIVATE).edit().remove(PREF).commit();
            web.clearCache(true); WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(removed -> {
                if (isDestroyed()) return;
                CookieManager.getInstance().flush();
                if (clearOnly) {
                    web.loadDataWithBaseURL(site.origin + "/", "<html><body></body></html>", "text/html", "UTF-8", site.origin + "/");
                } else if (Policy.cookie(account.credential)) importCookies();
                else loadBootstrap();
            });
        } else loadBootstrap();
        handler.postDelayed(loadTimeout, 45000);
    }
    private boolean allowedNavigation(String url) {
        return loginRequest != null ? site.loginUrl(url) : site.owns(url);
    }
    private static String fingerprint(Account account) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((account.site.origin + ":" + account.userId + ":" + account.credential).getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
    }
    private void loadBootstrap() {
        // Verify in an inert same-origin document BEFORE executing the real site's UI.
        web.loadDataWithBaseURL(site.origin + "/", "<html><body></body></html>", "text/html", "UTF-8", site.origin + "/");
    }
    private void importCookies() {
        String[] pairs = account.credential.split(";");
        importCookie(pairs, 0);
    }
    private void importCookie(String[] pairs, int index) {
        if (isDestroyed() || failed) return;
        if (index == pairs.length) { CookieManager.getInstance().flush(); loadBootstrap(); return; }
        String pair = pairs[index].trim(); int equals = pair.indexOf('=');
        if (equals < 1 || !pair.substring(0, equals).matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) { fail("Cookie 格式无效，请重新绑定"); return; }
        CookieManager.getInstance().setCookie(site.origin, pair + "; Path=/; Secure; HttpOnly; SameSite=Lax", ok -> {
            if (!ok) fail("无法导入登录 Cookie"); else importCookie(pairs, index + 1);
        });
    }
    private void seedStorage() {
        if (!site.owns(web.getUrl())) { fail("无法恢复登录状态"); return; }
        int epoch = generation;
        String js = "(() => { if(location.origin!==" + JSONObject.quote(site.origin) + ") return false; localStorage.clear(); sessionStorage.clear();";
        if (!Policy.cookie(account.credential)) js += "localStorage.setItem('auth_token'," + JSONObject.quote(account.credential) + ");localStorage.setItem('auth_user'," + JSONObject.quote(account.userJson) + ");";
        js += "return true; })()";
        web.evaluateJavascript(js, result -> {
            if (isDestroyed() || failed || epoch != generation) return;
            if (!"true".equals(result)) { fail("登录存储初始化失败"); return; }
            seed = false;
            verifyBrowser(epoch);
        });
    }
    private void verifyBrowser(int epoch) {
        if (isDestroyed() || failed || epoch != generation) return;
        String key = "__nexa_" + UUID.randomUUID().toString().replace("-", "");
        String quoted = JSONObject.quote(key);
        // No native JavaScript bridge. Async result is polled; never read/return credentials to the caller.
        String js = "(() => { if(location.origin!==" + JSONObject.quote(site.origin) + ") return; "
            + "window[" + quoted + "]=null; const c=new AbortController(); const timer=setTimeout(()=>c.abort(),10000);"
            + "const t=localStorage.getItem('auth_token'); fetch('/api/v1/auth/me',{redirect:'error',credentials:'same-origin',signal:c.signal,headers:{'X-User-UI-Request':'1',...(t?{Authorization:'Bearer '+t}:{})}})"
            + ".then(async r=>{const b=await r.json();window[" + quoted + "]={ok:r.ok&&b.code===0,id:String(b.data?.id??'')};})"
            + ".catch(()=>{window[" + quoted + "]={ok:false};}).finally(()=>clearTimeout(timer)); })()";
        web.evaluateJavascript(js, ignored -> poll(quoted, epoch, 0));
    }
    private void poll(String key, int epoch, int attempts) {
        if (isDestroyed() || failed || epoch != generation) return;
        web.evaluateJavascript("JSON.stringify(window[" + key + "]??null)", raw -> {
            if (isDestroyed() || failed || epoch != generation) return;
            try {
                Object decoded = new JSONTokener(raw).nextValue();
                JSONObject result = decoded instanceof String && !"null".equals(decoded) ? new JSONObject((String) decoded) : null;
                if (result == null) {
                    if (attempts >= 24) { fail("无法核实原站登录身份，请重新绑定或稍后重试"); return; }
                    handler.postDelayed(() -> poll(key, epoch, attempts + 1), 500); return;
                }
                web.evaluateJavascript("delete window[" + key + "]", null);
                if (!result.optBoolean("ok") || !account.userId.equals(result.optString("id"))) {
                    getPreferences(MODE_PRIVATE).edit().remove(PREF).commit();
                    fail("身份不匹配或登录已失效。已隐藏页面，请返回更新凭证后重试。"); return;
                }
                getPreferences(MODE_PRIVATE).edit().putString(PREF, fingerprint).apply();
                if (bootstrap) {
                    bootstrap = false;
                    web.loadUrl(site.origin + site.accountPath());
                    return;
                }
                status.setText("身份已核实 · ID " + account.userId + (site.balanceOnly()
                    ? "\n返回列表后更新余额，不执行签到。"
                    : "\n请手动完成原站验证。返回列表后自动更新此账号卡片并核实签到结果。"));
                handler.removeCallbacks(loadTimeout);
                if (!paused) web.setVisibility(View.VISIBLE);
            } catch (Exception e) { fail("身份核实失败，请返回重试"); }
        });
    }
    private void clearLocalAndFinish() {
        web.evaluateJavascript("(() => {localStorage.clear();sessionStorage.clear();return true;})()", result -> {
            if (!"true".equals(result)) { fail("清理失败，请返回重试"); return; }
            CookieManager.getInstance().removeAllCookies(ok -> {
                CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData();
                web.clearCache(true); web.clearHistory(); setResult(RESULT_OK); finish();
            });
        });
    }
    private void fail(String message) {
        failed = true; generation++; handler.removeCallbacks(loadTimeout);
        if (login != null) login.close();
        if (web != null) { web.stopLoading(); web.setVisibility(View.INVISIBLE); }
        if (status != null) status.setText(message);
    }
    @Override protected void onPause() {
        if (router != null) { router.pause(); super.onPause(); return; }
        paused = true; generation++; handler.removeCallbacks(loadTimeout);
        if (login != null) login.pause();
        if (web != null) { web.setVisibility(View.INVISIBLE); web.onPause(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (router != null) { router.resume(); return; }
        if (web == null) return;
        web.onResume();
        if (login != null) {
            paused = false;
            if (!failed) {
                login.resume();
                if (login.isLoading()) armLoadTimeout();
                else handler.removeCallbacks(loadTimeout);
            }
            return;
        }
        if (paused && !failed && !clearOnly) {
            bootstrap = true;
            loadBootstrap();
        }
        paused = false;
    }
    @Override protected void onDestroy() {
        if (router != null) router.close();
        handler.removeCallbacksAndMessages(null);
        if (login != null) login.close();
        if (web != null) { ((LinearLayout) web.getParent()).removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
