import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(`./app/src/${path}`, import.meta.url), 'utf8');
const java = name => read(`main/java/com/nexacheckin/${name}.java`);

test('nine non-exported activities each own a private process', () => {
    const manifest = read('main/AndroidManifest.xml');
    for (let slot = 1; slot <= 9; slot++) {
        const activity = manifest.match(new RegExp(`<activity[^>]+android:name="\\.Slot${slot}Activity"[^>]*>`))?.[0];
        assert.ok(activity);
        assert.ok(activity.includes(`android:process=":account${slot}"`));
        assert.ok(activity.includes('android:exported="false"'));
    }
    assert.ok(manifest.includes('android:allowBackup="false"'));
    assert.ok(manifest.includes('android:usesCleartextTraffic="false"'));
});
test('application initializes profiles before activities; main cannot create a WebView', () => {
    const app = java('CheckinApplication');
    assert.ok(app.includes('Policy.profileSuffix('));
    assert.ok(app.includes('WebView.disableWebView()'));
    assert.ok(app.includes('WebView.setDataDirectorySuffix(suffix)'));
    assert.ok(!java('MainActivity').includes('new WebView'));
});
test('native network is fixed-origin and bounded; only refresh may POST', () => {
    const api = java('Api');
    assert.ok(api.includes('Policy.ORIGIN + "/api/v1"'));
    assert.ok(api.includes('setInstanceFollowRedirects(false)'));
    assert.ok(api.includes('1024 * 1024'));
    assert.ok(api.includes('deadlines.schedule('));
    assert.ok(api.includes('return object(request(path, credential, null))'));
    assert.ok(api.includes('return object(request("/auth/refresh", null, payload))'));
    assert.ok(api.includes('payload != null && !"/auth/refresh".equals(path)'));
    assert.ok(api.includes('setFixedLengthStreamingMode(payload.length)'));
    assert.equal((api.match(/setRequestMethod\("POST"\)/g) ?? []).length, 1);
});
test('browser does not expose a native JS bridge or log credentials', () => {
    const browser = java('CheckinActivity');
    assert.ok(!browser.includes('addJavascriptInterface'));
    assert.ok(browser.includes('h.cancel()'));
    assert.ok(browser.includes('(Policy.ORIGIN + ":" + account.userId + ":" + account.credential).getBytes'));
    for (const name of ['Api', 'MainActivity', 'CheckinActivity'])
        assert.ok(!java(name).includes('www.nexavlinks.com'));
    assert.ok(browser.includes('account.userId.equals(result.optString("id"))'));
    for (const name of ['Api', 'Account', 'Vault', 'MainActivity', 'CheckinActivity'])
        assert.ok(!/Log\.|System\.(out|err)|printStackTrace/.test(java(name)));
});

test('website login shares the isolated browser and validates before saving', () => {
    const main = java('MainActivity'), browser = java('CheckinActivity'), login = java('BrowserLogin');
    assert.ok(!main.includes('EditText token'));
    assert.ok(main.includes('pending.accept(accounts, data.getStringExtra("nonce"), candidate)'));
    assert.ok(main.includes('intent.putExtra("login", request.json().toString())'));
    assert.ok(browser.includes('loginRequest.slot'));
    assert.ok(browser.includes('new BrowserLogin(this, web, status, loginRequest'));
    assert.ok(browser.includes('WebView.setWebContentsDebuggingEnabled(false)'));
    assert.ok(login.includes('Policy.ORIGIN + "/login"'));
    assert.ok(login.includes('Api.get("/auth/me", token.access)'));
    assert.ok(login.includes('!request.userId.equals(id)'));
    assert.ok(login.includes('!token.same(decodeToken(latest))'));
    assert.ok(login.includes('location.origin!=='));
    assert.ok(login.includes('Policy.siteUrl(web.getUrl())'));
    assert.ok(login.includes('current == epoch'));
    assert.ok(login.includes('removeAllCookies'));
    assert.ok(login.includes('localStorage.clear();sessionStorage.clear()'));
    for (const name of ['BrowserLogin', 'LoginRequest']) {
        assert.ok(!/addJavascriptInterface|Log\.|System\.(out|err)|printStackTrace/.test(java(name)));
        assert.ok(!java(name).includes('new Vault'));
    }
});

