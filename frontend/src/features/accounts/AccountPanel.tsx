import { can } from '@/auth/access';
import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowDownLeft, ArrowRightLeft, ArrowUpRight, PencilLine, RefreshCw } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Controller, useForm, useWatch, type FieldValues, type UseFormReturn } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type {
  AccountView,
  AdjustmentView,
  LedgerEntryView,
  TermAccountView,
  TopUpView,
  WithdrawalView,
} from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Receipt } from '@/components/Receipt';
import { DataTable, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { DatePicker } from '@/components/DatePicker';
import { RECEIPT_METHODS } from '@/features/receipts/receiptForm';
import { PayoutRouteFields } from '@/features/payments/PayoutRouteFields';
import { BLANK_ROUTE, describeRoute, routeBody } from '@/features/payments/payoutRoute';
import { methodWords } from '@/features/payments/paymentWords';
import {
  approveWithdrawalGates,
  decideAdjustmentGates,
  requestWithdrawalGates,
} from '@/gates/accumulationGates';
import { formatDate, formatInstantDay, todayIso } from '@/lib/dates';
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
import { TermAccountSection } from './TermAccountSection';
import { ENTRY_LABEL } from './entryLabels';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { StaffName } from '@/components/StaffName';

/** How often the tab rereads the account while money is on its way in or out (2026-10-28). */
const REFRESH_MS = 5000;

/** Stable empties, so a missing list is the same value on every render. */
const NONE_WITHDRAWN: WithdrawalView[] = [];
const NONE_TOPPED_UP: TopUpView[] = [];
const NONE_ADJUSTED: AdjustmentView[] = [];

/**
 * The savings account behind a policy (product step 3), laid out the way a person reads a bank account
 * (2026-10-28): the balance and what has gone in and out of it first, then what can be done, then what is
 * waiting, then the ledger.
 *
 * The ledger is the record, and it is shown as one -- nothing edited. A correction appears as its own REVERSAL or
 * ADJUSTMENT line that names what it corrects, never as a changed figure. Requesting is open to any staff member;
 * anything that moves money out needs a finance officer, and a second person, exactly as the endpoints do.
 *
 * While anything is waiting -- a withdrawal to approve or to be paid, a top-up to be collected, an adjustment to
 * decide -- the tab rereads itself every few seconds. A withdrawal used to look stuck until somebody reloaded the
 * page, though its money had already left the balance at approval.
 */
export function AccountPanel({ policyNumber }: { policyNumber: string }) {
  const account = useAccumulationStore((s) => s.account[policyNumber]);
  const loadAccount = useAccumulationStore((s) => s.loadAccount);
  const loadMovements = useAccumulationStore((s) => s.loadMovements);
  // A fixed-term deposit carries its terms beside the ledger; null for any other account.
  const deposit = useAccumulationStore((s) => s.deposit[policyNumber]);
  const loadDeposit = useAccumulationStore((s) => s.loadDeposit);
  // A term account (2026-10-21) carries its locked rate and maturity beside the ledger; null for any other account.
  const termAccount = useAccumulationStore((s) => s.termAccount[policyNumber]);
  const loadTermAccount = useAccumulationStore((s) => s.loadTermAccount);
  // Paying a matured deposit or term account to its payee (payouts.manage, 2026-10-26).
  const isFinance = can('payouts.manage');
  const waiting = useWaiting(policyNumber);

  useEffect(() => {
    void loadAccount(policyNumber);
    void loadMovements(policyNumber);
    void loadDeposit(policyNumber);
    void loadTermAccount(policyNumber);
  }, [policyNumber, loadAccount, loadMovements, loadDeposit, loadTermAccount]);

  const somethingWaiting = waiting.count > 0;
  useEffect(() => {
    if (!somethingWaiting) return undefined;
    const timer = window.setInterval(() => {
      // A hidden tab is not read; the next interval after it is shown again catches up.
      if (document.visibilityState !== 'visible') return;
      void loadAccount(policyNumber);
      void loadMovements(policyNumber);
    }, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [somethingWaiting, policyNumber, loadAccount, loadMovements]);

  if (!account || isInitialLoad(account)) return <LoadingBlock />;
  if (account.status === 'error' && account.error && account.data === null) {
    return <ErrorPanel error={account.error} onRetry={() => void loadAccount(policyNumber)} />;
  }
  if (!account.data) return null;
  // An unpaid deposit's empty ledger would invite a top-up or transfer it cannot take; the deposit section already
  // says what is awaited.
  const unpaidDeposit = Boolean(deposit?.data) && account.data.entries.length === 0;

  return (
    <div className="space-y-5">
      <Figures account={account.data} />
      {deposit?.data && <DepositSection deposit={deposit.data} isFinance={isFinance} />}
      {termAccount?.data && <TermAccountSection termAccount={termAccount.data} isFinance={isFinance} />}
      <Movements account={account.data} deposit={Boolean(deposit?.data)} termAccount={termAccount?.data ?? null} />
      {!unpaidDeposit && <Ledger entries={account.data.entries} />}
      <PastRequests policyNumber={policyNumber} />
    </div>
  );
}

/** Everything on the account still waiting on a person or a payment, oldest first. */
function useWaiting(policyNumber: string) {
  const withdrawalsResource = useAccumulationStore((s) => s.withdrawals[policyNumber]);
  const topUpsResource = useAccumulationStore((s) => s.topUps[policyNumber]);
  const adjustmentsResource = useAccumulationStore((s) => s.adjustments[policyNumber]);
  const withdrawals = (withdrawalsResource?.data ?? NONE_WITHDRAWN).filter(
    (w) => w.status === 'REQUESTED' || w.status === 'APPROVED',
  );
  const topUps = (topUpsResource?.data ?? NONE_TOPPED_UP).filter((t) => t.status === 'REQUESTED');
  const adjustments = (adjustmentsResource?.data ?? NONE_ADJUSTED).filter((a) => a.status === 'PROPOSED');
  return { withdrawals, topUps, adjustments, count: withdrawals.length + topUps.length + adjustments.length };
}

/**
 * The figures band: the balance, and what has gone in and out, each a figure the server summed. Below it the facts
 * that qualify them -- the account's state, when it opened, interest earned and charges taken.
 */
function Figures({ account }: { account: AccountView }) {
  const { totals } = account;
  return (
    <div className="space-y-3">
      <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3">
        <Field stacked label="Balance" value={formatMoney(account.balance)} />
        <Field stacked label="Paid in" value={formatMoney(totals.paidIn)} note="Premiums, top-ups and transfers in" />
        <Field stacked label="Paid out" value={formatMoney(totals.paidOut)} note="Withdrawals and other payments out" />
      </dl>
      <dl className="grid grid-cols-1 gap-x-6 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Status" value={<StatusBadge kind="savingsAccount" value={account.status} />} />
        <Field label="Opened" value={formatDate(account.openedOn)} />
        <Field label="Interest credited" value={formatMoney(totals.interest)} />
        <Field label="Charges taken" value={formatMoney(totals.charges)} />
        {account.closedOn && (
          <Field label="Closed" value={`${formatDate(account.closedOn)} (${account.closedReason ?? 'closed'})`} />
        )}
      </dl>
    </div>
  );
}

/** How many ledger lines show before "Show all" -- the latest, since that is what a person checks first. */
const LEDGER_PREVIEW = 12;

/**
 * The ledger as a statement: newest first, money in and money out in their own columns, the running balance last.
 * A reversal names the line it undoes by its number.
 */
function Ledger({ entries }: { entries: LedgerEntryView[] }) {
  const [all, setAll] = useState(false);
  if (entries.length === 0) {
    return (
      <EmptyState
        title="Nothing on the ledger yet"
        description="The account is credited when the first premium, top-up or transfer is collected."
      />
    );
  }
  const seqById = new Map(entries.map((e) => [e.entryId, e.seq]));
  const newestFirst = [...entries].sort((a, b) => b.seq - a.seq);
  const shown = all ? newestFirst : newestFirst.slice(0, LEDGER_PREVIEW);
  const signed = (e: LedgerEntryView) => !e.amount.amount.startsWith('-');
  const columns: Column<LedgerEntryView>[] = [
    { key: 'date', header: 'Date', nowrap: true, render: (e) => formatDate(e.effectiveDate) },
    {
      key: 'entry',
      header: 'Entry',
      render: (e) => (
        <span className="block min-w-0">
          <span className="font-medium">{ENTRY_LABEL[e.type]}</span>{' '}
          <span className="text-xs text-subtle-foreground">#{e.seq}</span>
          {(e.reason || e.reversesEntryId) && (
            <span className="block text-xs text-muted-foreground">
              {e.reason}
              {e.reason && e.reversesEntryId ? ' · ' : ''}
              {e.reversesEntryId ? `reverses #${seqById.get(e.reversesEntryId) ?? '?'}` : ''}
            </span>
          )}
        </span>
      ),
    },
    { key: 'in', header: 'Money in', align: 'right', render: (e) => (signed(e) ? formatMoney(e.amount) : '') },
    {
      key: 'out',
      header: 'Money out',
      align: 'right',
      render: (e) => (signed(e) ? '' : formatMoney({ ...e.amount, amount: e.amount.amount.slice(1) })),
    },
    { key: 'balance', header: 'Balance', align: 'right', render: (e) => <span className="font-medium">{formatMoney(e.balanceAfter)}</span> },
  ];
  return (
    <section aria-labelledby="ledger-heading" className="space-y-2">
      <div className="flex items-baseline justify-between gap-3">
        <h3 id="ledger-heading" className="text-sm font-semibold">
          Ledger
        </h3>
        <p className="text-xs text-muted-foreground">
          {entries.length} {entries.length === 1 ? 'entry' : 'entries'}, newest first
        </p>
      </div>
      <div className="overflow-hidden rounded-lg border border-border">
        <DataTable caption="Ledger" columns={columns} rows={shown} rowKey={(e) => e.entryId} />
      </div>
      {entries.length > LEDGER_PREVIEW && (
        <Button size="sm" variant="ghost" onClick={() => setAll((v) => !v)}>
          {all ? `Show the latest ${LEDGER_PREVIEW}` : `Show all ${entries.length} entries`}
        </Button>
      )}
    </section>
  );
}

type Action = 'withdraw' | 'topup' | 'transfer' | 'adjust' | null;

/**
 * What can be done, then what is waiting.
 *
 * `deposit`: a fixed-term deposit takes nothing in and lets nothing out during its term (spec D5), so only the
 * adjustment -- the two-person correction -- is offered.
 *
 * `termAccount`: money comes in during its term but nothing goes out, so no withdrawal is offered; once it has
 * matured, nothing comes in either -- it only waits to be paid out.
 */
function Movements({ account, deposit, termAccount }: { account: AccountView; deposit: boolean; termAccount: TermAccountView | null }) {
  const policyNumber = account.policyNumber;
  const moneyIn = !deposit && (termAccount === null || termAccount.status === 'RUNNING');
  const moneyOut = !deposit && termAccount === null;
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;
  // The second signature on withdrawals and adjustments (accounts.approve, 2026-10-26).
  const isFinance = can('accounts.approve');
  // Requesting a withdrawal or a top-up is customer service's (accounts.transact, 2026-10-26); finance signs.
  const canTransact = can('accounts.transact');

  // Select the stored resource and default OUTSIDE the selector: `?? []` inside it returns a fresh
  // array on every call, which Zustand reads as a change and re-renders forever -- the render loop
  // createResourceSlice warns about, and a blank page in the browser.
  const withdrawalsResource = useAccumulationStore((s) => s.withdrawals[policyNumber]);
  const withdrawals = withdrawalsResource?.data ?? NONE_WITHDRAWN;
  const waiting = useWaiting(policyNumber);
  const [action, setAction] = useState<Action>(null);
  // What the last top-up or withdrawal did, shown until the next action is chosen (2026-10-28). The form used to reset
  // in place with one grey line, and a recorded top-up read as if nothing had happened.
  const [done, setDone] = useState<{ kind: 'topup'; topUp: TopUpView } | { kind: 'withdraw'; withdrawal: WithdrawalView } | null>(null);
  const open = account.status === 'OPEN';
  const close = () => setAction(null);
  const choose = (next: Action) => {
    setDone(null);
    setAction(next);
  };

  const actionButton = (value: Exclude<Action, null>, label: string, icon: ReactNode) => (
    <Button size="sm" variant={action === value ? 'secondary' : 'outline'} aria-expanded={action === value}
      onClick={() => choose(action === value ? null : value)}>
      {icon}
      {label}
    </Button>
  );

  return (
    <div className="space-y-4">
      {open && (
        <div className="flex flex-wrap gap-2">
          {canTransact && moneyOut && actionButton('withdraw', 'Request withdrawal', <ArrowUpRight aria-hidden className="size-4" />)}
          {canTransact && moneyIn && actionButton('topup', 'Request top-up', <ArrowDownLeft aria-hidden className="size-4" />)}
          {/* Finance's: recording a transfer puts money on the account, and an adjustment is a
              correction a second finance user signs. Not offered at all to anyone else, rather than
              a button that would 403. */}
          {isFinance && moneyIn && actionButton('transfer', 'Record transfer in', <ArrowRightLeft aria-hidden className="size-4" />)}
          {isFinance && actionButton('adjust', 'Propose adjustment', <PencilLine aria-hidden className="size-4" />)}
        </div>
      )}

      {done?.kind === 'topup' && (
        <Receipt
          heading={done.topUp.status === 'COLLECTED' ? 'Top-up credited' : 'Top-up requested'}
          lines={[
            { label: 'Amount', value: formatMoney(done.topUp.amount) },
            { label: 'How it came in', value: topUpHow(done.topUp) },
            ...(done.topUp.receivedOn ? [{ label: 'Received on', value: formatDate(done.topUp.receivedOn) }] : []),
            { label: 'Balance now', value: formatMoney(account.balance) },
          ]}
          note={
            done.topUp.status === 'COLLECTED'
              ? 'It is on the ledger below, less any allocation charge, and in the account value on the Overview.'
              : 'A request was sent to the payer’s phone. It is credited once finance confirms the money came, and waits below until then.'
          }
          onward={
            <Button size="sm" variant="ghost" onClick={() => setDone(null)}>
              Done
            </Button>
          }
        />
      )}
      {done?.kind === 'withdraw' && (
        <Receipt
          heading="Withdrawal requested"
          lines={[
            { label: 'Amount', value: formatMoney(done.withdrawal.amount) },
            { label: 'Paid by', value: `${methodWords(done.withdrawal.method)} to ${describeRoute(done.withdrawal)}` },
          ]}
          note="Nothing has left the account yet. A finance officer approves it below; the money leaves the balance then."
          onward={
            <Button size="sm" variant="ghost" onClick={() => setDone(null)}>
              Done
            </Button>
          }
        />
      )}

      {action === 'withdraw' && (
        <WithdrawalForm
          account={account}
          withdrawals={withdrawals}
          onDone={close}
          onRequested={(withdrawal) => {
            setAction(null);
            setDone({ kind: 'withdraw', withdrawal });
          }}
        />
      )}
      {action === 'topup' && (
        <TopUpForm
          policyNumber={policyNumber}
          onDone={close}
          onRecorded={(topUp) => {
            setAction(null);
            setDone({ kind: 'topup', topUp });
          }}
        />
      )}
      {action === 'transfer' && <TransferInForm policyNumber={policyNumber} onDone={close} />}
      {action === 'adjust' && <AdjustmentForm policyNumber={policyNumber} onDone={close} />}

      {waiting.count > 0 && (
        <section aria-labelledby="waiting-heading" className="rounded-lg border border-border">
          <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 px-4 pt-3 pb-1">
            <h3 id="waiting-heading" className="text-sm font-semibold">
              Waiting <span className="font-normal text-muted-foreground">· {waiting.count}</span>
            </h3>
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <RefreshCw aria-hidden className="size-3.5" />
              Updates by itself every few seconds
            </p>
          </div>
          <div className="divide-y divide-border">
            {waiting.withdrawals.map((w) => (
              <WithdrawalRow key={w.withdrawalId} withdrawal={w} viewerSubject={viewerSubject} isFinance={isFinance} />
            ))}
            {waiting.topUps.map((t) => (
              <WaitingLine
                key={t.topUpId}
                what="Top-up"
                amount={formatMoney(t.amount)}
                status={<StatusBadge kind="accountTopUp" value={t.status} />}
                detail={<>{topUpHow(t)} · requested by <StaffName subject={t.requestedBy} /> · waiting for the money to arrive</>}
              />
            ))}
            {waiting.adjustments.map((a) => (
              <AdjustmentRow key={a.adjustmentId} adjustment={a} viewerSubject={viewerSubject} isFinance={isFinance} />
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

/** How a top-up came in, in one phrase: "Mobile money from +255…", "Cash · RC-104". */
function topUpHow(t: TopUpView): string {
  if (t.method === 'MOBILE_MONEY') return `Mobile money from ${t.payerRef}`;
  return [methodWords(t.method), t.reference].filter(Boolean).join(' · ');
}

/** One waiting item: what it is and how much on the first line, who and why it waits on the second. */
function WaitingLine({ what, amount, status, detail, children }: {
  what: string;
  amount: string;
  status: ReactNode;
  detail: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="space-y-2 px-4 py-3">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="text-sm font-medium">{what}</span>
        <span className="text-sm font-medium tabular-nums">{amount}</span>
        {status}
      </div>
      <p className="text-xs text-muted-foreground">{detail}</p>
      {children}
    </div>
  );
}

/**
 * Every request that is no longer waiting -- paid, collected, failed, decided -- newest first, so "what happened to
 * the withdrawal I asked for" has an answer on the tab rather than in the ledger's wording.
 */
function PastRequests({ policyNumber }: { policyNumber: string }) {
  const withdrawalsResource = useAccumulationStore((s) => s.withdrawals[policyNumber]);
  const topUpsResource = useAccumulationStore((s) => s.topUps[policyNumber]);
  const adjustmentsResource = useAccumulationStore((s) => s.adjustments[policyNumber]);
  type Row = { id: string; at: string; what: string; amount: string; how: string; status: ReactNode };
  const rows: Row[] = [
    ...(withdrawalsResource?.data ?? NONE_WITHDRAWN)
      .filter((w) => w.status === 'PAID' || w.status === 'FAILED')
      .map((w) => ({
        id: w.withdrawalId,
        at: w.requestedAt,
        what: 'Withdrawal',
        amount: formatMoney(w.amount),
        how: `${methodWords(w.method)} · ${describeRoute(w)}`,
        status: <StatusBadge kind="accountWithdrawal" value={w.status} />,
      })),
    ...(topUpsResource?.data ?? NONE_TOPPED_UP)
      .filter((t) => t.status !== 'REQUESTED')
      .map((t) => ({
        id: t.topUpId,
        at: t.requestedAt,
        what: 'Top-up',
        amount: formatMoney(t.amount),
        how: topUpHow(t),
        status: <StatusBadge kind="accountTopUp" value={t.status} />,
      })),
    ...(adjustmentsResource?.data ?? NONE_ADJUSTED)
      .filter((a) => a.status !== 'PROPOSED')
      .map((a) => ({
        id: a.adjustmentId,
        at: a.proposedAt,
        what: 'Adjustment',
        amount: formatMoney({ amount: a.amount, currencyCode: 'TZS' }),
        how: a.reason,
        status: <StatusBadge kind="accountAdjustment" value={a.status} />,
      })),
  ].sort((a, b) => b.at.localeCompare(a.at));
  if (rows.length === 0) return null;
  const columns: Column<Row>[] = [
    { key: 'at', header: 'Requested', nowrap: true, render: (r) => formatInstantDay(r.at) },
    { key: 'what', header: 'Request', render: (r) => <span className="font-medium">{r.what}</span> },
    { key: 'how', header: 'Paid to / from', secondary: true, render: (r) => <span className="text-muted-foreground">{r.how}</span> },
    { key: 'amount', header: 'Amount', align: 'right', render: (r) => r.amount },
    { key: 'status', header: 'Status', render: (r) => r.status },
  ];
  return (
    <section aria-labelledby="past-requests-heading" className="space-y-2">
      <h3 id="past-requests-heading" className="text-sm font-semibold">
        Past requests
      </h3>
      <div className="overflow-hidden rounded-lg border border-border">
        <DataTable caption="Past requests" columns={columns} rows={rows} rowKey={(r) => r.id} />
      </div>
    </section>
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

/** A movement form's frame: a titled, flat box under the action buttons, with a way out. */
function ActionBox({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-3 rounded-lg border border-border-strong p-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-semibold">{title}</h3>
        <Button type="button" size="sm" variant="ghost" onClick={onClose}>
          Close
        </Button>
      </div>
      {children}
    </section>
  );
}

function WithdrawalForm({
  account,
  withdrawals,
  onDone,
  onRequested,
}: {
  account: AccountView;
  withdrawals: WithdrawalView[];
  onDone: () => void;
  onRequested: (withdrawal: WithdrawalView) => void;
}) {
  const policyNumber = account.policyNumber;
  const withdraw = useAccumulationStore((s) => s.withdraw);
  const acting = useAccumulationStore((s) => s.acting[`withdraw.${policyNumber}`]);
  const [armed, setArmed] = useState<WithdrawalValues | null>(null);
  const form = useForm<WithdrawalValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(withdrawalSchema),
    defaultValues: { amount: '', ...BLANK_ROUTE },
  });
  const gates = requestWithdrawalGates(account, withdrawals);
  const refused = gates.some((g) => !g.ok && g.hard);

  if (armed) {
    return (
      <ConfirmAct
        heading="Request this withdrawal?"
        consequence={
          <>
            <strong>{formatMoney({ amount: armed.amount, currencyCode: account.balance.currencyCode })}</strong> from{' '}
            <strong>{policyNumber}</strong>, paid by {methodWords(armed.method).toLowerCase()} to{' '}
            <strong>{describeRoute(routeBody(armed))}</strong>. It is valued again when a second person approves it.
          </>
        }
        reversal="Nothing moves until a finance officer approves it. A request can be left unapproved."
        confirmLabel="Request withdrawal"
        busy={acting?.status === 'loading'}
        onConfirm={() => {
          void withdraw(policyNumber, { amount: armed.amount, ...routeBody(armed) }, startMutation()).then((requested) => {
            if (requested) onRequested(requested);
          });
          setArmed(null);
        }}
        onCancel={() => setArmed(null)}
      />
    );
  }

  return (
    <ActionBox title="Withdraw from the account" onClose={onDone}>
      <GatePanel gates={gates} title="Before requesting" />
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form onSubmit={form.handleSubmit((v) => setArmed(v))} className="space-y-4">
        <div className="max-w-xs">
          <FormField
            label={`Amount (${account.balance.currencyCode})`}
            hint="The account keeps its product’s minimum balance, after any policy loan."
            error={form.formState.errors.amount?.message}
          >
            <Input {...form.register('amount')} placeholder="40000.00" inputMode="decimal" />
          </FormField>
        </div>
        <PayoutRouteFields form={form} />
        <Button type="submit" size="sm" variant="primary" disabled={refused}>
          Continue
        </Button>
      </form>
    </ActionBox>
  );
}

/**
 * A top-up (2026-10-28): asked of the payer's phone now, or recorded as already received by any of the five ways money
 * reaches the insurer by hand -- the same chooser the Billing tab's invoices use. One person records it: a received
 * top-up is credited as soon as payment receipts it, with no second signature.
 */
function TopUpForm({
  policyNumber,
  onDone,
  onRecorded,
}: {
  policyNumber: string;
  onDone: () => void;
  onRecorded: (topUp: TopUpView) => void;
}) {
  const topUp = useAccumulationStore((s) => s.topUp);
  const acting = useAccumulationStore((s) => s.acting[`topup.${policyNumber}`]);
  const form = useForm<TopUpValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(topUpSchema),
    defaultValues: { amount: '', method: 'MOBILE_MONEY', payerRef: '', reference: '', receivedOn: todayIso() },
  });
  const method = useWatch({ control: form.control, name: 'method' });
  const received = method !== 'MOBILE_MONEY' ? RECEIPT_METHODS.find((m) => m.value === method) : undefined;
  const errors = form.formState.errors;

  return (
    <ActionBox title="Top up the account" onClose={onDone}>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form
        onSubmit={form.handleSubmit((v) => {
          const body =
            v.method === 'MOBILE_MONEY'
              ? { amount: v.amount, payerRef: v.payerRef.trim() }
              : {
                  amount: v.amount,
                  method: v.method,
                  reference: v.reference.trim(),
                  receivedOn: v.receivedOn,
                  ...(v.payerRef.trim() ? { payerRef: v.payerRef.trim() } : {}),
                };
          void topUp(policyNumber, body, startMutation()).then((recorded) => {
            if (recorded) onRecorded(recorded);
          });
        })}
        className="space-y-3"
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="How is it being paid?" hint={received?.hint} error={errors.method?.message}>
            <Select {...form.register('method')}>
              <optgroup label="Ask for it now">
                <option value="MOBILE_MONEY">Mobile money — send a request to the payer’s phone</option>
              </optgroup>
              <optgroup label="Already received — record it">
                {RECEIPT_METHODS.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
              </optgroup>
            </Select>
          </FormField>
          <TextField form={form} name="amount" label="Amount" placeholder="20000.00" inputMode="decimal" />
          {received ? (
            <>
              <FormField label={received.reference} error={errors.reference?.message}>
                <Input className="font-mono" {...form.register('reference')} />
              </FormField>
              <FormField label="Received on" error={errors.receivedOn?.message}>
                <Controller
                  control={form.control}
                  name="receivedOn"
                  render={({ field }) => <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />}
                />
              </FormField>
              <TextField form={form} name="payerRef" label="Paid by (optional)" placeholder="As the slip shows it" />
            </>
          ) : (
            <TextField form={form} name="payerRef" label="Collect from" placeholder="Mobile money number" />
          )}
        </div>
        <Button type="submit" size="sm" variant="primary" disabled={acting?.status === 'loading'}>
          {received ? 'Record top-up' : 'Request top-up'}
        </Button>
      </form>
    </ActionBox>
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
    <ActionBox title="Record a transfer in" onClose={onDone}>
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
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField form={form} name="amount" label="Amount received" placeholder="300000.00" inputMode="decimal" />
          <TextField form={form} name="sourceScheme" label="From scheme" placeholder="NSSF member 1234" />
          <TextField form={form} name="documentRef" label="Document reference (optional)" />
        </div>
        <Button type="submit" size="sm" variant="primary" disabled={acting?.status === 'loading'}>
          Record transfer in
        </Button>
      </form>
    </ActionBox>
  );
}

function AdjustmentForm({ policyNumber, onDone }: { policyNumber: string; onDone: () => void }) {
  const propose = useAccumulationStore((s) => s.proposeAdjustment);
  const acting = useAccumulationStore((s) => s.acting[`adjust.${policyNumber}`]);
  const form = useForm<AdjustmentValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(adjustmentSchema), defaultValues: { amount: '', reason: '' } });

  return (
    <ActionBox title="Propose an adjustment" onClose={onDone}>
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
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField form={form} name="amount" label="Amount" placeholder="-250.00" inputMode="decimal" />
          <TextField form={form} name="reason" label="Reason" placeholder="Fee charged twice in error" />
        </div>
        <Button type="submit" size="sm" variant="primary" disabled={acting?.status === 'loading'}>
          Propose adjustment
        </Button>
      </form>
    </ActionBox>
  );
}

/** What a withdrawal is waiting for, in words: a person's approval, or the payment itself. */
function waitingFor(w: WithdrawalView): string {
  if (w.status === 'REQUESTED') return 'waiting for a finance officer to approve it';
  if (w.method === 'EFT') return 'taken from the balance; waiting for finance to make the bank transfer';
  if (w.method === 'CHEQUE') return 'taken from the balance; waiting for finance to write the cheque';
  return 'taken from the balance; waiting for the mobile-money provider to confirm the payment';
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
  const to = describeRoute(withdrawal);

  return (
    <WaitingLine
      what="Withdrawal"
      amount={formatMoney(withdrawal.amount)}
      status={<StatusBadge kind="accountWithdrawal" value={withdrawal.status} />}
      detail={
        <>
          {methodWords(withdrawal.method)} to {to} · requested by <StaffName subject={withdrawal.requestedBy} />
          {withdrawal.approvedBy && <> · approved by <StaffName subject={withdrawal.approvedBy} /></>} · {waitingFor(withdrawal)}
        </>
      }
    >
      {/* 202 from the rail: REQUESTED, never "paid" -- the badge reads PAID only once the payment is confirmed. */}
      {acting?.status === 'success' && withdrawal.status === 'APPROVED' && (
        <p className="text-xs text-muted-foreground" role="status">
          Approved. {withdrawal.method === 'MOBILE_MONEY' ? 'The payment has been requested from the provider.' : 'It is in finance’s queue of payments to make.'}
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
                  <strong>{formatMoney(withdrawal.amount)}</strong> leaves the account now and is paid by{' '}
                  {methodWords(withdrawal.method).toLowerCase()} to {to}.
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
            <Button size="sm" variant="primary" disabled={refused} onClick={() => setArmed(true)}>
              Approve
            </Button>
          )}
        </>
      )}
    </WaitingLine>
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
    <WaitingLine
      what="Adjustment"
      amount={formatMoney(money)}
      status={<StatusBadge kind="accountAdjustment" value={adjustment.status} />}
      detail={
        <>
          {adjustment.reason} · proposed by <StaffName subject={adjustment.proposedBy} /> · waiting for a second person
          to decide it
        </>
      }
    >
      {adjustment.status === 'PROPOSED' && isFinance && (
        <>
          <GatePanel gates={gates} title="Before deciding" />
          {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="primary"
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
    </WaitingLine>
  );
}
