import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useSearchParams } from 'react-router-dom';
import type { FieldReceiptView } from '@/api/types';
import { FormField, RequiredNote } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { formatInstantDay } from '@/lib/dates';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { useAgentCashStore } from '@/store/agentCashStore';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOwnAgent, useDistributionStore } from '@/store/distributionStore';
import { bankedSchema, recordCashSchema, type BankedValues, type RecordCashValues } from './agentCashForm';

/**
 * "My cash" (2026-11-11): the agent records cash a customer handed them, sees what they still hold, and says where they
 * banked it. Recording pays the customer's premium at once and texts the customer, naming the agent; the numbered
 * receipt follows when the money reaches the company. Cash the company has not received within 14 days is undone.
 */
export function MyCashPage() {
  const own = useDistributionStore(selectOwnAgent);
  const loadOwnAgent = useDistributionStore((s) => s.loadOwnAgent);
  const held = useAgentCashStore((s) => s.held);
  const loadHeld = useAgentCashStore((s) => s.loadHeld);
  const agentId = own.data?.agentId;

  useEffect(() => {
    void loadOwnAgent();
  }, [loadOwnAgent]);

  useEffect(() => {
    if (agentId) void loadHeld(agentId);
  }, [agentId, loadHeld]);

  if (isInitialLoad(own)) return <LoadingBlock label="Loading your cash" />;
  if (!agentId) {
    return (
      <div className="px-6 pt-6">
        {own.error ? <ErrorPanel error={own.error} onRetry={() => void loadOwnAgent()} /> : null}
      </div>
    );
  }

  return (
    <>
      <PageHeader
        title="My cash"
        description="Cash customers hand you. Record it here at once: they get an SMS naming you, and nobody chases them for it. Bank it within 14 days."
      />
      <div className="space-y-5 px-6 pt-5 pb-8">
        <RecordCash agentId={agentId} />
        <section aria-label="Cash you still hold" className="rounded-xl border border-border bg-surface p-4 shadow-card">
          <h2 className="mb-3 text-sm font-medium">Cash you still hold</h2>
          {isInitialLoad(held) ? (
            <LoadingBlock />
          ) : held.status === 'error' && held.error && held.data === null ? (
            <ErrorPanel error={held.error} onRetry={() => void loadHeld(agentId)} />
          ) : (held.data ?? []).length === 0 ? (
            <EmptyState title="You hold no cash" description="Cash you record shows here until the company receives it." />
          ) : (
            <HeldList agentId={agentId} rows={held.data ?? []} />
          )}
        </section>
      </div>
    </>
  );
}

function RecordCash({ agentId }: { agentId: string }) {
  const [params] = useSearchParams();
  const record = useAgentCashStore((s) => s.record);
  const recording = useAgentCashStore((s) => s.recording);
  const resetRecording = useAgentCashStore((s) => s.resetRecording);
  const [done, setDone] = useState<RecordCashValues | null>(null);
  const form = useForm<RecordCashValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(recordCashSchema),
    defaultValues: { policyNumber: params.get('policy') ?? '', amount: '' },
  });

  if (done) {
    return (
      <section aria-label="Cash recorded" className="space-y-3 rounded-xl border border-border bg-surface p-4 shadow-card">
        <p className="text-sm font-medium" role="status">
          Recorded: {formatMoney({ amount: done.amount, currencyCode: 'TZS' })} cash for {done.policyNumber}.
        </p>
        <p className="text-sm text-muted-foreground">
          The customer has been sent an SMS naming you. Their receipt follows once the money reaches the company. On
          savings, deposits and annuities the money counts from the day the company receives it.
        </p>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            setDone(null);
            resetRecording();
            form.reset({ policyNumber: '', amount: '' });
          }}
        >
          Record more cash
        </Button>
      </section>
    );
  }

  return (
    <form
      aria-label="Record cash"
      className="space-y-3 rounded-xl border border-border bg-surface p-4 shadow-card"
      onSubmit={form.handleSubmit(async (v) => {
        if (await record(agentId, v.policyNumber.trim().toUpperCase(), v.amount, 'TZS')) setDone(v);
      })}
    >
      <h2 className="text-sm font-medium">Record cash</h2>
      <RequiredNote />
      {recording.status === 'error' && recording.error && <InlineError error={recording.error} />}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FormField required label="Policy number" error={form.formState.errors.policyNumber?.message}>
          <Input placeholder="POL-…" autoComplete="off" {...form.register('policyNumber')} />
        </FormField>
        <FormField required label="Amount (TZS)" error={form.formState.errors.amount?.message}>
          <Input inputMode="decimal" placeholder="50000" {...form.register('amount')} />
        </FormField>
      </div>
      <Button type="submit" size="sm" disabled={recording.status === 'loading'}>
        Record cash
      </Button>
    </form>
  );
}

