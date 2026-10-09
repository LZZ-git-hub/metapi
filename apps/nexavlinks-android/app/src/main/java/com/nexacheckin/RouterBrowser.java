package com.nexacheckin;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Isolated router website login. OAuth is handled by the site's own UI, not password collection. */
final class RouterBrowser {
    private static final String PROFILE = "router-profile";
    private final Activity activity;
    private final LoginRequest request;
    private final Account account;
    private final Site site;
    private final boolean clear;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final TextView status;
    private WebView web, popup;
    private boolean active = true, closed, clearing, clearingStorage, busy, verified;
    private int epoch;
    private String confirmedCheckinDay = "", verificationDay = "";
    private final Runnable timeout = this::onTimeout;
    private void onTimeout() { if(alive() && active) fail("原站加载或身份核实超时，请返回后重试"); }
    RouterBrowser(Activity activity, LoginRequest request, Account account, boolean clear) {
        this.activity = activity; this.request = request; this.account = account; this.clear = clear;
        site = request != null ? request.site : account.site;
        root = Ui.column(activity);
        root.addView(Ui.title(activity, site.label + " · " + (request == null ? account.label : request.label), 20));
        status = Ui.muted(activity,"正在准备隔离浏览器…",13); root.addView(status);
        root.addView(Ui.button(activity,request == null ? "返回账号列表" : "取消登录",activity::finish));
        web = createWeb(); root.addView(web,new LinearLayout.LayoutParams(-1,0,1)); Ui.secure(activity,root);
        start();
    }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private boolean allowed(String url) { return request == null ? site.owns(url) : site.loginUrl(url); }
    private void arm() { handler.removeCallbacks(timeout); if (active && !closed) handler.postDelayed(timeout,45000); }
    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWeb() {
        WebView view = new WebView(activity); view.setVisibility(View.INVISIBLE); view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = view.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSafeBrowsingEnabled(true); s.setSupportMultipleWindows(request != null);
        // The site's OAuth opens a popup after awaiting its state API; allow only in login mode.
        s.setJavaScriptCanOpenWindowsAutomatically(request != null);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);
        view.setDownloadListener((url,agent,disposition,type,length) -> fail("此窗口不支持下载"));
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest permission) { permission.deny(); }
            @Override public boolean onConsoleMessage(ConsoleMessage message) { return true; }
            @Override public boolean onCreateWindow(WebView source, boolean dialog, boolean gesture, Message message) {
                if (!alive() || request == null || popup != null || !site.owns(source.getUrl())) return false;
                // Linux DO's login button opens its OAuth URL after a same-origin state request.
                popup = createWeb(); root.addView(popup,new LinearLayout.LayoutParams(-1,0,1)); web.setVisibility(View.GONE);
                ((WebView.WebViewTransport)message.obj).setWebView(popup); message.sendToTarget(); return true;
            }
            @Override public void onCloseWindow(WebView window) {
                if (window == popup) { root.removeView(popup); popup.destroy(); popup = null; if (web != null) web.setVisibility(View.VISIBLE); }
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                if (v == popup && request != null && "about:blank".equals(r.getUrl().toString())) return false;
                if (!allowed(r.getUrl().toString())) { fail("已阻止未授权站点跳转；此版本支持 Linux DO 或站内登录"); return true; }
                return false;
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                if (!alive()) return;
                if (v == popup && request != null && "about:blank".equals(url)) return;
                if (!allowed(url)) { fail("原站页面地址异常"); return; }
                epoch++; busy = false; clearingStorage = false; handler.removeCallbacks(capture); arm();
                v.setVisibility(active && request != null && !clearing ? View.VISIBLE : View.INVISIBLE);
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (!alive() || !allowed(url)) return;
                if (active) { if (clearing && v == web) arm(); else handler.removeCallbacks(timeout); }
                if (clearing && v == web) { if(active)clearStorage(); return; }
                if (request != null) {
                    if (active) v.setVisibility(View.VISIBLE);
                    status.setText(site.owns(url) ? "请使用 Linux DO 或站内账号正常登录，成功后自动核实保存" : "请在 Linux DO 完成登录授权，密码仅由其页面处理");
                    scheduleCapture();
                } else verify(v, account.userId, false);
            }
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e) { h.cancel(); fail("证书验证失败，已停止连接"); }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e) { if (r.isForMainFrame()) fail("原站加载失败，请稍后重试"); }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e) { if (r.isForMainFrame() && e.getStatusCode() >= 400) fail("原站访问受限，请稍后重试"); }
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail d) {
                root.removeView(v); v.destroy(); if(v==web)web=null; if(v==popup)popup=null;
                fail("浏览器进程退出，请返回重试"); return true;
            }
        });
        return view;
    }
    private void start() {
        if (request != null || clear) {
            clearing = true; activity.getPreferences(Activity.MODE_PRIVATE).edit().remove(PROFILE).commit();
            WebStorage.getInstance().deleteAllData(); web.clearCache(true); arm();
            CookieManager.getInstance().removeAllCookies(ok -> {
                if (!alive()) return;
                CookieManager.getInstance().flush();
                web.loadDataWithBaseURL(site.origin+"/","<html><body></body></html>","text/html","UTF-8",site.origin+"/");
            });
        } else {
            try {
                if (!fingerprint(account).equals(activity.getPreferences(Activity.MODE_PRIVATE).getString(PROFILE,""))) {
                    fail("此槽位会话未验证，请在管理中重新登录"); return;
                }
                arm(); web.loadDataWithBaseURL(site.origin+"/","<html><body></body></html>","text/html","UTF-8",site.origin+"/");
            } catch (Exception e) { fail("无法恢复会话，请重新登录"); }
        }
    }
    private void clearStorage() {
        if(!alive() || !active || !clearing || clearingStorage)return;
        clearingStorage=true;
        int current = epoch; arm();
        web.evaluateJavascript("(() => {if(location.origin!=="+JSONObject.quote(site.origin)+")return false;localStorage.clear();sessionStorage.clear();return true;})()",raw -> {
            if (!alive() || !active || !clearing || current != epoch) return;
            clearingStorage=false;
            if (!"true".equals(raw)) { fail("无法清理旧登录，请返回重试"); return; }
            clearing = false;
            if (clear) { CookieManager.getInstance().flush(); activity.setResult(Activity.RESULT_OK); activity.finish(); }
            else web.loadUrl(site.origin+"/login");
        });
    }
    private WebView currentWeb() { return popup == null ? web : popup; }
    private void scheduleCapture() { handler.removeCallbacks(capture); if (alive() && active) handler.postDelayed(capture,1500); }
    private final Runnable capture = this::captureLogin;
    private void captureLogin() {
        WebView v = currentWeb();
        if (!alive() || !active || request == null || clearing || busy || v == null) return;
        if (!site.owns(v.getUrl())) { scheduleCapture(); return; }
        int current = epoch; busy = true;
        v.evaluateJavascript("(() => {if(location.origin!=="+JSONObject.quote(site.origin)+")return null;try{return String(JSON.parse(localStorage.getItem('user')||'null')?.id||'');}catch(e){return null;}})()",raw -> {
            if (!alive() || current != epoch || !active) return;
            try {
                Object decoded = new JSONTokener(raw).nextValue(); String id = decoded instanceof String ? (String)decoded : "";
                if (!id.matches("[1-9][0-9]*")) { busy=false; scheduleCapture(); return; }
                if (!request.userId.isEmpty() && !request.userId.equals(id)) { fail("登录的是其他账号，未更改原账号"); return; }
                verify(v,id,true);
            } catch (Exception e) { busy=false; scheduleCapture(); }
        });
    }
    private void verify(WebView v,String id,boolean binding) {
        if (!alive() || !active || busy && !binding || !site.owns(v.getUrl())) return;
        int current = epoch; busy = true; verificationDay = Policy.day(); arm();
        String key = "__router_"+UUID.randomUUID().toString().replace("-","");
        String js = "(() => {if(location.origin!=="+JSONObject.quote(site.origin)+")return;window['"+key+"']=null;"
            +"const c=new AbortController();setTimeout(()=>c.abort(),12000);fetch('/api/user/self',{credentials:'same-origin',redirect:'error',signal:c.signal,headers:{'New-Api-User':"+JSONObject.quote(id)+"}})"
            +".then(async r=>{const b=await r.json();const u=b.data;window['"+key+"']=r.ok&&b.success===true&&u?{success:true,data:{id:u.id,quota:u.quota,used_quota:u.used_quota,checked_in:u.checked_in,checked_in_today:u.checked_in_today}}:{success:false};}).catch(()=>window['"+key+"']={success:false});})()";
        v.evaluateJavascript(js,ignored -> poll(v,id,binding,key,current,0));
    }
    private void poll(WebView v,String id,boolean binding,String key,int current,int attempts) {
        if (!alive() || !active || epoch!=current || !site.owns(v.getUrl())) return;
        v.evaluateJavascript("JSON.stringify(window['"+key+"']??null)",raw -> {
            if (!alive() || !active || epoch!=current) return;
            try {
                Object decoded = new JSONTokener(raw).nextValue();
                if (!(decoded instanceof String) || "null".equals(decoded)) {
                    if(attempts>=28){fail("身份核实超时，请返回重试");return;}
                    handler.postDelayed(() -> poll(v,id,binding,key,current,attempts+1),500);return;
                }
                JSONObject user = RouterProtocol.user(new JSONObject((String)decoded),id);
                v.evaluateJavascript("localStorage.getItem('quota_per_unit')",unitRaw -> finishVerification(v,user,binding,current,unitRaw));
            } catch (Exception e) { fail("原站未确认身份或需要浏览器验证，请重新登录；未保存新账号"); }
        });
    }
    private void finishVerification(WebView v,JSONObject user,boolean binding,int current,String unitRaw) {
        if (!alive() || !active || epoch!=current || !site.owns(v.getUrl())) return;
        try {
            String cookie = CookieManager.getInstance().getCookie(site.origin+"/api/user/self");
            if(cookie==null || !Policy.cookie(cookie))throw new Exception();
            String expectedCookie = cookie;
            v.evaluateJavascript("(() => {if(location.origin!=="+JSONObject.quote(site.origin)+")return null;try{return String(JSON.parse(localStorage.getItem('user')||'null')?.id||'');}catch(e){return null;}})()", latest -> {
                if (!alive() || !active || epoch!=current || !site.owns(v.getUrl())) return;
                try {
                    Object latestId = new JSONTokener(latest).nextValue();
                    if (!(latestId instanceof String) || !Policy.userId(user).equals(latestId)
                        || !expectedCookie.equals(CookieManager.getInstance().getCookie(site.origin+"/api/user/self"))) {
                        fail("登录会话在核实时发生变化，请重试"); return;
                    }
                    completeVerification(v,user,binding,unitRaw,expectedCookie);
                } catch(Exception e) { fail("无法核实最新登录状态"); }
            });
        }catch(Exception e){fail("无法保存已验证的登录会话，请重新登录");}
    }
    private void completeVerification(WebView v,JSONObject user,boolean binding,String unitRaw,String cookie) {
        try {
            String unit=""; Object value=new JSONTokener(unitRaw).nextValue();
            if(value instanceof String)try{if(Policy.cost(value).signum()>0&&((String)value).length()<=32)unit=(String)value;}catch(Exception ignored){}
            if(unit.isEmpty()&&account!=null)unit=account.routerUnit;
            String id=Policy.userId(user);
            Account saved = new Account(binding?request.slot:account.slot,UUID.randomUUID().toString(),binding?request.label:account.label,
                Policy.credential(cookie),id,new JSONObject().put("id",id).toString(),"",0,site,unit,v.getSettings().getUserAgentString());
            CookieManager.getInstance().flush();
            activity.getPreferences(Activity.MODE_PRIVATE).edit().putString(PROFILE,fingerprint(saved)).commit();
            Intent result=new Intent(); result.putExtra(binding?"account":"routerAccount",saved.json().toString());
            // Preserve a reward observed in the first /self response when later verification is silent.
            if (Boolean.TRUE.equals(RouterProtocol.checked(user))) confirmedCheckinDay = verificationDay;
            Progress snapshot = RouterProtocol.snapshot(saved,user,verificationDay);
            if(snapshot.checked == null && snapshot.day.equals(confirmedCheckinDay))snapshot.checked=true;
            result.putExtra("routerProgress",snapshot.json().toString());
            if(binding)result.putExtra("nonce",request.nonce);
            activity.setResult(Activity.RESULT_OK,result); handler.removeCallbacks(timeout); busy=false;
            if(binding){close();activity.finish();return;}
            if(!verified){verified=true;v.loadUrl(site.origin+"/console");}
            else {status.setText("原站身份已核实 · 关闭后返回账号列表");if(active)v.setVisibility(View.VISIBLE);}
        }catch(Exception e){fail("无法保存已验证的登录会话，请重新登录");}
    }
    private static String fingerprint(Account a)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((a.site.origin+":"+a.userId+":"+a.credential).getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(digest,android.util.Base64.NO_WRAP);
    }
    private void fail(String message){activity.setResult(Activity.RESULT_CANCELED);closed=true;handler.removeCallbacksAndMessages(null);if(web!=null){web.stopLoading();web.setVisibility(View.INVISIBLE);}if(popup!=null){popup.stopLoading();popup.setVisibility(View.INVISIBLE);}status.setText(message);}
    void pause(){active=false;epoch++;busy=false;clearingStorage=false;handler.removeCallbacksAndMessages(null);if(web!=null){web.setVisibility(View.INVISIBLE);web.onPause();}if(popup!=null){popup.setVisibility(View.INVISIBLE);popup.onPause();}}
    void resume(){if(closed)return;active=true;if(web!=null)web.onResume();if(popup!=null)popup.onResume();
        if(clearing&&web!=null&&site.owns(web.getUrl())&&web.getProgress()==100)clearStorage();
        else if(request!=null){if(currentWeb()!=null&&allowed(currentWeb().getUrl())&&currentWeb().getProgress()==100)currentWeb().setVisibility(View.VISIBLE);scheduleCapture();}else if(currentWeb()!=null&&site.owns(currentWeb().getUrl())&&currentWeb().getProgress()==100)verify(currentWeb(),account.userId,false);
        if(clearing||currentWeb()!=null&&currentWeb().getProgress()<100)arm();
    }
    void close(){closed=true;handler.removeCallbacksAndMessages(null);if(popup!=null){root.removeView(popup);popup.destroy();popup=null;}if(web!=null){root.removeView(web);web.destroy();web=null;}}
}
