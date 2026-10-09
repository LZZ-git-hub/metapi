package com.nexacheckin;
import android.app.Application;
import android.webkit.WebView;
public final class CheckinApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        String suffix = Policy.profileSuffix(getPackageName(), Application.getProcessName());
        // Before any WebView, CookieManager or WebStorage use in this process.
        if (suffix == null) WebView.disableWebView();
        else WebView.setDataDirectorySuffix(suffix);
    }
}
