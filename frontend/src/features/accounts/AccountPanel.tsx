import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm, type FieldValues, type UseFormReturn } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { AccountView, AdjustmentView, LedgerEntryView, WithdrawalView } from '@/api/types';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  approveWithdrawalGates,
  decideAdjustmentGates,
  requestWithdrawalGates,
} from '@/gates/accumulationGates';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAccumulationStore } from '@/store/accumulationStore';
import {
  adjustmentSchema,
  topUpSchema,
  transferInSchema,
  withdrawalSchema,
  type AdjustmentValues,
  type TopUpValues,
  type TransferInValues,
  type WithdrawalValues,
} from './accountForms';
import { DepositSection } from './DepositSection';
import { ENTRY_LABEL } from './entryLabels';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/**
 * The savings account behind a policy (product step 3): its balance, every entry on its ledger, and
 * the money that can move on it.
 *
 * The ledger is the record, and it is shown as one -- in sequence order, nothing edited. A correction
 * appears as its own REVERSAL or ADJUSTMENT line that names what it corrects, never as a changed
 * figure. Requesting is open to any staff member; anything that moves money needs a finance officer,
 * and a second person, exactly as the endpoints do.
 */
export function AccountPanel({ policyNumber }: { policyNumber: string }) {
  const account = useAccumulationStore((s) => s.account[policyNumber]);
  const loadAccount = useAccumulationStore((s) => s.loadAccount);
  const loadMovements = useAccumulationStore((s) => s.loadMovements);
  // A fixed-term deposit carries its terms beside the ledger; null for any other account.
  const deposit = useAccumulationStore((s) => s.deposit[policyNumber]);
  const loadDeposit = useAccumulationStore((s) => s.loadDeposit);
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const isFinance = identity ? canSeeFinance(identity) : false;

  useEffect(() => {
    void loadAccount(policyNumber);
    void loadMovements(policyNumber);
    void loadDeposit(policyNumber);
  }, [policyNumber, loadAccount, loadMovements, loadDeposit]);

  if (!account || isInitialLoad(account)) return <LoadingBlock />;
  if (account.status === 'error' && account.error && account.data === null) {
    return <ErrorPanel error={account.error} onRetry={() => void loadAccount(policyNumber)} />;
  }
  if (!account.data) return null;

  return (
    <div className="space-y-5">
      <Summary account={account.data} />
      {deposit?.data && <DepositSection deposit={deposit.data} isFinance={isFinance} />}
      {/* An unpaid deposit's empty ledger would invite a top-up or transfer it cannot take; the
          deposit section already says what is awaited. */}
      {!(deposit?.data && account.data.entries.length === 0) && <Ledger entries={account.data.entries} />}
      <Movements account={account.data} deposit={Boolean(deposit?.data)} />
    </div>
  );
}

function Summary({ account }: { account: AccountView }) {
  return (
    <dl className="divide-y divide-border rounded-md border border-border">
      <Field label="Status" value={<StatusBadge kind="savingsAccount" value={account.status} />} />
      <Field label="Balance" value={formatMoney(account.balance)} emphasis />
      <Field label="Opened" value={formatDate(account.openedOn)} />
      {account.closedOn && (
        <Field label="Closed" value={`${formatDate(account.closedOn)} (${account.closedReason ?? 'closed'})`} />
      )}
    </dl>
  );
}

