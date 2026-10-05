import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import type { UnitLinkedOptionsView, UnitLinkedWithdrawalView } from '@/api/types';
import { downloadUnitStatement } from '@/api/unitlinked';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { approveWithdrawalGates, eatNow } from '@/gates/unitLinkedGates';
import type { ApiError } from '@/lib/apiError';
import { formatDate, formatInstant } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { useUnitLinkedStore } from '@/store/unitLinkedStore';
import {
  splitSchema,
  statementSchema,
  switchSchema,
  toSplit,
  toSwitchRequest,
  toTopUpRequest,
  toWithdrawalRequest,
  topUpSchema,
  withdrawalSchema,
  type SplitValues,
  type StatementValues,
  type SwitchValues,
  type TopUpValues,
  type WithdrawalValues,
} from './u2Forms';

/**
 * The U2 actions on a unit-linked policy's Units tab (product step 6): premium redirection, a fund switch, a partial
 * withdrawal with second-person approval, a top-up and statements. Each section renders only when the version offers
 * it. No figure on screen is a price anything is sold at: everything is priced FORWARD, at the first price after it
 * is asked for (or, for a withdrawal, approved) -- estimates say so.
 */

const sectionClass = 'space-y-2 border-t border-border pt-4';

function Heading({ title, note }: { title: string; note?: string }) {
  return (
    <div>
      <p className="text-xs font-medium">{title}</p>
      {note && <p className="text-xs text-muted-foreground">{note}</p>}
    </div>
  );
}

/**
 * A refinement on a whole list (a split that does not total 100, amounts that do not add up): react-hook-form files it
 * under the array's `root`, or on the array itself when the resolver reports it there -- read both.
 */
function ArrayError({ error }: { error: { message?: string; root?: { message?: string } } | undefined }) {
  const message = error?.message ?? error?.root?.message;
  return message ? <p role="alert" className="text-xs text-status-danger-fg">{message}</p> : null;
}

// ---- Premium redirection ----

