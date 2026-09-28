import { createHash } from 'node:crypto';
import { and, eq, gte, inArray, lt, sql } from 'drizzle-orm';
import { db, schema } from '../db/index.js';
import { upsertSetting } from '../db/upsertSetting.js';
import { fetch } from 'undici';
import { getCredentialModeFromExtraConfig, resolveProxyUrlFromExtraConfig } from './accountExtraConfig.js';
import { withSiteRecordProxyRequestInit } from './siteProxy.js';
import { formatUtcSqlDateTime, parseStoredUtcDateTime } from './localTimeService.js';

const settingKey = 'nexavlinks_daily_priority';
type Account = typeof schema.accounts.$inferSelect;
type Site = typeof schema.sites.$inferSelect;
export type NexavlinksCandidate = { account: Account; site: Site };
export interface NexavlinksProgress {
    day: string; target: number; enabled: boolean; checked: boolean; spend: number | null;
    remoteSpend: number | null; usageSyncedAt: string | null; checkinKnown: boolean;
    estimated: boolean; localSpend: number; priority: boolean; syncedAt: string | null; warning: string;
}
interface Snapshot {
    day: string; identity: string; at: number; remoteSpend: number | null;
    usageKnown: boolean; checked: boolean; checkinKnown: boolean; usageSyncedAt: number | null; error: string;
}
const snapshots = new Map<number, Snapshot>();
const pending = new Map<string, Promise<Snapshot | null>>();
const syncIntervalMs = 30_000;

export function isNexavlinksSite(site: { url: string }) {
    try {
        const url = new URL(site.url);
        return url.protocol === 'https:' && !url.port && !url.username && !url.password
            && ['www.nexavlinks.com', 'nexavlinks.com'].includes(url.hostname);
    } catch { return false; }
}

export function nexavlinksDay(now = new Date()) {
    return new Date(now.getTime() + 8 * 3_600_000).toISOString().slice(0, 10);
}

function dayRange() {
    const day = nexavlinksDay();
    const start = new Date(`${day}T00:00:00+08:00`);
    return { day, start: formatUtcSqlDateTime(start), end: formatUtcSqlDateTime(new Date(start.getTime() + 86_400_000)) };
}

function checkedToday(value: string | null, day: string) {
    const parsed = parseStoredUtcDateTime(value);
    return !!parsed && nexavlinksDay(parsed) === day;
}

function canTrack(account: Account) {
    return !!account.accessToken?.trim() && getCredentialModeFromExtraConfig(account.extraConfig) !== 'apikey';
}

function fingerprint(account: Account) {
    return createHash('sha256').update(`${account.siteId}:${account.accessToken}`).digest('hex');
}

export async function getNexavlinksSettings() {
    const row = await db.select().from(schema.settings).where(eq(schema.settings.key, settingKey)).get();
    let value;
    try { value = JSON.parse(row?.value || '{}'); } catch { value = {}; }
    return { enabled: value?.enabled !== false, target: Number.isFinite(value?.target) && value.target > 0 ? value.target : 0.4, timeZone: 'Asia/Shanghai' };
}

export async function saveNexavlinksSettings(value: { enabled?: unknown; target?: unknown }) {
    if (typeof value?.enabled !== 'boolean' || typeof value?.target !== 'number'
        || !Number.isFinite(value.target) || value.target <= 0 || value.target > 1000) {
        throw new Error('请设置开关，并填写大于 0、不超过 1000 的每日目标额度。');
    }
    await upsertSetting(settingKey, { enabled: value.enabled, target: value.target });
    return getNexavlinksSettings();
}

async function localSpend(ids: number[], range: ReturnType<typeof dayRange>) {
    if (!ids.length) return new Map();
    const rows = await db.select({ accountId: schema.proxyLogs.accountId, cost: sql`coalesce(sum(${schema.proxyLogs.estimatedCost}), 0)` })
        .from(schema.proxyLogs).where(and(inArray(schema.proxyLogs.accountId, ids), gte(schema.proxyLogs.createdAt, range.start), lt(schema.proxyLogs.createdAt, range.end)))
        .groupBy(schema.proxyLogs.accountId).all();
    return new Map(rows.map(row => [row.accountId, Math.max(0, Number(row.cost) || 0)]));
}

