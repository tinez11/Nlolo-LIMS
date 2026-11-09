import { can } from '@/auth/access';
import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getDispatchSummary, resendDispatch, searchDispatches } from '@/api/communications';
import type { DispatchSummary, NotificationDispatchView } from '@/api/types';
import { FilterChip } from '@/components/FilterChip';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';
import { ChannelStatusBar } from './ChannelStatusBar';
import { DispatchStatusBadge } from './DispatchStatusBadge';
import { messageName } from './messageCatalog';

const PERIODS = [
  { days: 1, label: 'Today' },
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
];

const STATUS_FILTERS = [
  { value: '', label: 'All' },
  { value: 'SENT', label: 'Sent' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'NOT_SENT', label: 'Not sent' },
];

/** The newest rows the register shows at once; the rest are reached by narrowing. */
const PAGE = 200;

/**
 * What the platform has said to customers (2026-11-08 redesign, the user: at 10,000 customers this is a million rows a
 * year). It opens on the last week -- never "everything" -- with a summary of what was sent and why the rest were not,
 * filters that run in the database, and each message's exact text. A failed or not-sent message can be sent again.
 */
export function MessagesPage() {
  const [params, setParams] = useSearchParams();
  const days = Number(params.get('days') ?? 7) || 7;
  const status = params.get('status') ?? '';
  const channel = params.get('channel') ?? '';
  const templateKey = params.get('message') ?? '';
  const policy = params.get('policy') ?? '';

  // The rows and the filters they were read for: a change of filter shows the skeleton until its own rows arrive.
  const queryKey = JSON.stringify([days, status, channel, templateKey, policy]);
  const [result, setResult] = useState<{ key: string; rows: NotificationDispatchView[] } | null>(null);
  const rows = result?.key === queryKey ? result.rows : null;
  const [summary, setSummary] = useState<DispatchSummary | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [policyText, setPolicyText] = useState(policy);
  const [open, setOpen] = useState<string | null>(null);

  const load = useCallback(() => {
    searchDispatches({ sinceDays: days, status, channel, templateKey, policyNumber: policy, limit: PAGE })
      .then((found) => { setError(null); setResult({ key: queryKey, rows: found }); }, (e: ApiError) => setError(e));
    getDispatchSummary(days).then(setSummary, () => setSummary(null));
  }, [days, status, channel, templateKey, policy, queryKey]);

  useEffect(() => {
    load();
  }, [load]);

  function set(name: string, value: string | number | null) {
    const next = new URLSearchParams(params);
    if (value === null || value === '') next.delete(name);
    else next.set(name, String(value));
    setParams(next);
  }

  const keys = summary ? [...new Set((rows ?? []).map((r) => r.templateKey).filter(Boolean))] as string[] : [];

  return (
    <>
      <PageHeader title="Messages sent" description="What customers were told, and whether it reached them." />
      <div className="space-y-4 px-6 pb-8">
        <ChannelStatusBar />
        {summary && <Summary summary={summary} onShow={(s) => set('status', s)} />}

        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-end gap-3 border-b border-border px-3 py-3">
            <div className="flex flex-wrap gap-1.5" aria-label="Period">
              {PERIODS.map((p) => <FilterChip key={p.days} label={p.label} active={days === p.days} onClick={() => set('days', p.days)} />)}
            </div>
            <div className="flex flex-wrap gap-1.5" aria-label="Status">
              {STATUS_FILTERS.map((s) => <FilterChip key={s.value} label={s.label} active={status === s.value} onClick={() => set('status', s.value)} />)}
            </div>
            <label className="text-xs text-label">
              Channel
              <Select className="mt-1" value={channel} onChange={(e) => set('channel', e.target.value)}>
                <option value="">SMS and email</option>
                <option value="SMS">SMS</option>
                <option value="EMAIL">Email</option>
              </Select>
            </label>
            <label className="text-xs text-label">
              Message
              <Select className="mt-1" value={templateKey} onChange={(e) => set('message', e.target.value)}>
                <option value="">Every message</option>
                {[...new Set([templateKey, ...keys].filter(Boolean))].map((k) => <option key={k} value={k}>{messageName(k)}</option>)}
              </Select>
            </label>
            <form className="ml-auto flex items-end gap-2" onSubmit={(e) => { e.preventDefault(); set('policy', policyText.trim().toUpperCase()); }}>
              <label className="text-xs text-label">
                Policy number
                <Input className="mt-1 w-40" placeholder="POL-…" value={policyText} onChange={(e) => setPolicyText(e.target.value)} />
              </label>
              <Button type="submit" size="sm" variant="outline">Find</Button>
            </form>
          </div>

          {error && rows === null ? (
            <ErrorPanel error={error} onRetry={load} />
          ) : rows === null ? (
            <TableSkeleton columns={5} />
          ) : rows.length === 0 ? (
            <EmptyState title="No messages" description="Nothing matches in this period. Try a longer period or fewer filters." />
          ) : (
            <>
              <ul className="divide-y divide-border" aria-label="Messages">
                {rows.map((d) => (
                  <MessageRow key={d.dispatchId} message={d} open={open === d.dispatchId}
                    onToggle={() => setOpen(open === d.dispatchId ? null : d.dispatchId ?? null)} onResent={load} />
                ))}
              </ul>
              {rows.length >= PAGE && (
                <p className="border-t border-border px-4 py-2 text-xs text-muted-foreground">
                  Showing the newest {PAGE}. Narrow by status, message or policy to see older ones.
                </p>
              )}
            </>
          )}
        </div>
      </div>
    </>
  );
}