// Source-level regressions only; real WebView rendering still needs device coverage.
test('all inert bootstrap pages keep a same-origin history URL', () => {
    for (const name of ['BrowserLogin', 'CheckinActivity']) {
        const calls = java(name).match(/web\.loadDataWithBaseURL\([^;]+;/g);
        assert.ok(calls?.length);
        for (const call of calls) assert.ok(call.endsWith('"UTF-8", Policy.ORIGIN + "/");'));
    }
});
test('login timeout covers bootstrap and is rearmed on resume', () => {
    const browser = java('CheckinActivity');
    const finished = browser.slice(browser.indexOf('@Override public void onPageFinished'), browser.indexOf('@Override public void onReceivedSslError'));
    assert.ok(finished.includes('login.pageFinished();'));
    assert.ok(finished.includes('if (!login.isLoading()) handler.removeCallbacks(loadTimeout);'));
    assert.ok(!finished.includes('{ handler.removeCallbacks(loadTimeout); login.pageFinished();'));
    const resume = browser.slice(browser.indexOf('@Override protected void onResume()'));
    assert.ok(resume.includes('if (login.isLoading()) armLoadTimeout();'));
    assert.ok(resume.indexOf('paused = false;') < resume.indexOf('login.resume();'));
    assert.ok(browser.includes('login.loadingStage()'));
});
test('login rendering and initialization recovery retain isolation checks', () => {
    const login = java('BrowserLogin');
    const started = login.slice(login.indexOf('void pageStarted()'), login.indexOf('void pageFinished()'));
    assert.ok(started.includes('if (!clearing)'));
    assert.ok(started.includes('if (active) web.setVisibility(View.VISIBLE);'));
    assert.ok(login.includes('if (!active || clearingStorage) return;'));
    assert.ok(login.includes('if (closed || !active || !clearing || current != epoch) return;'));
    assert.ok(login.includes('close(); web.stopLoading(); web.setVisibility(View.INVISIBLE);'));
    assert.ok(login.includes('if (!ready && Policy.siteUrl(web.getUrl()) && web.getProgress() == 100) pageFinished();'));
    assert.ok(login.includes('active = false; epoch++; busy = false; clearingStorage = false;'));
});


test('home lifecycle, date rollover and rename do not auto-refresh; action results refresh only their account', () => {
    const main = java('MainActivity');
    const resume = main.slice(main.indexOf('@Override protected void onResume()'), main.indexOf('@Override protected void onPause()'));
    const midnight = main.slice(main.indexOf('private final Runnable midnight'), main.indexOf('@Override public void onCreate'));
    const result = main.slice(main.indexOf('@Override protected void onActivityResult'), main.indexOf('private boolean save('));
    const edit = main.slice(main.indexOf('private void edit('));
    for (const body of [resume, midnight, edit]) {
        assert.ok(!/refreshAll\(|refresh\(|refreshAfterAction\(|runNext\(|Progress\.read\(/.test(body));
    }
    assert.ok(!/refreshAll\(/.test(result));
    assert.ok(result.includes('refreshAfterAction(find(candidate.slot))'));
    assert.ok(result.indexOf('if (save(pending.accept(') < result.indexOf('refreshAfterAction(find(candidate.slot))'));
    assert.ok(result.includes('RefreshPolicy.matchesVisit(returned, slot, revision)'));
    assert.ok(result.includes('refreshAfterAction(returned)'));
    assert.ok(result.indexOf('visitingSlot = 0; visitingRevision = "";') < result.indexOf('refreshAfterAction(returned)'));
    assert.ok(main.includes('state.putString("visitingRevision", visitingRevision)'));
    assert.equal((main.match(/Progress\.read\(/g) ?? []).length, 1);
    assert.ok(!main.includes('progress.clear()'));
    assert.ok(main.includes('Progress old = progress.get(slot)'));
    assert.ok(main.includes('p.checked = null; p.eligible = null; p.checkinPending = true'));
    assert.ok(main.includes('progress.get(a.slot) == loading'));
});
test('manual refresh coalesces in-flight requests without cooldown; cache remains encrypted', () => {
    const main = java('MainActivity');
    assert.ok(main.includes('Executors.newFixedThreadPool(RefreshPolicy.MAX_CONCURRENT)'));
    assert.ok(main.includes('RefreshPolicy.isBusy(p)'));
    assert.ok(!main.includes('RefreshPolicy.remaining('));
    assert.ok(!/COOLDOWN_MS|requestedAt|currentTimeMillis/.test(java('RefreshPolicy')));
    assert.ok(!/5 分钟|冷却/.test(main));
    assert.ok(main.includes('RefreshPolicy.hasCapacity(queryingSlots.size()) && !queue.isEmpty()'));
    assert.ok(main.includes('accountStore.tryBegin(a)'));
    assert.ok(!main.includes('}, 2000)'));
    assert.ok(main.includes('queryingSlots.remove(a.slot)'));
    assert.ok(java('AccountStore').includes('RefreshPolicy.hasCapacity(active.size())'));
    assert.ok(main.includes('if (priority) queue.addFirst(a)'));
    assert.ok(main.includes('if (!queryingSlots.contains(old.slot)) progress.remove(old.slot)'));
    assert.ok(main.includes('new Vault(this, "snapshots.enc")'));
    assert.ok(main.includes('SnapshotData.decode(accounts, snapshots.loadValues())'));
    assert.ok(main.includes('snapshots.saveValues(SnapshotData.encode(accounts, progress))'));
    assert.ok(java('Vault').includes('AES/GCM/NoPadding'));
    assert.ok(java('Vault').includes('getNoBackupFilesDir()'));
    assert.ok(!java('SnapshotData').includes('.credential'));
});


test('each manual query renews once, persists the pair first and survives activity recreation', () => {
    const main = java('MainActivity'), renewal = java('Renewal'), store = java('AccountStore');
    assert.ok(main.includes('Renewal.renew(a, Api::refresh, accountStore::rotate, System::currentTimeMillis)'));
    assert.ok(main.indexOf('Renewal.renew(') < main.indexOf('Progress.read(effective)'));
    assert.equal((renewal.match(/exchange\.refresh\(/g) ?? []).length, 1);
    assert.ok(renewal.indexOf('store.save(a, next)') < renewal.indexOf('return next'));
    assert.ok(main.includes('worker.shutdown()')); assert.ok(!main.includes('worker.shutdownNow()'));
    assert.ok(store.includes('synchronized void begin(Account a)'));
    assert.ok(store.includes('active.contains(a.slot)'));
    assert.ok(store.includes('getApplicationContext()'));
    assert.ok(store.indexOf('vault.save(updated)') < store.indexOf('accounts = updated'));
    assert.ok(main.includes('latest.revision.equals(resultRevision)'));
    assert.ok(main.includes('if (accountStore.busy(a.slot))'));
});
test('login collects both tokens; check-in page cannot independently rotate the native pair', () => {
    const login = java('BrowserLogin'), browser = java('CheckinActivity');
    assert.ok(login.includes("localStorage.getItem('refresh_token')"));
    assert.ok(login.includes("localStorage.getItem('token_expires_at')"));
    assert.ok(login.includes('token.refresh, token.expiresAt'));
    assert.ok(browser.includes('seed = !account.refreshToken.isEmpty()'));
    assert.ok(browser.includes('localStorage.clear(); sessionStorage.clear()'));
    assert.ok(!browser.includes("localStorage.setItem('refresh_token'"));
    assert.ok(!browser.includes("localStorage.setItem('token_expires_at'"));
    const result = browser.slice(browser.indexOf('result.putExtra("account"'), browser.indexOf('login.start()'));
    assert.ok(result.indexOf('web.destroy()') < result.indexOf('setResult(RESULT_OK'));
    for (const name of ['TokenPair', 'Renewal', 'AccountStore'])
        assert.ok(!/Log\.|System\.(out|err)|printStackTrace/.test(java(name)));
});


test('expiry reminders use only local schedules and protected non-exported receivers', () => {
    const manifest = read('main/AndroidManifest.xml');
    assert.ok(manifest.includes('android.permission.POST_NOTIFICATIONS'));
    assert.ok(manifest.includes('android.permission.RECEIVE_BOOT_COMPLETED'));
    assert.ok(manifest.includes('android:name=".ExpiryReminderReceiver" android:exported="false"'));
    for (const action of ['BOOT_COMPLETED', 'MY_PACKAGE_REPLACED', 'TIME_SET', 'TIMEZONE_CHANGED'])
        assert.ok(manifest.includes(`android.intent.action.${action}`));
    assert.ok(!/SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|FOREGROUND_SERVICE/.test(manifest));
    for (const name of ['ExpiryNotifications', 'ExpiryReminderReceiver', 'ExpiryPolicy']) {
        const source = java(name);
        assert.ok(!/Api\.|Renewal\.|Progress\.read\(|HttpURLConnection|HttpsURLConnection|WebView/.test(source));
        assert.ok(!/Log\.|System\.(out|err)|printStackTrace/.test(source));
    }
    const reminders = java('ExpiryNotifications'), receiver = java('ExpiryReminderReceiver');
    assert.ok(reminders.includes('setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP'));
    assert.ok(!reminders.includes('setExact'));
    assert.ok(reminders.includes('PendingIntent.FLAG_IMMUTABLE'));
    assert.ok(!reminders.includes('.putExtra('));
    assert.ok(reminders.includes('Notification.VISIBILITY_PRIVATE'));
    assert.ok(reminders.includes('.setPublicVersion(publicVersion)'));
    assert.ok(reminders.includes('Manifest.permission.POST_NOTIFICATIONS'));
    assert.ok(reminders.includes('manager.areNotificationsEnabled()'));
    assert.ok(reminders.includes('NotificationManager.IMPORTANCE_NONE'));
    assert.ok(receiver.includes('AccountStore.get(app).rescheduleReminders()'));
    assert.ok(receiver.includes('finally { pending.finish(); }'));
});
test('saved rotation/login/delete reschedule reminders under the account-store lock', () => {
    const store = java('AccountStore'), main = java('MainActivity'), reminders = java('ExpiryNotifications');
    assert.ok(store.includes('synchronized void rescheduleReminders()'));
    assert.ok(store.includes('accounts = updated;\n        rescheduleReminders();'));
    assert.ok(store.includes('vault.save(merged); accounts = merged;\n        rescheduleReminders();'));
    assert.ok(main.includes('onRequestPermissionsResult'));
    assert.ok(main.includes('ExpiryNotifications.permissionAsked(this)'));
    assert.ok(main.includes('ExpiryPolicy.message(a, now)'));
    assert.ok(reminders.includes('manager.cancel(id)'));
    assert.ok(reminders.includes('a == null || a.expiresAt <= 0'));
    assert.ok(reminders.includes('ExpiryPolicy.shouldNotify('));
    assert.ok(reminders.includes('alarms.cancel(pending)'));
});


test('single home prioritizes check-in and folds secondary amounts without hiding unknowns', () => {
    const main = java('MainActivity');
    assert.ok(main.includes('header.addView(remindersButton)'));
    assert.ok(!main.includes('overview.addView(Ui.button(this, "到期提醒设置"'));
    assert.ok(main.includes('CheckinPresentation.state(p, day)'));
    assert.ok(main.includes('CheckinPresentation.action(state)'));
    assert.ok(main.includes('if (statisticsExpanded) renderStatistics(t)'));
    assert.ok(main.includes('if (expanded) accountDetails(card, a, p, now)'));
    assert.ok(main.includes('CheckinPresentation.shortWarning(p)'));
    assert.ok(main.includes('showText(a.label + " · 查询异常", warning)'));
    assert.ok(main.includes('if (expiryLevel >= ExpiryPolicy.SOON)'));
    assert.ok(main.includes('homeScroll.scrollTo(0, scrollY)'));
    const render = main.slice(main.indexOf('private void render()'), main.indexOf('private void renderStatistics('));
    assert.ok(render.indexOf('card.addView(buttons)') < render.indexOf('if (expanded) accountDetails'));
    for (const metric of ['账号余额合计', '累计使用合计', '今日消费合计'])
        assert.ok(main.includes(`Totals.label("${metric}"`));
    assert.ok(main.includes('subscriptionDetails(card, p, now)'));
});
test('subscriptions use one fixed GET after identity and sanitized snapshot storage', () => {
    const api = java('Api'), progress = java('Progress'), subscription = java('Subscription');
    assert.ok(api.includes('request("/subscriptions", credential, null)'));
    assert.ok(api.includes('data instanceof JSONArray'));
    assert.ok(progress.includes('Api::subscriptions'));
    assert.ok(progress.indexOf('!account.userId.equals(Policy.userId(user))') < progress.indexOf('subscriptionReader.get('));
    assert.ok(progress.includes('Subscription.json(subscriptions)'));
    assert.ok(progress.includes('Subscription.restore('));
    assert.ok(!java('Totals').includes('quotas'));
    assert.ok(!/Api\.|setRequestMethod|Log\.|System\.(out|err)/.test(subscription));
});


test('subscription cards use known-only progress bars and exact amount labels', () => {
    const view = java('SubscriptionCard');
    assert.ok(java('MainActivity').includes('SubscriptionCard.create(this, s, now)'));
    assert.ok(view.includes('if (used >= 0)'));
    assert.ok(view.includes('setMax(10000)'));
    assert.ok(view.includes('setProgress(used)'));
    assert.ok(view.includes('setContentDescription('));
    assert.ok(view.includes('Totals.money(quota.remaining())'));
    assert.ok(view.includes('Totals.money(quota.used)'));
    assert.ok(view.includes('Totals.money(quota.limit)'));
    assert.ok(view.includes('!currentWindow || used < 0 ? Ui.MUTED'));
    assert.ok(!/Api\.|Renewal\.|Progress\.read\(|Log\.|System\.(out|err)/.test(view));
});


test('reminder sheet has a real switch, system permission state and secure local help', () => {
    const main = java('MainActivity');
    assert.ok(main.includes('reminderSwitch = new Switch(this)'));
    assert.ok(main.includes('window.setGravity(Gravity.BOTTOM)'));
    assert.ok(main.includes('if (updatingReminderSwitch) return'));
    assert.ok(main.includes('if (!ExpiryNotifications.setEnabled(this, enabled))'));
    assert.ok(main.includes('reminderStatus.setText(ExpiryNotifications.status(this))'));
    assert.ok(main.includes('ExpiryNotifications.needsAttention(this)'));
    assert.ok(main.includes('updateReminderPanel()'));
    const help = main.slice(main.indexOf('private void showHelp()'), main.indexOf('private TextView expiryNotice'));
    assert.ok(!/Api\.|Renewal\.|Progress\.read\(/.test(help));
    assert.ok(help.includes('FLAG_SECURE'));
    assert.ok(main.includes('state.putBoolean("statisticsExpanded", statisticsExpanded)'));
    assert.ok(main.includes('state.putStringArrayList("expandedAccounts"'));
});


test('eligibility comes from the existing status response and resets are local display only', () => {
    const p = java('Progress'), main = java('MainActivity'), view = java('SubscriptionCard');
    assert.equal((p.match(/reader.get\("\/check-in"/g) ?? []).length, 1);
    assert.ok(p.includes('checkin.opt("eligible")'));
    assert.ok(p.includes('eligible instanceof Boolean'));
    assert.ok(p.includes('.put("eligible", eligible)'));
    assert.ok(main.includes('CheckinPresentation.eligibilityLabel(p, day)'));
    assert.ok(main.includes('completed.eligible = null'));
    assert.ok(!java('CheckinPresentation').includes('p.cost'));
    assert.ok(java('Subscription').includes('QuotaReset.read(j, group, period)'));
    assert.ok(java('Subscription').includes('QuotaReset.restore(q.optJSONObject("reset"))'));
    assert.ok(view.includes('quota.reset.description(active, now)'));
    assert.ok(view.includes('quota.reset.needsRefresh(now)'));
    assert.ok(view.includes('上次剩余 USD'));
    assert.ok(!/Api\.|Renewal\.|Progress\.read\(|new URL|setInterval|postDelayed/.test(java('QuotaReset')));
});


test('router sessions are provider-bound and only explicit claims reach AnyRouter POST', () => {
    const api = java('RouterApi'), main = java('MainActivity');
    assert.ok(api.includes('claim && a.site != Site.ANY'));
    assert.ok(api.includes('claim ? "/api/user/sign_in" : "/api/user/self"'));
    assert.ok(api.includes('new URL(a.site.origin + path)'));
    assert.ok(api.includes('setInstanceFollowRedirects(false)'));
    assert.ok(api.includes('setFixedLengthStreamingMode(0)'));
    assert.ok(api.includes('RouterProtocol.user(transport.request(a, false), a.userId)'));
    assert.ok(!java('RouterProtocol').includes(".contains(\"success\")"));
    assert.ok(main.includes('final boolean claim = routerClaims.remove(a.revision)'));
    assert.ok(main.includes('if (!claim) throw new Exception'));
    assert.ok(main.includes('a.site == Site.NEXA && unavailable(a).isEmpty()'));
    assert.ok(main.includes('a == null || a.site.router()) return'));
    assert.ok(java('LoginRequest').includes('site != candidate.site'));
    assert.ok(java('LoginRequest').includes('a.identityKey().equals(candidate.identityKey())'));
    assert.ok(java('SnapshotData').includes('a.site.origin.equals(j.optString("origin"))'));
});
test('router OAuth is isolated, does not collect passwords, and permits only fixed Linux DO origins', () => {
    const browser = java('RouterBrowser'), site = java('Site');
    assert.ok(site.includes('sameOrigin("https://connect.linux.do", url)'));
    assert.ok(site.includes('sameOrigin("https://linux.do", url)'));
    assert.ok(browser.includes('request == null ? site.owns(url) : site.loginUrl(url)'));
    assert.ok(browser.includes('CookieManager.getInstance().getCookie(site.origin+"/api/user/self")'));
    assert.ok(browser.includes('RouterProtocol.user(new JSONObject((String)decoded),id)'));
    assert.ok(browser.includes('setAcceptThirdPartyCookies(view,false)'));
    assert.ok(browser.includes('h.cancel()'));
    assert.ok(browser.includes('localStorage.clear();sessionStorage.clear()'));
    assert.ok(!/addJavascriptInterface|Log\.|System\.(out|err)|printStackTrace|querySelector/.test(browser));
    assert.ok(java('CheckinActivity').includes('router = new RouterBrowser(this, loginRequest, account, clearOnly)'));
    assert.ok(!java('MainActivity').includes('new WebView'));
});