function Ledger({ entries }: { entries: LedgerEntryView[] }) {
  if (entries.length === 0) {
    return (
      <EmptyState
        title="Nothing on the ledger yet"
        description="The account is credited when the first premium, top-up or transfer is collected."
      />
    );
  }
  const seqById = new Map(entries.map((e) => [e.entryId, e.seq]));
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium">Ledger</p>
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Ledger">
        {entries.map((entry) => (
          <div key={entry.entryId} role="listitem" className="flex items-start justify-between gap-3 px-4 py-2.5">
            <div className="min-w-0">
              <span className="text-xs text-muted-foreground">#{entry.seq}</span>{' '}
              <span className="text-sm font-medium">{ENTRY_LABEL[entry.type]}</span>
              <p className="text-xs text-muted-foreground">
                {formatDate(entry.effectiveDate)}
                {entry.reason ? ` · ${entry.reason}` : ''}
                {entry.reversesEntryId ? ` · reverses #${seqById.get(entry.reversesEntryId) ?? '?'}` : ''}
              </p>
            </div>
            <span className="shrink-0 text-right">
              <span className="block text-sm font-medium">{formatMoney(entry.amount)}</span>
              <span className="block text-xs text-muted-foreground">balance {formatMoney(entry.balanceAfter)}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

type Action = 'withdraw' | 'topup' | 'transfer' | 'adjust' | null;

/** Stable empties, so a missing list is the same value on every render. */
const NONE_WITHDRAWN: WithdrawalView[] = [];
const NONE_ADJUSTED: AdjustmentView[] = [];

/**
 * `deposit`: a fixed-term deposit takes nothing in and lets nothing out during its term (spec
 * D5), so only the adjustment -- the two-person correction -- is offered.
 */
function Movements({ account, deposit }: { account: AccountView; deposit: boolean }) {
  const policyNumber = account.policyNumber;
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;
  const isFinance = identity ? canSeeFinance(identity) : false;

  // Select the stored resource and default OUTSIDE the selector: `?? []` inside it returns a fresh
  // array on every call, which Zustand reads as a change and re-renders forever -- the render loop
  // createResourceSlice warns about, and a blank page in the browser.
  const withdrawalsResource = useAccumulationStore((s) => s.withdrawals[policyNumber]);
  const adjustmentsResource = useAccumulationStore((s) => s.adjustments[policyNumber]);
  const withdrawals = withdrawalsResource?.data ?? NONE_WITHDRAWN;
  const adjustments = adjustmentsResource?.data ?? NONE_ADJUSTED;
  const [action, setAction] = useState<Action>(null);
  const open = account.status === 'OPEN';

  return (
    <div className="space-y-4">
      {open && (
        <div className="flex flex-wrap gap-2">
          {!deposit && (
            <>
              <Button size="sm" variant={action === 'withdraw' ? 'secondary' : 'outline'} onClick={() => setAction('withdraw')}>
                Request withdrawal
              </Button>
              <Button size="sm" variant={action === 'topup' ? 'secondary' : 'outline'} onClick={() => setAction('topup')}>
                Request top-up
              </Button>
            </>
          )}
          {/* Finance's: recording a transfer puts money on the account, and an adjustment is a
              correction a second finance user signs. Not offered at all to anyone else, rather than
              a button that would 403. */}
          {isFinance && (
            <>
              {!deposit && (
                <Button size="sm" variant={action === 'transfer' ? 'secondary' : 'outline'} onClick={() => setAction('transfer')}>
                  Record transfer in
                </Button>
              )}
              <Button size="sm" variant={action === 'adjust' ? 'secondary' : 'outline'} onClick={() => setAction('adjust')}>
                Propose adjustment
              </Button>
            </>
          )}
        </div>
      )}

      {action === 'withdraw' && <WithdrawalForm account={account} withdrawals={withdrawals} onDone={() => setAction(null)} />}
      {action === 'topup' && <TopUpForm policyNumber={policyNumber} onDone={() => setAction(null)} />}
      {action === 'transfer' && <TransferInForm policyNumber={policyNumber} onDone={() => setAction(null)} />}
      {action === 'adjust' && <AdjustmentForm policyNumber={policyNumber} onDone={() => setAction(null)} />}

      {withdrawals.length > 0 && (
        <div>
          <p className="mb-1.5 text-xs font-medium">Withdrawals</p>
          <div className="divide-y divide-border rounded-md border border-border">
            {withdrawals.map((w) => (
              <WithdrawalRow key={w.withdrawalId} withdrawal={w} viewerSubject={viewerSubject} isFinance={isFinance} />
            ))}
          </div>
        </div>
      )}

      {adjustments.length > 0 && (
        <div>
          <p className="mb-1.5 text-xs font-medium">Adjustments</p>
          <div className="divide-y divide-border rounded-md border border-border">
            {adjustments.map((a) => (
              <AdjustmentRow key={a.adjustmentId} adjustment={a} viewerSubject={viewerSubject} isFinance={isFinance} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/** One labelled input bound to a react-hook-form field, with its error. */
function TextField<T extends FieldValues>({
  form,
  name,
  label,
  placeholder,
  inputMode,
}: {
  form: UseFormReturn<T>;
  name: string;
  label: string;
  placeholder?: string;
  inputMode?: 'decimal' | 'text';
}) {
  const error = (form.formState.errors as Record<string, { message?: string } | undefined>)[name]?.message;
  return (
    <FormField label={label} error={error}>
      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any -- a generic field name */}
      <Input {...form.register(name as any)} placeholder={placeholder} inputMode={inputMode} />
    </FormField>
  );
}

function WithdrawalForm({
  account,
  withdrawals,
  onDone,
}: {
  account: AccountView;
  withdrawals: WithdrawalView[];
  onDone: () => void;
}) {
  const policyNumber = account.policyNumber;
  const withdraw = useAccumulationStore((s) => s.withdraw);
  const acting = useAccumulationStore((s) => s.acting[`withdraw.${policyNumber}`]);
  const [armed, setArmed] = useState<WithdrawalValues | null>(null);
  const form = useForm<WithdrawalValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(withdrawalSchema), defaultValues: { amount: '', payeeRef: '' } });
  const gates = requestWithdrawalGates(account, withdrawals);
  const refused = gates.some((g) => !g.ok && g.hard);

  if (armed) {
    return (
      <ConfirmAct
        heading="Request this withdrawal?"
        consequence={
          <>
            <strong>{formatMoney({ amount: armed.amount, currencyCode: account.balance.currencyCode })}</strong> from{' '}
            <strong>{policyNumber}</strong> to {armed.payeeRef}. It is valued again when a second person approves it.
          </>
        }
        reversal="Nothing moves until a finance officer approves it. A request can be left unapproved."
        confirmLabel="Request withdrawal"
        busy={acting?.status === 'loading'}
        onConfirm={() => {
          void withdraw(policyNumber, { amount: armed.amount, payeeRef: armed.payeeRef.trim() }, startMutation()).then(() => {
            if (useAccumulationStore.getState().acting[`withdraw.${policyNumber}`]?.status === 'success') onDone();
          });
          setArmed(null);
        }}
        onCancel={() => setArmed(null)}
      />
    );
  }

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <GatePanel gates={gates} title="Before requesting" />
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form onSubmit={form.handleSubmit((v) => setArmed(v))} className="space-y-3">
        <TextField form={form} name="amount" label="Amount" placeholder="40000.00" inputMode="decimal" />
        <TextField form={form} name="payeeRef" label="Pay to" placeholder="Mobile money number or bank destination" />
        <p className="text-xs text-muted-foreground">
          The account must keep its product&apos;s minimum balance, after any policy loan. The server checks the exact figure.
        </p>
        <Button type="submit" size="sm" disabled={refused}>
          Continue
        </Button>
      </form>
    </div>
  );
}

function TopUpForm({ policyNumber, onDone }: { policyNumber: string; onDone: () => void }) {
  const topUp = useAccumulationStore((s) => s.topUp);
  const acting = useAccumulationStore((s) => s.acting[`topup.${policyNumber}`]);
  const form = useForm<TopUpValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(topUpSchema), defaultValues: { amount: '', payerRef: '' } });

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {acting?.status === 'success' && (
        <p className="text-xs text-muted-foreground" role="status">
          The collection has been requested from the provider. It is credited once the payment is confirmed.
        </p>
      )}
      <form
        onSubmit={form.handleSubmit((v) =>
          void topUp(policyNumber, { amount: v.amount, payerRef: v.payerRef.trim() }, startMutation()).then(() => {
            if (useAccumulationStore.getState().acting[`topup.${policyNumber}`]?.status === 'success') form.reset();
          }),
        )}
        className="space-y-3"
      >
        <TextField form={form} name="amount" label="Amount" placeholder="20000.00" inputMode="decimal" />
        <TextField form={form} name="payerRef" label="Collect from" placeholder="Mobile money number" />
        <div className="flex gap-2">
          <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
            Request top-up
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Close
          </Button>
        </div>
      </form>
    </div>
  );
}

function TransferInForm({ policyNumber, onDone }: { policyNumber: string; onDone: () => void }) {
  const transferIn = useAccumulationStore((s) => s.transferIn);
  const acting = useAccumulationStore((s) => s.acting[`transfer.${policyNumber}`]);
  const form = useForm<TransferInValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(transferInSchema),
    defaultValues: { amount: '', sourceScheme: '', documentRef: '' },
  });

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form
        onSubmit={form.handleSubmit((v) =>
          void transferIn(
            policyNumber,
            { amount: v.amount, sourceScheme: v.sourceScheme.trim(), ...(v.documentRef ? { documentRef: v.documentRef } : {}) },
            startMutation(),
          ).then(() => {
            if (useAccumulationStore.getState().acting[`transfer.${policyNumber}`]?.status === 'success') onDone();
          }),
        )}
        className="space-y-3"
      >
        <TextField form={form} name="amount" label="Amount received" placeholder="300000.00" inputMode="decimal" />
        <TextField form={form} name="sourceScheme" label="From scheme" placeholder="NSSF member 1234" />
        <TextField form={form} name="documentRef" label="Document reference (optional)" />
        <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
          Record transfer in
        </Button>
      </form>
    </div>
  );
}

function AdjustmentForm({ policyNumber, onDone }: { policyNumber: string; onDone: () => void }) {
  const propose = useAccumulationStore((s) => s.proposeAdjustment);
  const acting = useAccumulationStore((s) => s.acting[`adjust.${policyNumber}`]);
  const form = useForm<AdjustmentValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(adjustmentSchema), defaultValues: { amount: '', reason: '' } });

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <p className="text-xs text-muted-foreground">
        A correction is a new entry, never an edit. A negative amount takes money out. It is posted only once a second
        person approves it.
      </p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form
        onSubmit={form.handleSubmit((v) =>
          void propose(policyNumber, { amount: v.amount, reason: v.reason.trim() }, startMutation()).then(() => {
            if (useAccumulationStore.getState().acting[`adjust.${policyNumber}`]?.status === 'success') onDone();
          }),
        )}
        className="space-y-3"
      >
        <TextField form={form} name="amount" label="Amount" placeholder="-250.00" inputMode="decimal" />
        <TextField form={form} name="reason" label="Reason" placeholder="Fee charged twice in error" />
        <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
          Propose adjustment
        </Button>
      </form>
    </div>
  );
}