/** The period at a glance: sent, failed, not sent, and the commonest reasons -- each a way into the list. */
function Summary({ summary, onShow }: { summary: DispatchSummary; onShow: (status: string) => void }) {
  const total = (s: string) => summary.counts.filter((c) => c.status === s).reduce((n, c) => n + c.count, 0);
  const tiles = [
    { status: 'SENT', label: 'Sent', tone: 'text-status-success-fg' },
    { status: 'FAILED', label: 'Failed', tone: 'text-status-danger-fg' },
    { status: 'NOT_SENT', label: 'Not sent (switched off)', tone: 'text-muted-foreground' },
  ];
  return (
    <section className="grid grid-cols-1 gap-3 lg:grid-cols-[repeat(3,minmax(0,1fr))_2fr]" aria-label="Summary">
      {tiles.map((t) => (
        <button key={t.status} type="button" onClick={() => onShow(t.status)}
          className="rounded-xl border border-border bg-surface p-4 text-left shadow-card hover:bg-hover">
          <span className="block text-xs text-label">{t.label}</span>
          <span className={`text-display font-semibold tabular-nums ${t.tone}`}>{total(t.status).toLocaleString('en-US')}</span>
        </button>
      ))}
      <div className="rounded-xl border border-border bg-surface p-4 shadow-card">
        <span className="block text-xs text-label">Why messages did not go</span>
        {summary.reasons.length === 0 ? (
          <p className="mt-1 text-sm">Every message went.</p>
        ) : (
          <ul className="mt-1 space-y-1 text-sm">
            {summary.reasons.slice(0, 4).map((r, i) => (
              <li key={i} className="flex justify-between gap-3">
                <span className="min-w-0 truncate" title={r.reason ?? ''}>{r.channel === 'EMAIL' ? 'Email' : 'SMS'}: {r.reason ?? 'no reason recorded'}</span>
                <span className="shrink-0 tabular-nums text-muted-foreground">{r.count.toLocaleString('en-US')}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function MessageRow({ message: d, open, onToggle, onResent }: {
  message: NotificationDispatchView;
  open: boolean;
  onToggle: () => void;
  onResent: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [resent, setResent] = useState<NotificationDispatchView | null>(null);
  const mayResend = can('communications.templates') && (d.status === 'FAILED' || d.status === 'NOT_SENT') && !!d.body;

  async function resend() {
    setBusy(true);
    setError(null);
    try {
      setResent(await resendDispatch(d.dispatchId ?? ''));
      onResent();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <li>
      <button type="button" onClick={onToggle} aria-expanded={open}
        className="grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 px-4 py-3 text-left hover:bg-hover sm:grid-cols-[9rem_minmax(0,1.2fr)_minmax(0,1fr)_5rem_7rem]">
        <span className="text-xs tabular-nums text-muted-foreground">{formatInstant(d.createdAt)}</span>
        <span className="min-w-0 truncate font-medium">{messageName(d.templateKey)}</span>
        <span className="min-w-0 truncate text-sm">
          {d.partyId ? <PartyName partyId={d.partyId} /> : '—'}
          {d.policyNumber && <span className="ml-2 font-mono text-xs text-muted-foreground">{d.policyNumber}</span>}
        </span>
        <span className="text-xs">{d.channel === 'EMAIL' ? 'Email' : 'SMS'}</span>
        <span><DispatchStatusBadge status={d.status} /></span>
      </button>
      {open && (
        <div className="space-y-2 bg-surface-muted px-4 pb-4 pt-1">
          {d.failureReason && (
            <p className="text-sm"><span className="text-label">{d.status === 'NOT_SENT' ? 'Why not sent:' : 'Why it failed:'}</span> {d.failureReason}</p>
          )}
          <p className="text-xs text-label">The text {d.status === 'SENT' ? 'sent' : 'worded'}</p>
          <p className="max-w-prose whitespace-pre-wrap rounded-lg bg-surface px-4 py-3 text-sm">{d.body ?? 'Not kept for messages from before November 2026.'}</p>
          {error && <InlineError error={error} />}
          {resent ? (
            <p role="status" className="text-sm">Sent again: <DispatchStatusBadge status={resent.status} />{resent.failureReason ? ` — ${resent.failureReason}` : ''}</p>
          ) : mayResend && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => void resend()}>
              {busy ? 'Sending…' : 'Send again'}
            </Button>
          )}
        </div>
      )}
    </li>
  );
}
