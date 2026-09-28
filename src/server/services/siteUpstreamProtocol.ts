import { eq, inArray } from 'drizzle-orm';
import { db, schema } from '../db/index.js';
import { upsertSetting } from '../db/upsertSetting.js';

export type SiteUpstreamProtocol = 'auto' | 'responses';
const settingKey = (siteId: number) => `site_upstream_protocol:${siteId}`;
const nativePlatforms = new Set(['claude', 'gemini', 'codex', 'gemini-cli', 'antigravity']);

export function supportsForcedResponses(platform: unknown) {
    return !nativePlatforms.has(String(platform || '').trim().toLowerCase());
}

function parseProtocol(value: string | null | undefined): SiteUpstreamProtocol {
    if (value == null) return 'auto';
    // 配置损坏时停止请求，避免静默切回其他协议。
    const protocol = JSON.parse(value);
    if (protocol !== 'auto' && protocol !== 'responses') {
        throw new Error('Invalid site upstream protocol setting');
    }
    return protocol;
}

export async function getSiteUpstreamProtocol(siteId: number | undefined) {
    if (typeof siteId !== 'number' || !Number.isInteger(siteId) || siteId <= 0) return 'auto';
    const row = await db.select({ value: schema.settings.value }).from(schema.settings)
        .where(eq(schema.settings.key, settingKey(siteId))).get();
    return parseProtocol(row?.value);
}

export async function loadSiteUpstreamProtocols(siteIds: number[]): Promise<Map<number, SiteUpstreamProtocol>> {
    if (!siteIds.length) return new Map();
    const rows = await db.select().from(schema.settings)
        .where(inArray(schema.settings.key, siteIds.map(settingKey))).all();
    return new Map(rows.map((row) => [Number(row.key.slice(row.key.indexOf(':') + 1)), parseProtocol(row.value)]));
}

export async function saveSiteUpstreamProtocol(siteId: number, protocol: SiteUpstreamProtocol, tx: typeof db) {
    if (protocol === 'auto') {
        await deleteSiteUpstreamProtocol(siteId, tx);
        return;
    }
    await upsertSetting(settingKey(siteId), protocol, tx);
}

export async function deleteSiteUpstreamProtocol(siteId: number, tx: typeof db) {
    await tx.delete(schema.settings).where(eq(schema.settings.key, settingKey(siteId))).run();
}
