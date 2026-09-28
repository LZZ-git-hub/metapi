import { eq, inArray } from 'drizzle-orm';
import { db, schema } from '../db/index.js';
import { upsertSetting } from '../db/upsertSetting.js';
import { getPreferredAccountToken, isMaskedTokenValue } from './accountTokenService.js';
import { getCredentialModeFromExtraConfig, mergeAccountExtraConfig, resolveProxyUrlFromExtraConfig } from './accountExtraConfig.js';
import { withSiteRecordProxyRequestInit } from './siteProxy.js';

export type SiteBalanceQueryMode = 'default' | 'usage';
type Account = typeof schema.accounts.$inferSelect;
type Site = typeof schema.sites.$inferSelect;
const settingKey = (siteId: number) => `site_balance_query:${siteId}`;
function parseMode(value: string | null | undefined): SiteBalanceQueryMode {
    const mode = value == null ? 'default' : JSON.parse(value);
    if (mode !== 'default' && mode !== 'usage') throw new Error('余额查询方式配置无效');
    return mode;
}
export async function getSiteBalanceQuery(siteId: number) {
    const row = await db.select({ value: schema.settings.value }).from(schema.settings)
        .where(eq(schema.settings.key, settingKey(siteId))).get();
    return parseMode(row?.value);
}
export async function loadSiteBalanceQueries(siteIds: number[]): Promise<Map<number, SiteBalanceQueryMode>> {
    if (!siteIds.length) return new Map();
    const rows = await db.select().from(schema.settings)
        .where(inArray(schema.settings.key, [...new Set(siteIds)].map(settingKey))).all();
    return new Map(rows.map((row) => [Number(row.key.split(':')[1]), parseMode(row.value)]));
}
export async function saveSiteBalanceQuery(siteId: number, mode: SiteBalanceQueryMode, tx: typeof db) {
    if (mode === 'default') return deleteSiteBalanceQuery(siteId, tx);
    await upsertSetting(settingKey(siteId), mode, tx);
}
export async function deleteSiteBalanceQuery(siteId: number, tx: typeof db) {
    await tx.delete(schema.settings).where(eq(schema.settings.key, settingKey(siteId))).run();
}
export function getStoredBalanceUnit(extraConfig: unknown) {
    try {
        const parsed = (typeof extraConfig === 'string' ? JSON.parse(extraConfig) : extraConfig) as { balanceUnit?: unknown } | null;
        const unit = parsed?.balanceUnit;
        return typeof unit === 'string' && unit.trim() ? unit.trim() : 'USD';
    } catch {
        return 'USD';
    }
}
function parseUsageBalance(payload: any) {
    // 0 是有效余额；空字符串、布尔值和缺失字段不能转换成 0。
    const balance = [payload?.remaining, payload?.quota?.remaining, payload?.balance]
        .map((value) => typeof value === 'number' ? value
            : typeof value === 'string' && value.trim() ? Number(value) : NaN)
        .find(Number.isFinite);
    if (balance === undefined) throw new Error('余额响应缺少有效的 remaining、quota.remaining 或 balance');
    const unit = [payload?.unit, payload?.quota?.unit]
        .find((value) => typeof value === 'string' && value.trim())?.trim() || 'USD';
    return { balance, unit };
}
export async function refreshUsageBalance(account: Account, site: Site) {
    const preferred = await getPreferredAccountToken(account.id);
    const apiKeyMode = getCredentialModeFromExtraConfig(account.extraConfig) === 'apikey';
    const apiKey = (preferred?.token || account.apiToken || (apiKeyMode ? account.accessToken : '') || '')
        .trim().replace(/^Bearer\s+/i, '').trim();
    if (!apiKey || isMaskedTokenValue(apiKey)) {
        throw new Error('该连接没有可用的 API Key，请在账号令牌管理中设置默认令牌');
    }
    const base = site.url.trim().replace(/\/+$/, '');
    const url = /\/v1$/i.test(base) ? base + '/usage' : base + '/v1/usage';
    const { fetch } = await import('undici');
    let response;
    let payload: any;
    try {
        response = await fetch(url, withSiteRecordProxyRequestInit(site, {
            method: 'GET',
            headers: { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' },
            signal: AbortSignal.timeout(15000),
            redirect: 'error',
        }, resolveProxyUrlFromExtraConfig(account.extraConfig)));
        if (!response.ok) {
            await response.body?.cancel();
            throw new Error('http');
        }
        payload = await response.json();
    } catch {
        // 不回传上游正文或请求对象，避免错误提示泄露凭证。
        throw new Error(response && !response.ok
            ? `余额查询失败（HTTP ${response.status}），请检查 API Key 和站点接口`
            : '余额查询失败，请检查网络、超时或接口是否返回有效 JSON');
    }
    if (payload?.success === false || payload?.error) throw new Error('余额接口返回失败状态');
    const info = parseUsageBalance(payload);
    // 此接口只提供余额，不覆盖原有消费、总额度或登录会话健康状态。
    const latest = await db.select({ extraConfig: schema.accounts.extraConfig }).from(schema.accounts)
        .where(eq(schema.accounts.id, account.id)).get();
    if (!latest) throw new Error('账号已删除');
    await db.update(schema.accounts).set({
        balance: info.balance,
        extraConfig: mergeAccountExtraConfig(latest.extraConfig, { balanceUnit: info.unit }),
        lastBalanceRefresh: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
    }).where(eq(schema.accounts.id, account.id)).run();
    return { ...info, used: account.balanceUsed ?? 0, quota: account.quota ?? 0 };
}
