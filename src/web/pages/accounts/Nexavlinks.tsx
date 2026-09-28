import { useEffect, useState } from 'react';
import { api } from '../../api.js';
import { useToast } from '../../components/Toast.js';
import './nexavlinks.css';

type Progress = {
  enabled: boolean;
  target: number;
  checked: boolean;
  checkinKnown: boolean;
  priority: boolean;
  remoteSpend: number | null;
  usageSyncedAt?: string;
  warning?: string;
};

type Account = { id: number; nexavlinks?: Progress };

export function NexaPriorityPanel({ onChanged }: { onChanged: () => void }) {
  const [settings, setSettings] = useState<{ enabled: boolean; target: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  useEffect(() => {
    let active = true;
    api.getNexaSettings().then((value) => {
      if (active) setSettings({ enabled: value.enabled, target: String(value.target) });
    }).catch(() => { if (active) setMessage('无法读取 NexaVlinks 设置，请刷新页面重试'); });
    return () => { active = false; };
  }, []);

  const save = async () => {
    if (!settings || busy) return;
    const target = Number(settings.target);
    if (!Number.isFinite(target) || target <= 0 || target > 1000) {
      setMessage('每日目标须大于 0 且不超过 1000');
      return;
    }
    setBusy(true);
    setMessage('');
    try {
      const saved = await api.saveNexaSettings({ enabled: settings.enabled, target });
      setSettings({ enabled: saved.enabled, target: String(saved.target) });
      setMessage('已保存');
      onChanged();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '保存失败');
    } finally { setBusy(false); }
  };

  return (
    <section className="card nexa-priority">
      <strong>NexaVlinks 签到消费优先</strong>
      <p>仅优先使用今天未签到、且今日消费不足目标的可用账号。同一账号多个 Key 共用进度，达到目标后换下一个；没有符合条件的账号时恢复原路由。按北京时间跨天。</p>
      {settings && <div className="nexa-priority-controls">
        <label><input type="checkbox" checked={settings.enabled} disabled={busy} onChange={(event) => setSettings({ ...settings, enabled: event.target.checked })} /> 启用优先分配</label>
        <label>每日目标 $ <input type="number" min="0.000001" max="1000" step="any" value={settings.target} disabled={busy} onChange={(event) => setSettings({ ...settings, target: event.target.value })} /></label>
        <button type="button" className="btn btn-soft-primary" disabled={busy} onClick={() => void save()}>{busy ? '保存中…' : '保存规则'}</button>
      </div>}
      <p>网站消费和签到状态缓存 30 秒，可通过“刷新进度”立即同步。数据未确认时恢复原路由；统计延迟、单次请求和并发可能使消费超出目标。</p>
      <p>云端签到请在原站登录对应账号并手动完成验证，再刷新进度。浏览器当前登录账号可能与此处账号不同，请核对后操作。</p>
      {message && <div role="status">{message}</div>}
    </section>
  );
}

export function NexaAccountUsage({ account }: { account: Account }) {
  const progress = account.nexavlinks;
  if (!progress) return null;
  const spend = typeof progress.remoteSpend === 'number' && Number.isFinite(progress.remoteSpend) ? Math.max(0, progress.remoteSpend) : null;
  const target = progress.target || 0.4;
  const detail = [
    '北京时间今日网站实际消费',
    progress.usageSyncedAt ? `同步时间 ${new Date(progress.usageSyncedAt).toLocaleTimeString()}` : '',
    !progress.enabled ? '优先规则已关闭' : progress.priority ? '符合优先条件' : '参与原路由',
    progress.warning,
  ].filter(Boolean).join('；');
  return <div className="nexa-usage" title={detail} tabIndex={0}>
    <span className={`badge ${progress.checked ? 'badge-success' : 'badge-muted'}`}>{progress.checked ? '已签到' : progress.checkinKnown ? '未签到' : '待同步'}</span>
    <span>{spend === null ? '—' : `$${spend.toFixed(4)}`} / ${target}</span>
    <progress aria-label="网站今日消费进度" max={target} value={spend === null ? 0 : Math.min(spend, target)} aria-valuetext={spend === null ? '尚未读取' : detail} />
  </div>;
}

export function NexaAccountCheckin({ account, onChanged }: { account: Account; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const sync = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const result = await api.syncNexaAccount(account.id);
      toast.info(result.progress?.warning || (result.progress?.checked ? '原站已确认今日签到' : '进度已更新，今日尚未签到'));
      onChanged();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '同步失败');
    } finally { setBusy(false); }
  };
  return <>
    <a className="btn btn-link btn-link-warning" href="https://www.nexavlinks.com/check-in" target="_blank" rel="noopener noreferrer" onClick={(event) => event.stopPropagation()}>原站签到</a>
    <button type="button" className="btn btn-link btn-link-primary" disabled={busy} onClick={(event) => { event.stopPropagation(); void sync(); }}>{busy ? '同步中…' : '刷新进度'}</button>
  </>;
}

export function formatAccountBalance(account: { balance?: number; balanceUnit?: string }) {
  const unit = account.balanceUnit || 'USD';
  const amount = Number(account.balance || 0).toFixed(2);
  return unit.toUpperCase() === 'USD' ? `$${amount}` : `${amount} ${unit}`;
}