// 只向固定站点发送登录凭证；拒绝重定向，避免凭证被带到其他域名。
async function readUpstream(account: Account, site: Site, path: string) {
    if (!isNexavlinksSite(site)) throw new Error('仅支持 NexaVlinks。');
    const token = account.accessToken.trim().replace(/^Bearer\s+/i, '');
    const headers: Record<string, string> = { 'X-User-UI-Request': '1' };
    if (token.includes('=')) headers.Cookie = token.replace(/^Cookie:\s*/i, '');
    else headers.Authorization = `Bearer ${token}`;
    try {
        const url = new URL(`/api/v1${path}`, 'https://www.nexavlinks.com');
        url.searchParams.set('timezone', 'Asia/Shanghai');
        const response = await fetch(url.href, withSiteRecordProxyRequestInit(site, {
            headers, redirect: 'error', signal: AbortSignal.timeout(4000),
        }, resolveProxyUrlFromExtraConfig(account.extraConfig)));
        if (!response.ok) { await response.body?.cancel(); throw new Error(); }
        const result = await response.json() as { code?: number; data?: Record<string, unknown> };
        if (result?.code !== 0 || !result.data || typeof result.data !== 'object') throw new Error();
        return result.data;
    } catch {
        // 不向日志或前端返回可能含有凭证的上游响应正文。
        throw new Error('无法读取 NexaVlinks 数据，请检查登录凭证、网络或代理。');
    }
}

async function syncAccount(account: Account, site: Site, force = false): Promise<Snapshot | null> {
    const day = nexavlinksDay();
    const identity = fingerprint(account);
    const current = snapshots.get(account.id);
    if (!force && current?.day === day && current.identity === identity && Date.now() - current.at < syncIntervalMs) return current;
    const key = `${account.id}:${identity}`;
    const inFlight = pending.get(key);
    if (inFlight) return inFlight;
    const task = (async () => {
        const [usage, checkin] = await Promise.allSettled([
            readUpstream(account, site, '/usage/dashboard/stats'),
            readUpstream(account, site, '/check-in'),
        ]);
        if (day !== nexavlinksDay()) return null;
        const rawCost = usage.status === 'fulfilled' ? usage.value.today_actual_cost : undefined;
        const cost = typeof rawCost === 'number' || (typeof rawCost === 'string' && rawCost.trim()) ? Number(rawCost) : NaN;
        const costKnown = Number.isFinite(cost) && cost >= 0;
        const checkinKnown = checkin.status === 'fulfilled' && typeof checkin.value.checked_in_today === 'boolean';
        const previous = current?.day === day && current.identity === identity ? current : null;
        const checked = (checkin.status === 'fulfilled' && checkin.value.checked_in_today === true) || previous?.checked === true;
        const value = {
            day, identity, at: Date.now(), remoteSpend: costKnown ? cost : previous?.remoteSpend ?? null,
            usageKnown: costKnown,
            checked, checkinKnown, usageSyncedAt: costKnown ? Date.now() : previous?.usageSyncedAt ?? null,
            error: [!costKnown ? (previous?.remoteSpend != null ? '网站消费刷新失败，显示上次同步值' : '网站今日消费暂未读取') : '',
                !checkinKnown ? '网站签到状态暂未核实，请刷新进度' : ''].filter(Boolean).join('；'),
        };
        // 凭证在请求途中被重新绑定时，不能把旧账号的结果写回新绑定。
        const latest = await db.select().from(schema.accounts).where(eq(schema.accounts.id, account.id)).get();
        if (!latest || fingerprint(latest) !== identity) return null;
        if (checked && !checkedToday(latest.lastCheckinAt, day)) {
            const timestamp = new Date();
            if (nexavlinksDay(timestamp) !== day) return null;
            await db.update(schema.accounts).set({ lastCheckinAt: timestamp.toISOString() })
                .where(and(eq(schema.accounts.id, account.id), eq(schema.accounts.accessToken, account.accessToken), eq(schema.accounts.siteId, account.siteId))).run();
            await db.insert(schema.checkinLogs).values({ accountId: account.id, status: 'success', message: 'NexaVlinks 原站已确认今日签到（人工完成）', createdAt: formatUtcSqlDateTime(timestamp) }).run();
        }
        snapshots.set(account.id, value);
        return value;
    })().finally(() => pending.delete(key));
    pending.set(key, task);
    return task;
}

