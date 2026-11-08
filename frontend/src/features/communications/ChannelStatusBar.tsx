import { useEffect, useState } from 'react';
import { getChannels } from '@/api/communications';
import type { ChannelStatus } from '@/api/types';

const WORDS: Record<string, Record<string, { label: string; tone: 'ok' | 'warn' | 'off' }>> = {
  SMS: {
    LIVE: { label: 'live — reaching customers', tone: 'ok' },
    LIMITED: { label: 'live to test numbers only', tone: 'warn' },
    TEST: { label: 'test gateway only — no customer receives it', tone: 'warn' },
    OFF: { label: 'off — not reaching customers', tone: 'off' },
  },
  EMAIL: {
    LIVE: { label: 'live — reaching customers', tone: 'ok' },
    LIMITED: { label: 'live to test addresses only', tone: 'warn' },
    TEST: { label: 'test mailbox only — nothing leaves this machine', tone: 'warn' },
    OFF: { label: 'not in use — customers are reached by SMS', tone: 'off' },
  },
};

const TONE = {
  ok: 'bg-status-success-bg text-status-success-fg',
  warn: 'bg-status-warning-bg text-status-warning-fg',
  off: 'bg-status-danger-bg text-status-danger-fg',
};

/**
 * Whether customers are actually reached (2026-11-08). Before this, an SMS switched off showed as hundreds of
 * failures and nothing said that no customer was being told anything.
 */
export function ChannelStatusBar() {
  const [channels, setChannels] = useState<ChannelStatus[] | null>(null);
  useEffect(() => {
    getChannels().then(setChannels, () => setChannels([]));
  }, []);
  if (!channels || channels.length === 0) return null;
  return (
    <div className="flex flex-wrap gap-2" role="status" aria-label="How messages reach customers">
      {channels.map((c) => {
        const w = WORDS[c.channel]?.[c.mode] ?? { label: c.mode, tone: 'warn' as const };
        return (
          <span key={c.channel} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${TONE[w.tone]}`}>
            <span className="font-semibold">{c.channel === 'SMS' ? 'SMS' : 'Email'}:</span> {w.label}
          </span>
        );
      })}
    </div>
  );
}