function WithdrawalRow({
  withdrawal,
  viewerSubject,
  isFinance,
}: {
  withdrawal: WithdrawalView;
  viewerSubject: string | undefined;
  isFinance: boolean;
}) {
  const approve = useAccumulationStore((s) => s.approveWithdrawal);
  const acting = useAccumulationStore((s) => s.acting[withdrawal.withdrawalId]);
  const [armed, setArmed] = useState(false);
  const gates = approveWithdrawalGates(withdrawal, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <div className="space-y-2 px-4 py-2.5">
      <div className="flex items-start justify-between gap-2">
        <div>
          <span className="text-sm font-medium">{formatMoney(withdrawal.amount)}</span>{' '}
          <StatusBadge kind="accountWithdrawal" value={withdrawal.status} />
          <p className="text-xs text-muted-foreground">
            To {withdrawal.payeeRef} · requested by {withdrawal.requestedBy}
            {withdrawal.approvedBy ? ` · approved by ${withdrawal.approvedBy}` : ''}
          </p>
        </div>
      </div>
      {/* 202 from the rail: REQUESTED, never "paid" -- the badge reads PAID only once the provider confirms. */}
      {acting?.status === 'success' && (
        <p className="text-xs text-muted-foreground" role="status">
          The payment has been requested from the provider.
        </p>
      )}
      {withdrawal.status === 'REQUESTED' && isFinance && (
        <>
          <GatePanel gates={gates} title="Before approving" />
          {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
          {armed ? (
            <ConfirmAct
              heading="Approve this withdrawal?"
              consequence={
                <>
                  <strong>{formatMoney(withdrawal.amount)}</strong> leaves the account now and is requested for{' '}
                  {withdrawal.payeeRef}.
                </>
              }
              reversal="If the payment fails, the money goes back to the account as a reversal entry."
              confirmLabel={`Pay ${formatMoney(withdrawal.amount)}`}
              tone="danger"
              busy={acting?.status === 'loading'}
              onConfirm={() => {
                void approve(withdrawal.policyNumber, withdrawal.withdrawalId, startMutation());
                setArmed(false);
              }}
              onCancel={() => setArmed(false)}
            />
          ) : (
            <Button size="sm" disabled={refused} onClick={() => setArmed(true)}>
              Approve
            </Button>
          )}
        </>
      )}
    </div>
  );
}

function AdjustmentRow({
  adjustment,
  viewerSubject,
  isFinance,
}: {
  adjustment: AdjustmentView;
  viewerSubject: string | undefined;
  isFinance: boolean;
}) {
  const decide = useAccumulationStore((s) => s.decideAdjustment);
  const acting = useAccumulationStore((s) => s.acting[adjustment.adjustmentId]);
  const gates = decideAdjustmentGates(adjustment, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);
  const money = { amount: adjustment.amount, currencyCode: 'TZS' };

  return (
    <div className="space-y-2 px-4 py-2.5">
      <div>
        <span className="text-sm font-medium">{formatMoney(money)}</span>{' '}
        <StatusBadge kind="accountAdjustment" value={adjustment.status} />
        <p className="text-xs text-muted-foreground">
          {adjustment.reason} · proposed by {adjustment.proposedBy}
          {adjustment.decidedBy ? ` · decided by ${adjustment.decidedBy}` : ''}
        </p>
      </div>
      {adjustment.status === 'PROPOSED' && isFinance && (
        <>
          <GatePanel gates={gates} title="Before deciding" />
          {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={refused || acting?.status === 'loading'}
              onClick={() => void decide(adjustment.policyNumber, adjustment.adjustmentId, true, startMutation())}
            >
              Approve
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={refused || acting?.status === 'loading'}
              onClick={() => void decide(adjustment.policyNumber, adjustment.adjustmentId, false, startMutation())}
            >
              Reject
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