export async function getNexavlinksProgress(rows: NexavlinksCandidate[], { sync = true, force = false } = {}): Promise<Map<number, NexavlinksProgress | null>> {
    const relevant = rows.filter(row => isNexavlinksSite(row.site) && canTrack(row.account));
    if (!relevant.length) return new Map();
    const unique = [...new Map(relevant.map(row => [row.account.id, row])).values()];
    const ids = unique.map(row => row.account.id);
    const range = dayRange();
    const [costs, settings, latestAccounts] = await Promise.all([
        localSpend(ids, range), getNexavlinksSettings(),
        db.select().from(schema.accounts).where(inArray(schema.accounts.id, ids)).all(),
    ]);
    const accountsById = new Map<number, Account>(latestAccounts.map((account: Account) => [account.id, account]));
    const progress = new Map<number, NexavlinksProgress | null>(await Promise.all(unique.map(async (row): Promise<[number, NexavlinksProgress | null]> => {
        const account = accountsById.get(row.account.id);
        if (!account || account.siteId !== row.site.id || !canTrack(account)) return [row.account.id, null];
        const local = costs.get(account.id) || 0;
        let snapshot: Snapshot | null | undefined = snapshots.get(account.id);
        if (sync && (account.status === 'active' || (force && account.status !== 'disabled')) && row.site.status !== 'disabled') snapshot = await syncAccount(account, row.site, force);
        if (snapshot?.day !== range.day || snapshot.identity !== fingerprint(account)) snapshot = null;
        const remoteKnown = snapshot?.remoteSpend != null;
        // 展示与优先切换统一使用网站实际消费，无法核实则回退原路由。
        const spend = remoteKnown ? snapshot!.remoteSpend : null;
        const checked = checkedToday(account.lastCheckinAt, range.day) || snapshot?.checked === true;
        const eligible = settings.enabled && !checked && snapshot?.checkinKnown === true && snapshot?.usageKnown === true
            && spend !== null && spend < settings.target && account.status === 'active' && row.site.status !== 'disabled';
        return [account.id, {
            day: range.day, target: settings.target, enabled: settings.enabled, checked, spend,
            remoteSpend: remoteKnown ? snapshot!.remoteSpend : null,
            usageSyncedAt: snapshot?.usageSyncedAt ? new Date(snapshot.usageSyncedAt).toISOString() : null,
            checkinKnown: checked || snapshot?.checkinKnown === true,
            estimated: false, localSpend: local, priority: eligible,
            syncedAt: snapshot ? new Date(snapshot.at).toISOString() : null,
            warning: snapshot?.error || (!snapshot ? '网站消费和签到状态尚未同步，请刷新进度' : ''),
        }];
    })));
    return range.day === nexavlinksDay() ? progress : getNexavlinksProgress(rows, { sync, force });
}

export async function selectNexavlinksPriority<T extends NexavlinksCandidate>(candidates: T[]): Promise<T[]> {
    const settings = await getNexavlinksSettings();
    if (!settings.enabled || !candidates.some(row => isNexavlinksSite(row.site))) return [];
    const progress = await getNexavlinksProgress(candidates);
    const eligible = candidates.filter(row => progress.get(row.account.id)?.priority);
    if (!eligible.length) return [];
    // 同一账户的所有 Key 共用进度；优先把一个账号用到目标再换下一个。
    const accountId = Math.min(...eligible.map(row => row.account.id));
    return eligible.filter(row => row.account.id === accountId);
}

export async function getNexavlinksAccount(id: number) {
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('账号 ID 无效。');
    const row = await db.select({ account: schema.accounts, site: schema.sites }).from(schema.accounts)
        .innerJoin(schema.sites, eq(schema.accounts.siteId, schema.sites.id)).where(eq(schema.accounts.id, id)).get();
    if (!row || !isNexavlinksSite(row.site) || !canTrack(row.account)) throw new Error('需要 NexaVlinks 登录账号。');
    if (row.account.status === 'disabled' || row.site.status === 'disabled') throw new Error('账号或站点已禁用。');
    return row;
}