export function HeldList({ agentId, rows }: { agentId: string; rows: FieldReceiptView[] }) {
  const markBanked = useAgentCashStore((s) => s.markBanked);
  const banking = useAgentCashStore((s) => s.banking);
  const [chosen, setChosen] = useState<string[]>([]);
  const form = useForm<BankedValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(bankedSchema),
    defaultValues: { reference: '' },
  });
  const open = rows.filter((r) => r.status !== 'EXPIRED');
  const total = open.reduce((sum, r) => sum + Number(r.amount?.amount ?? 0), 0);

  return (
    <div className="space-y-3">
      <p className="text-sm">
        You hold <span className="font-medium tabular-nums">{formatMoney({ amount: String(total), currencyCode: 'TZS' })}</span>{' '}
        in {open.length} {open.length === 1 ? 'collection' : 'collections'}.
      </p>
      <ul className="divide-y divide-border rounded-md border border-border">
        {rows.map((r) => (
          <li key={r.receiptId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-3 py-2 text-sm">
            <span className="flex items-center gap-3">
              {r.status !== 'EXPIRED' && r.receiptId && (
                <CheckboxField
                  label={<span className="sr-only">Banked {r.policyNumber}</span>}
                  checked={chosen.includes(r.receiptId)}
                  onChange={(e) => {
                    // Read now: the event's target is gone by the time React runs the state updater.
                    const checked = e.currentTarget.checked;
                    setChosen((c) => (checked ? [...c, r.receiptId!] : c.filter((id) => id !== r.receiptId)));
                  }}
                />
              )}
              <span className="flex flex-col">
                {r.policyholderPartyId ? <PartyName partyId={r.policyholderPartyId} className="font-medium" /> : null}
                <span className="font-mono text-xs text-muted-foreground">
                  {r.policyNumber} · {formatInstantDay(r.capturedAtServer)}
                </span>
              </span>
            </span>
            <span className="flex items-center gap-3">
              <span className="tabular-nums">{formatMoney(r.amount)}</span>
              {r.bankedReference ? (
                <span className="text-xs text-muted-foreground">Banked · {r.bankedReference}</span>
              ) : (
                <StatusBadge kind="fieldReceipt" value={r.status ?? ''} />
              )}
            </span>
          </li>
        ))}
      </ul>
      <form
        aria-label="I've banked this"
        className="flex flex-wrap items-end gap-2"
        onSubmit={form.handleSubmit(async (v) => {
          if (await markBanked(agentId, chosen, v.reference.trim())) {
            setChosen([]);
            form.reset({ reference: '' });
          }
        })}
      >
        <FormField label="Bank slip or mobile-money reference" error={form.formState.errors.reference?.message}>
          <Input className="w-64" {...form.register('reference')} />
        </FormField>
        <Button type="submit" size="sm" variant="outline" disabled={chosen.length === 0 || banking.status === 'loading'}>
          I've banked {chosen.length === 0 ? 'these' : chosen.length === 1 ? 'this' : `these ${chosen.length}`}
        </Button>
      </form>
      {banking.status === 'error' && banking.error && <InlineError error={banking.error} />}
    </div>
  );
}