export function PremiumSplitPanel({ policyNumber, fundCodes }: { policyNumber: string; fundCodes: string[] }) {
  const history = useUnitLinkedStore((s) => s.splits[policyNumber]);
  const redirect = useUnitLinkedStore((s) => s.redirect);
  const acting = useUnitLinkedStore((s) => s.acting[`split.${policyNumber}`]);
  const current = history?.data?.[0];
  const form = useForm<SplitValues>({
    resolver: zodResolver(splitSchema),
    defaultValues: { shares: fundCodes.map((fundCode) => ({ fundCode, percent: '' })) },
  });
  return (
    <section aria-label="Premium split" className={sectionClass}>
      <Heading
        title="Premium split"
        note="A new split applies to premiums received from now on. Units already held stay where they are; a switch moves those."
      />
      {current && (
        <p className="text-xs">
          In force since {formatInstant(current.effectiveFrom)}:{' '}
          {current.shares.map((s) => `${s.fundCode} ${s.percent}%`).join(', ')}
        </p>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form
        aria-label="Redirect future premiums"
        className="space-y-2"
        onSubmit={form.handleSubmit((v) => void redirect(policyNumber, toSplit(v)))}
      >
        <div className="flex flex-wrap gap-3">
          {fundCodes.map((code, i) => (
            <FormField key={code} label={`${code} (%)`}>
              <Input inputSize="sm" inputMode="numeric" className="w-20" {...form.register(`shares.${i}.percent`)} />
            </FormField>
          ))}
        </div>
        <ArrayError error={form.formState.errors.shares} />
        <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
          Redirect future premiums
        </Button>
      </form>
      {(history?.data?.length ?? 0) > 1 && (
        <ul aria-label="Split history" className="space-y-0.5 text-xs text-muted-foreground">
          {history?.data?.slice(1).map((h) => (
            <li key={h.splitId}>
              From {formatInstant(h.effectiveFrom)}: {h.shares.map((s) => `${s.fundCode} ${s.percent}%`).join(', ')} · by {h.recordedBy}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---- Switch ----

export function SwitchForm({
  policyNumber,
  heldFunds,
  fundCodes,
  options,
  currency,
}: {
  policyNumber: string;
  heldFunds: string[];
  fundCodes: string[];
  options: UnitLinkedOptionsView;
  currency: string;
}) {
  const switches = useUnitLinkedStore((s) => s.switches[policyNumber]);
  const request = useUnitLinkedStore((s) => s.requestSwitch);
  const acting = useUnitLinkedStore((s) => s.acting[`switch.${policyNumber}`]);
  const form = useForm<SwitchValues>({
    resolver: zodResolver(switchSchema),
    defaultValues: {
      out: heldFunds.map((fundCode) => ({ fundCode, percent: '' })),
      into: fundCodes.map((fundCode) => ({ fundCode, percent: '' })),
    },
  });
  const waiting = switches?.data?.find((s) => s.status === 'WAITING');
  const fee = options.switchFee ?? 0;
  return (
    <section aria-label="Fund switch" className={sectionClass}>
      <Heading
        title="Switch funds"
        note={`${options.freeSwitchesPerYear ?? 0} free switches a policy year, then ${formatMoney({ amount: String(fee), currencyCode: currency })} each. Both sides are priced on the same day: the first after the request on which every fund involved has a price.`}
      />
      {waiting && (
        <p role="status" className="text-xs">
          A switch is waiting: priced on {formatDate(waiting.boundDate)}.
        </p>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {!waiting && heldFunds.length > 0 && (
        <form
          aria-label="Request a switch"
          className="space-y-2"
          onSubmit={form.handleSubmit((v) => void request(policyNumber, toSwitchRequest(v)))}
        >
          <p className="text-xs text-muted-foreground">Out of — the share of each fund&apos;s units to move (100 = all):</p>
          <div className="flex flex-wrap gap-3">
            {heldFunds.map((code, i) => (
              <FormField key={code} label={`Move out of ${code} (%)`}>
                <Input inputSize="sm" inputMode="numeric" className="w-20" {...form.register(`out.${i}.percent`)} />
              </FormField>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">Into — how the proceeds are divided, totalling 100:</p>
          <div className="flex flex-wrap gap-3">
            {fundCodes.map((code, i) => (
              <FormField key={code} label={`Into ${code} (%)`}>
                <Input inputSize="sm" inputMode="numeric" className="w-20" {...form.register(`into.${i}.percent`)} />
              </FormField>
            ))}
          </div>
          <ArrayError error={form.formState.errors.out} />
          <ArrayError error={form.formState.errors.into} />
          <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
            Request switch
          </Button>
        </form>
      )}
      {(switches?.data ?? []).filter((s) => s.status !== 'WAITING').length > 0 && (
        <ul aria-label="Past switches" className="space-y-0.5 text-xs text-muted-foreground">
          {(switches?.data ?? [])
            .filter((s) => s.status !== 'WAITING')
            .map((s) => (
              <li key={s.switchId}>
                {s.status === 'EXECUTED' ? `Priced ${formatDate(s.executedOn ?? s.boundDate)}` : 'Cancelled'} ·{' '}
                {s.legs.map((l) => `${l.side === 'OUT' ? 'out of' : 'into'} ${l.fundCode} ${l.percent}%`).join(', ')}
                {s.fee ? ` · fee ${formatMoney({ amount: s.fee, currencyCode: currency })}` : ''}
              </li>
            ))}
        </ul>
      )}
    </section>
  );
}

// ---- Partial withdrawal ----

export function WithdrawalPanel({
  policyNumber,
  heldFunds,
  options,
  currency,
  viewerSubject,
}: {
  policyNumber: string;
  heldFunds: string[];
  options: UnitLinkedOptionsView;
  currency: string;
  viewerSubject: string | undefined;
}) {
  const withdrawals = useUnitLinkedStore((s) => s.withdrawals[policyNumber]);
  const request = useUnitLinkedStore((s) => s.requestWithdrawal);
  const acting = useUnitLinkedStore((s) => s.acting[`withdrawal.${policyNumber}`]);
  const minimum = options.minimumWithdrawal ?? null;
  const form = useForm<WithdrawalValues>({
    resolver: zodResolver(withdrawalSchema(minimum, currency)),
    defaultValues: { grossAmount: '', funds: heldFunds.map((fundCode) => ({ fundCode, amount: '' })), payeeRef: '' },
  });
  const live = (withdrawals?.data ?? []).find((w) => w.status === 'REQUESTED' || w.status === 'APPROVED');
  return (
    <section aria-label="Partial withdrawal" className={sectionClass}>
      <Heading
        title="Partial withdrawal"
        note={`At least ${formatMoney({ amount: String(minimum ?? 0), currencyCode: currency })}, leaving at least ${formatMoney({ amount: String(options.minimumRemainingValue ?? 0), currencyCode: currency })}. A second person approves it; the units are sold at the first price after approval, less the surrender charge for the policy year.`}
      />
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {live ? (
        <WithdrawalCard withdrawal={live} policyNumber={policyNumber} currency={currency} viewerSubject={viewerSubject} />
      ) : (
        <form
          aria-label="Request a withdrawal"
          className="space-y-2"
          onSubmit={form.handleSubmit((v) => void request(policyNumber, toWithdrawalRequest(v)))}
        >
          <div className="flex flex-wrap gap-3">
            <FormField label={`Gross amount (${currency})`} error={form.formState.errors.grossAmount?.message}>
              <Input inputSize="sm" inputMode="decimal" {...form.register('grossAmount')} />
            </FormField>
            <FormField label="Pay to" error={form.formState.errors.payeeRef?.message}>
              <Input inputSize="sm" placeholder="+255712345678" {...form.register('payeeRef')} />
            </FormField>
          </div>
          <p className="text-xs text-muted-foreground">From named funds (optional; leave empty to sell from each fund in proportion):</p>
          <div className="flex flex-wrap gap-3">
            {heldFunds.map((code, i) => (
              <FormField key={code} label={`From ${code}`}>
                <Input inputSize="sm" inputMode="decimal" className="w-28" {...form.register(`funds.${i}.amount`)} />
              </FormField>
            ))}
          </div>
          <ArrayError error={form.formState.errors.funds} />
          <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
            Request withdrawal
          </Button>
        </form>
      )}
      {(withdrawals?.data ?? []).filter((w) => w !== live).length > 0 && (
        <ul aria-label="Past withdrawals" className="space-y-0.5 text-xs text-muted-foreground">
          {(withdrawals?.data ?? [])
            .filter((w) => w !== live)
            .map((w) => (
              <li key={w.withdrawalId}>
                {formatInstant(w.requestedAt)} · {formatMoney({ amount: w.grossAmount, currencyCode: currency })} · {w.status.toLowerCase()}
                {w.surrenderCharge ? ` · charge ${formatMoney({ amount: w.surrenderCharge, currencyCode: currency })}` : ''}
                {w.shortfall && Number(w.shortfall) > 0 ? ` · short by ${formatMoney({ amount: w.shortfall, currencyCode: currency })}` : ''}
              </li>
            ))}
        </ul>
      )}
    </section>
  );
}

function WithdrawalCard({
  withdrawal,
  policyNumber,
  currency,
  viewerSubject,
}: {
  withdrawal: UnitLinkedWithdrawalView;
  policyNumber: string;
  currency: string;
  viewerSubject: string | undefined;
}) {
  const approve = useUnitLinkedStore((s) => s.approveWithdrawal);
  const acting = useUnitLinkedStore((s) => s.acting[withdrawal.withdrawalId]);
  const m = (amount: string | null | undefined) => (amount ? formatMoney({ amount, currencyCode: currency }) : '—');
  const requested = withdrawal.status === 'REQUESTED';
  const gates = requested ? approveWithdrawalGates(withdrawal, viewerSubject) : [];
  const refused = gates.some((g) => !g.ok && g.hard);
  return (
    <div className="space-y-2 rounded-md border border-border p-3" aria-label="Withdrawal in progress">
      <p className="text-xs">
        {m(withdrawal.grossAmount)} to {withdrawal.payeeRef}, requested by {withdrawal.requestedBy}
        {withdrawal.approvedBy ? `, approved by ${withdrawal.approvedBy} — waiting for its price` : ''}.
      </p>
      <dl className="grid grid-cols-2 gap-x-4 text-xs sm:grid-cols-4">
        <dt className="text-muted-foreground">Surrender charge ({withdrawal.chargePercent ?? '0'}%)</dt>
        <dd>{m(withdrawal.estimatedCharge)}</dd>
        <dt className="text-muted-foreground">Paid out</dt>
        <dd>{m(withdrawal.estimatedNet)}</dd>
        <dt className="text-muted-foreground">Value left</dt>
        <dd>{m(withdrawal.estimatedRemaining)}</dd>
        {withdrawal.newSumAssured && (
          <>
            <dt className="text-muted-foreground">New sum assured</dt>
            <dd>{m(withdrawal.newSumAssured)}</dd>
          </>
        )}
      </dl>
      <p className="text-xs text-muted-foreground">Each figure is an estimate at the latest prices; the sale is at the first price after approval.</p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {requested && (
        <>
          <GatePanel gates={gates} title="Before approving this withdrawal" />
          <Button
            size="sm"
            disabled={refused || acting?.status === 'loading'}
            onClick={() => void approve(policyNumber, withdrawal.withdrawalId)}
          >
            Approve withdrawal
          </Button>
        </>
      )}
    </div>
  );
}

// ---- Top-up ----

export function TopUpForm({
  policyNumber,
  fundCodes,
  options,
  currency,
}: {
  policyNumber: string;
  fundCodes: string[];
  options: UnitLinkedOptionsView;
  currency: string;
}) {
  const topUps = useUnitLinkedStore((s) => s.topUps[policyNumber]);
  const request = useUnitLinkedStore((s) => s.requestTopUp);
  const acting = useUnitLinkedStore((s) => s.acting[`topUp.${policyNumber}`]);
  // One key per intent: a retry after a failure or a timeout reuses it, so the money is collected once.
  const [attempt, setAttempt] = useState<MutationAttempt | null>(null);
  const minimum = options.minimumTopUp ?? null;
  const form = useForm<TopUpValues>({
    resolver: zodResolver(topUpSchema(minimum, currency)),
    defaultValues: { amount: '', payerRef: '', split: fundCodes.map((fundCode) => ({ fundCode, percent: '' })) },
  });
  const submit = form.handleSubmit(async (v) => {
    const current = attempt ?? startMutation();
    setAttempt(current);
    await request(policyNumber, toTopUpRequest(v), current);
    if (useUnitLinkedStore.getState().acting[`topUp.${policyNumber}`]?.status === 'success') {
      setAttempt(null);
      form.reset();
    }
  });
  return (
    <section aria-label="Top-up" className={sectionClass}>
      <Heading
        title="Top-up"
        note={`At least ${formatMoney({ amount: String(minimum ?? 0), currencyCode: currency })}. Collected from the payer; ${String(options.topUpAllocationPercent ?? '')}% of it buys units at the first price after the money arrives.`}
      />
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form aria-label="Request a top-up" className="space-y-2" onSubmit={(e) => void submit(e)}>
        <div className="flex flex-wrap gap-3">
          <FormField label={`Amount (${currency})`} error={form.formState.errors.amount?.message}>
            <Input inputSize="sm" inputMode="decimal" {...form.register('amount')} />
          </FormField>
          <FormField label="Collect from" error={form.formState.errors.payerRef?.message}>
            <Input inputSize="sm" placeholder="+255712345678" {...form.register('payerRef')} />
          </FormField>
        </div>
        <p className="text-xs text-muted-foreground">Its own split (optional; leave empty for the policy&apos;s split):</p>
        <div className="flex flex-wrap gap-3">
          {fundCodes.map((code, i) => (
            <FormField key={code} label={`Top-up into ${code} (%)`}>
              <Input inputSize="sm" inputMode="numeric" className="w-20" {...form.register(`split.${i}.percent`)} />
            </FormField>
          ))}
        </div>
        <ArrayError error={form.formState.errors.split} />
        <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
          {attempt ? 'Retry top-up' : 'Request top-up'}
        </Button>
      </form>
      {(topUps?.data?.length ?? 0) > 0 && (
        <ul aria-label="Top-ups" className="space-y-0.5 text-xs text-muted-foreground">
          {topUps?.data?.map((t) => (
            <li key={t.topUpId}>
              {formatInstant(t.requestedAt)} · {formatMoney({ amount: t.amount, currencyCode: t.currency })} from {t.payerRef} ·{' '}
              {t.status.toLowerCase()}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---- Statements ----

export function StatementsPanel({ policyNumber }: { policyNumber: string }) {
  const statements = useUnitLinkedStore((s) => s.statements[policyNumber]);
  const file = useUnitLinkedStore((s) => s.fileStatement);
  const acting = useUnitLinkedStore((s) => s.acting[`statement.${policyNumber}`]);
  const [downloadError, setDownloadError] = useState<ApiError | null>(null);
  const today = eatNow().date;
  const form = useForm<StatementValues>({
    resolver: zodResolver(statementSchema(today)),
    defaultValues: { from: '', to: today },
  });
  const download = async (statementId: string, name: string) => {
    setDownloadError(null);
    try {
      saveBlob(await downloadUnitStatement(statementId), name);
    } catch (cause) {
      setDownloadError(cause as ApiError);
    }
  };
  return (
    <section aria-label="Unit statements" className={sectionClass}>
      <Heading
        title="Statements"
        note="A statement for each calendar year is filed every January and the policyholder is told by SMS. One for any other period can be filed here; it is staff's and tells the policyholder nothing."
      />
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form
        aria-label="File a statement"
        className="flex flex-wrap items-end gap-3"
        onSubmit={form.handleSubmit((v) => void file(policyNumber, v.from, v.to))}
      >
        <FormField label="From" error={form.formState.errors.from?.message}>
          <Controller
            control={form.control}
            name="from"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <FormField label="To" error={form.formState.errors.to?.message}>
          <Controller
            control={form.control}
            name="to"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
          File statement
        </Button>
      </form>
      {downloadError && <InlineError error={downloadError} />}
      {(statements?.data?.length ?? 0) > 0 && (
        <div className="divide-y divide-border rounded-md border border-border" aria-label="Filed statements">
          {statements?.data?.map((s) => (
            <div key={s.statementId} className="flex items-center justify-between gap-2 px-3 py-2">
              <span className="text-sm">
                {formatDate(s.periodFrom)} to {formatDate(s.periodTo)}
                <span className="ml-2 text-xs text-muted-foreground">
                  {s.kind === 'ANNUAL' ? 'Annual' : 'On demand'} · by {s.generatedBy}
                </span>
              </span>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => void download(s.statementId, `unit-statement-${policyNumber}-${s.periodFrom}-${s.periodTo}.pdf`)}
              >
                Download
              </Button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
