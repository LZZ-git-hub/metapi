import * as routeRefreshWorkflow from '../services/routeRefreshWorkflow.js';
import { proxyChannelCoordinator } from '../services/proxyChannelCoordinator.js';
import { canRetryProxyChannel } from '../services/proxyChannelRetry.js';
import type { DownstreamRoutingPolicy } from '../services/downstreamPolicyTypes.js';
import { tokenRouter } from '../services/tokenRouter.js';

type SelectedChannel = Awaited<ReturnType<typeof tokenRouter.selectChannel>>;
const sessionSelectionQueues = new Map<string, Promise<SelectedChannel>>();

export const TESTER_FORCED_CHANNEL_HEADER = 'x-metapi-tester-forced-channel-id';
export const TESTER_REQUEST_HEADER = 'x-metapi-tester-request';

function headerValueEquals(
  headers: Record<string, unknown> | undefined,
  expectedKey: string,
  expectedValue: string,
): boolean {
  if (!headers) return false;
  const normalizedExpectedKey = expectedKey.trim().toLowerCase();
  const normalizedExpectedValue = expectedValue.trim().toLowerCase();
  for (const [rawKey, rawValue] of Object.entries(headers)) {
    if (rawKey.trim().toLowerCase() !== normalizedExpectedKey) continue;
    if (typeof rawValue === 'string' && rawValue.trim().toLowerCase() === normalizedExpectedValue) {
      return true;
    }
  }
  return false;
}

function isLoopbackClientIp(value: string | null | undefined): boolean {
  const trimmed = (value || '').trim();
  if (!trimmed) return false;
  if (trimmed === '::1' || trimmed === '127.0.0.1') return true;
  if (trimmed.startsWith('::ffff:')) {
    return trimmed.slice('::ffff:'.length).trim() === '127.0.0.1';
  }
  return false;
}

export function normalizeForcedChannelId(value: unknown): number | null {
  const numeric = typeof value === 'number'
    ? value
    : typeof value === 'string' && value.trim()
      ? Number(value.trim())
      : NaN;
  if (!Number.isSafeInteger(numeric) || numeric <= 0) return null;
  return numeric;
}

type TesterRequestInput = {
  headers?: Record<string, unknown>;
  clientIp?: string | null;
};

export function isTrustedTesterRequest(input?: TesterRequestInput): boolean {
  if (!input) return false;
  if (!isLoopbackClientIp(input.clientIp)) return false;
  return headerValueEquals(input.headers, TESTER_REQUEST_HEADER, '1');
}

export function getTesterForcedChannelId(input?: TesterRequestInput): number | null {
  if (!isTrustedTesterRequest(input)) return null;
  const headers = input?.headers;
  if (!headers) return null;
  for (const [rawKey, rawValue] of Object.entries(headers)) {
    if (rawKey.trim().toLowerCase() !== TESTER_FORCED_CHANNEL_HEADER) continue;
    return normalizeForcedChannelId(rawValue);
  }
  return null;
}

export function buildForcedChannelUnavailableMessage(forcedChannelId?: number | null): string {
  const normalizedForcedChannelId = normalizeForcedChannelId(forcedChannelId);
  if (normalizedForcedChannelId === null) {
    return 'No available channels for this model';
  }
  return `指定通道 #${normalizedForcedChannelId} 当前不可用，固定通道模式不会自动切换其他通道`;
}

export function canRetryChannelSelection(retryCount: number, forcedChannelId?: number | null): boolean {
  if (normalizeForcedChannelId(forcedChannelId) !== null) return false;
  return canRetryProxyChannel(retryCount);
}

type ChannelSelectionInput = {
  requestedModel: string;
  downstreamPolicy: DownstreamRoutingPolicy;
  excludeChannelIds: number[];
  retryCount: number;
  stickySessionKey?: string | null;
  forcedChannelId?: number | null;
};

export async function selectProxyChannelForAttempt(input: ChannelSelectionInput): Promise<SelectedChannel> {
  const key = input.stickySessionKey;
  if (!key || normalizeForcedChannelId(input.forcedChannelId) !== null) {
    return selectChannelForAttempt(input);
  }
  // 仅串行化同一会话的选路，先建立绑定，避免并发首请求各选一个 Key。
  const previous = sessionSelectionQueues.get(key) || Promise.resolve();
  const pending = previous.catch(() => {}).then(() => selectChannelForAttempt(input));
  sessionSelectionQueues.set(key, pending);
  try {
    return await pending;
  } finally {
    if (sessionSelectionQueues.get(key) === pending) sessionSelectionQueues.delete(key);
  }
}

async function selectChannelForAttempt(input: ChannelSelectionInput): Promise<SelectedChannel> {
  const normalizedForcedChannelId = normalizeForcedChannelId(input.forcedChannelId);
  if (normalizedForcedChannelId !== null) {
    if (input.retryCount > 0) return null;
    return await tokenRouter.selectPreferredChannel(
      input.requestedModel,
      normalizedForcedChannelId,
      input.downstreamPolicy,
      input.excludeChannelIds,
    );
  }

  let selected: SelectedChannel = null;
  let routingReason = input.stickySessionKey
    ? '首次分配（新会话或绑定已过期）'
    : '常规分配（无稳定会话标识）';
  if (input.retryCount > 0) routingReason = '故障切换（此前尝试的通道不可用）';
  let refreshedRoutes = false;

  const refreshRoutesForFirstAttempt = async (): Promise<boolean> => {
    if (input.retryCount > 0 || refreshedRoutes) return false;
    refreshedRoutes = true;
    try {
      await routeRefreshWorkflow.refreshModelsAndRebuildRoutes();
      return true;
    } catch (error) {
      console.warn('[proxy/surface] failed to refresh routes after empty selection', error);
      return false;
    }
  };

  if (input.retryCount === 0 && input.stickySessionKey) {
    const preferredChannelId = proxyChannelCoordinator.getStickyChannelId(input.stickySessionKey);
    if (preferredChannelId && !input.excludeChannelIds.includes(preferredChannelId)) {
      selected = await tokenRouter.selectPreferredChannel(
        input.requestedModel,
        preferredChannelId,
        input.downstreamPolicy,
        input.excludeChannelIds,
        true,
      );
      if (!selected) {
        await refreshRoutesForFirstAttempt();
        selected = await tokenRouter.selectPreferredChannel(
          input.requestedModel,
          preferredChannelId,
          input.downstreamPolicy,
          input.excludeChannelIds,
          true,
        );
        if (!selected) {
          proxyChannelCoordinator.clearStickyChannel(input.stickySessionKey, preferredChannelId);
        }
      }
      routingReason = '故障切换（原会话通道不可用）';
      if (selected) {
        routingReason = selected.channel.id === preferredChannelId
          ? '会话复用' : '站点优先规则切换';
      }
    }
  }

  if (!selected) {
    selected = input.retryCount === 0
      ? await tokenRouter.selectChannel(input.requestedModel, input.downstreamPolicy)
      : await tokenRouter.selectNextChannel(
        input.requestedModel,
        input.excludeChannelIds,
        input.downstreamPolicy,
      );
  }

  if (!selected && input.retryCount === 0 && !refreshedRoutes) {
    await refreshRoutesForFirstAttempt();
    selected = await tokenRouter.selectChannel(input.requestedModel, input.downstreamPolicy);
  }

  if (!selected) return null;
  proxyChannelCoordinator.bindStickyChannel(input.stickySessionKey, selected.channel.id);
  return { ...selected, routingReason };
}
