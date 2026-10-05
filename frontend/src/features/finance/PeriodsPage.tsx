import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { AccountingPeriodView } from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { approveReopenGates } from '@/gates/ledgerControlGates';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import { reopenSchema, type ReopenValues } from './registerForms';

const PERIOD = /^\d{4}-(0[1-9]|1[0-2])$/;

/** The last twelve months, newest first, as YYYY-MM. */
function lastTwelve(now: Date): string[] {
  const out: string[] = [];
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    out.push(`${d.getFullYear()}-${`${d.getMonth() + 1}`.padStart(2, '0')}`);
  }
  return out;
}

const untouched = (period: string): AccountingPeriodView => ({ period, status: 'OPEN' });

/**
 * Accounting periods (IFRS 17 spec §5.4). A period is OPEN until finance starts closing it -- from then it takes
 * only the platform's and the IFRS 17 engine's journals -- and LOCKED takes nothing. Reopening a locked period is
 * asked for with a reason and approved by a second person. The lock's own preconditions (earlier periods locked,
 * clearing accounts at zero) are the server's; its refusal is shown on the row.
 */
export function PeriodsPage() {
  const auth = useAuth();
  const viewerSubject = readIdentity(auth.user?.access_token)?.subject ?? undefined;
  const periods = useLedgerControlsStore((s) => s.periods);
  const loadPeriods = useLedgerControlsStore((s) => s.loadPeriods);
  const [extra, setExtra] = useState<string[]>([]);
  const [lookup, setLookup] = useState('');

  useEffect(() => {
    void loadPeriods();
  }, [loadPeriods]);

  function renderTable() {
    if (isInitialLoad(periods)) return <LoadingBlock />;
    if (periods.status === 'error' && periods.error && periods.data === null) {
      return <ErrorPanel error={periods.error} onRetry={() => void loadPeriods()} />;
    }
    const known = new Map((periods.data ?? []).map((p) => [p.period, p]));
    const shown = [...new Set([...lastTwelve(new Date()), ...known.keys(), ...extra])].sort().reverse();
    return (
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Accounting periods">
        {shown.map((period) => (
          <PeriodRow key={period} view={known.get(period) ?? untouched(period)} viewerSubject={viewerSubject} />
        ))}
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title="Accounting periods"
        description="Close and lock each month. A locked period takes no journal until a second person approves reopening it."
      />
      <div className="space-y-4 px-6 pb-6">
        <form
          className="flex flex-wrap items-end gap-2"
          aria-label="Show another period"
          onSubmit={(e) => {
            e.preventDefault();
            if (PERIOD.test(lookup.trim())) {
              setExtra((xs) => [...xs, lookup.trim()]);
              setLookup('');
            }
          }}
        >
          <FormField label="Another period (YYYY-MM)">
            <Input inputSize="sm" placeholder="2024-01" value={lookup} onChange={(e) => setLookup(e.target.value)} />
          </FormField>
          <Button type="submit" size="sm" variant="ghost" disabled={!PERIOD.test(lookup.trim())}>
            Show
          </Button>
        </form>
        <div className="rounded-lg border border-border bg-surface p-4">{renderTable()}</div>
      </div>
    </>
  );
}

type Pending = 'closing' | 'lock' | 'reopen-approval' | null;

function PeriodRow({ view, viewerSubject }: { view: AccountingPeriodView; viewerSubject: string | undefined }) {
  const act = useLedgerControlsStore((s) => s.actOnPeriod);
  const acting = useLedgerControlsStore((s) => s.acting[`period.${view.period}`]);
  const [confirming, setConfirming] = useState<Pending>(null);
  const busy = acting?.status === 'loading';
  const reopenAwaiting = view.status === 'LOCKED' && view.reopenRequestedBy != null && view.reopenedBy == null;
  const gates = reopenAwaiting ? approveReopenGates(view, viewerSubject) : [];
  const refused = gates.some((g) => !g.ok && g.hard);

  async function confirm(action: Exclude<Pending, null>) {
    if (await act(view.period, action)) setConfirming(null);
  }

  return (
    <div role="listitem" aria-label={`Period ${view.period}`} className="space-y-2 px-4 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm font-medium">{view.period}</span>
        <StatusBadge kind="accountingPeriod" value={view.status} />
        <span className="text-xs text-muted-foreground">
          {view.lockedBy && view.status === 'LOCKED' ? `Locked by ${view.lockedBy} ${formatInstant(view.lockedAt)}` : ''}
          {view.closingStartedBy && view.status === 'CLOSING'
            ? `Closing since ${formatInstant(view.closingStartedAt)}, by ${view.closingStartedBy}`
            : ''}
          {view.status === 'OPEN' && view.reopenedBy ? `Reopened by ${view.reopenedBy}: ${view.reopenReason ?? ''}` : ''}
        </span>
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}

      {view.status === 'OPEN' &&
        (confirming === 'closing' ? (
          <ConfirmAct
            heading={`Start closing ${view.period}?`}
            consequence="From now the period takes only the platform's and the IFRS 17 engine's journals, no event or manual journal."
            reversal="A closing period does not return to open by itself: lock it, then ask for a reopening, which a second person approves."
            confirmLabel="Start closing"
            busy={busy}
            onConfirm={() => void confirm('closing')}
            onCancel={() => setConfirming(null)}
          />
        ) : (
          <Button size="sm" variant="ghost" onClick={() => setConfirming('closing')}>
            Start closing
          </Button>
        ))}

      {view.status === 'CLOSING' &&
        (confirming === 'lock' ? (
          <ConfirmAct
            heading={`Lock ${view.period}?`}
            consequence="The period takes no journal of any kind. It locks only when every earlier period with postings is locked and every clearing account is at zero."
            reversal="Reopening needs a reason and a second person's approval."
            confirmLabel="Lock period"
            busy={busy}
            onConfirm={() => void confirm('lock')}
            onCancel={() => setConfirming(null)}
          />
        ) : (
          <Button size="sm" onClick={() => setConfirming('lock')}>
            Lock period
          </Button>
        ))}

      {view.status === 'LOCKED' && !reopenAwaiting && <ReopenRequestForm period={view.period} busy={busy} />}

      {reopenAwaiting && (
        <>
          <p className="text-xs">
            Reopening asked by {view.reopenRequestedBy} {formatInstant(view.reopenRequestedAt)}: {view.reopenReason}
          </p>
          <GatePanel gates={gates} title="Before approving" />
          {confirming === 'reopen-approval' ? (
            <ConfirmAct
              heading={`Reopen ${view.period}?`}
              consequence="The period is open again and takes journals of every kind."
              reversal="Close and lock it again when the correction is posted."
              confirmLabel="Approve reopening"
              busy={busy}
              onConfirm={() => void confirm('reopen-approval')}
              onCancel={() => setConfirming(null)}
            />
          ) : (
            <Button size="sm" disabled={refused} onClick={() => setConfirming('reopen-approval')}>
              Approve reopening
            </Button>
          )}
        </>
      )}
    </div>
  );
}

function ReopenRequestForm({ period, busy }: { period: string; busy: boolean }) {
  const requestReopen = useLedgerControlsStore((s) => s.requestReopen);
  const form = useForm<ReopenValues>({ resolver: zodResolver(reopenSchema), defaultValues: { reason: '' } });
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      aria-label={`Request reopening ${period}`}
      onSubmit={form.handleSubmit(async (v) => {
        if (await requestReopen(period, v.reason.trim())) form.reset();
      })}
    >
      <FormField label="Reason to reopen" error={form.formState.errors.reason?.message}>
        <Input inputSize="sm" className="w-80" {...form.register('reason')} />
      </FormField>
      <Button type="submit" size="sm" variant="ghost" disabled={busy}>
        Request reopening
      </Button>
    </form>
  );
}
