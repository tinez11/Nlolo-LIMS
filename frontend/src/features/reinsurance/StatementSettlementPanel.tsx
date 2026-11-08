import { useCallback, useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { approveSettlement, getSettlements, recordSettlement, refuseSettlement } from '@/api/reinsurance';
import type {
  NewReinsuranceSettlement,
  ReinsuranceSettlement,
  ReinsuranceSettlementKind,
  ReinsuranceSettlementsOpen,
} from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { StaffName } from '@/components/StaffName';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { formatDate, formatInstant, todayIso } from '@/lib/dates';
import { compareAmounts, formatMoney } from '@/lib/money';
import { barredAsOwn } from '@/lib/ownWork';

/** The four movements, in a finance officer's words. */
const SETTLEMENT_KIND: Record<ReinsuranceSettlementKind, string> = {
  RECEIVED: 'Received from the reinsurer',
  PAID: 'Pay the reinsurer',
  PROFIT_COMMISSION: 'Profit commission received',
  WITHHELD_RELEASED: 'Release funds withheld',
};

const STATUS_WORDS: Record<string, string> = {
  AWAITING_APPROVAL: 'Waiting for a second person',
  POSTED: 'Posted',
  REQUESTED: 'Under Payments to make',
  PAID: 'Paid',
  FAILED: 'Transfer failed; owed again',
  REFUSED: 'Refused',
};

/** What each kind may take: the statement's open balance for it. */
function openFor(kind: ReinsuranceSettlementKind, o: ReinsuranceSettlementsOpen) {
  return kind === 'RECEIVED' ? o.theyOweUs
    : kind === 'PAID' ? o.weOweThem
      : kind === 'PROFIT_COMMISSION' ? o.profitCommissionDue : o.withheldHeld;
}

const positive = (m: { amount: string }) => compareAmounts(m.amount, '0.00') > 0;

/**
 * Settling an approved statement (2026-11-08). What it posted into the current account, the profit commission
 * receivable and funds withheld leaves here, one movement at a time: recorded by one finance person, confirmed by a
 * second, and never more than the statement still has open. Paying the reinsurer becomes a bank transfer under
 * "Payments to make".
 */
export function StatementSettlementPanel({ statementId }: { statementId: string }) {
  const [open, setOpen] = useState<ReinsuranceSettlementsOpen | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);

  const reload = useCallback(() => {
    getSettlements(statementId).then(setOpen, (e: ApiError) => setLoadError(e));
  }, [statementId]);
  useEffect(() => {
    reload();
  }, [reload]);

  if (loadError) return <InlineError error={loadError} />;
  if (!open) return null;

  const kinds = (Object.keys(SETTLEMENT_KIND) as ReinsuranceSettlementKind[]).filter((k) => positive(openFor(k, open)));
  const settled = kinds.length === 0;

  return (
    <section className="space-y-3 rounded-xl border border-border bg-surface p-4 shadow-card" aria-label="Settlement">
      <div>
        <h2 className="text-sm font-medium">Settlement</h2>
        <p className="text-xs text-muted-foreground">
          What this statement left open, and the money and releases recorded against it.
        </p>
      </div>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-4">
        <Figure label="They owe us" value={open.theyOweUs} />
        <Figure label="We owe them" value={open.weOweThem} />
        <Figure label="Profit commission due" value={open.profitCommissionDue} />
        <Figure label="Funds withheld held" value={open.withheldHeld} />
      </dl>
      {settled ? (
        <p className="text-sm">Nothing is left open on this statement.</p>
      ) : (
        can('reinsurance.manage') && <RecordForm statementId={statementId} open={open} kinds={kinds} onRecorded={reload} />
      )}
      {open.settlements.length > 0 && (
        <ul className="divide-y divide-border rounded-md border border-border" aria-label="Settlements">
          {open.settlements.map((s) => <SettlementRow key={s.settlementId} settlement={s} onDecided={reload} />)}
        </ul>
      )}
    </section>
  );
}

function Figure({ label, value }: { label: string; value: { amount: string; currencyCode: string } }) {
  return (
    <div>
      <dt className="text-xs text-label">{label}</dt>
      <dd className="tabular-nums">{formatMoney(value)}</dd>
    </div>
  );
}

function RecordForm({ statementId, open, kinds, onRecorded }: {
  statementId: string;
  open: ReinsuranceSettlementsOpen;
  kinds: ReinsuranceSettlementKind[];
  onRecorded: () => void;
}) {
  const [kind, setKind] = useState<ReinsuranceSettlementKind>(kinds[0]!);
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState<'BANK_DEPOSIT' | 'CHEQUE'>('BANK_DEPOSIT');
  const [reference, setReference] = useState('');
  const [receivedOn, setReceivedOn] = useState(todayIso());
  const [bank, setBank] = useState('');
  const [branch, setBranch] = useState('');
  const [accountName, setAccountName] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [note, setNote] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const current = kinds.includes(kind) ? kind : kinds[0]!;
  const limit = openFor(current, open);
  const moneyIn = current === 'RECEIVED' || current === 'PROFIT_COMMISSION';

  async function submit() {
    setProblem(null);
    setError(null);
    if (!/^\d{1,15}(\.\d{1,2})?$/.test(amount.trim()) || !(Number(amount) > 0)) {
      setProblem('An amount above zero, to at most two decimal places');
      return;
    }
    if (compareAmounts(Number(amount).toFixed(2), limit.amount) > 0) {
      setProblem(`At most ${formatMoney(limit)} on this statement`);
      return;
    }
    const body: NewReinsuranceSettlement = {
      kind: current,
      amount: Number(amount).toFixed(2),
      note: note.trim() || null,
      ...(moneyIn ? { method, reference: reference.trim(), receivedOn } : {}),
      ...(current === 'PAID' ? {
        payeeBank: bank.trim(), payeeBranch: branch.trim() || null, payeeAccountName: accountName.trim(),
        payeeAccountNumber: accountNumber.trim(),
      } : {}),
    };
    setBusy(true);
    try {
      await recordSettlement(statementId, body);
      setAmount('');
      setReference('');
      setNote('');
      onRecorded();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="space-y-3 rounded-md border border-border p-3" aria-label="Record a settlement"
      onSubmit={(e) => { e.preventDefault(); void submit(); }}>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FormField required label="What is it?">
          <Select value={current} onChange={(e) => setKind(e.target.value as ReinsuranceSettlementKind)}>
            {kinds.map((k) => <option key={k} value={k}>{SETTLEMENT_KIND[k]}</option>)}
          </Select>
        </FormField>
        <FormField required label={`Amount (${limit.currencyCode})`} hint={`At most ${formatMoney(limit)}.`} error={problem ?? undefined}>
          <Input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" />
        </FormField>
        {moneyIn && (
          <>
            <FormField required label="How did it come in?">
              <Select value={method} onChange={(e) => setMethod(e.target.value as 'BANK_DEPOSIT' | 'CHEQUE')}>
                <option value="BANK_DEPOSIT">Bank deposit</option>
                <option value="CHEQUE">Cheque</option>
              </Select>
            </FormField>
            <FormField required label={method === 'CHEQUE' ? 'Cheque number' : 'Bank reference'}>
              <Input value={reference} onChange={(e) => setReference(e.target.value)} />
            </FormField>
            <FormField required label="Received on">
              <DatePicker value={receivedOn} onChange={(iso) => iso && setReceivedOn(iso)} />
            </FormField>
          </>
        )}
        {current === 'PAID' && (
          <>
            <FormField required label="Reinsurer's bank">
              <Input value={bank} onChange={(e) => setBank(e.target.value)} />
            </FormField>
            <FormField label="Branch">
              <Input value={branch} onChange={(e) => setBranch(e.target.value)} />
            </FormField>
            <FormField required label="Name on the account">
              <Input value={accountName} onChange={(e) => setAccountName(e.target.value)} />
            </FormField>
            <FormField required label="Account number">
              <Input value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} />
            </FormField>
          </>
        )}
        <FormField label="Note" className="sm:col-span-2">
          <Input value={note} onChange={(e) => setNote(e.target.value)} />
        </FormField>
      </div>
      {error && <InlineError error={error} />}
      <div className="flex items-center gap-3">
        <Button type="submit" size="sm" disabled={busy}>Record</Button>
        <p className="text-xs text-muted-foreground">
          {current === 'PAID'
            ? 'Once a second finance person confirms it, it waits under Payments to make for the transfer.'
            : 'It posts once a second finance person confirms it.'}
        </p>
      </div>
    </form>
  );
}

function SettlementRow({ settlement: s, onDecided }: { settlement: ReinsuranceSettlement; onDecided: () => void }) {
  const auth = useAuth();
  const viewer = readIdentity(auth.user?.access_token).subject;
  const mayDecide = s.status === 'AWAITING_APPROVAL' && can('reinsurance.approve')
    && !barredAsOwn(viewer, s.recordedBy, 'reinsurance');
  const [armed, setArmed] = useState<'confirm' | 'refuse' | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function decide(confirm: boolean) {
    setBusy(true);
    setError(null);
    try {
      if (confirm) await approveSettlement(s.settlementId);
      else await refuseSettlement(s.settlementId, reason.trim());
      setArmed(null);
      onDecided();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  const how = s.kind === 'PAID'
    ? `to ${s.payeeAccountName ?? ''}, ${s.payeeBank ?? ''} ${s.payeeAccountNumber ?? ''}`.trim()
    : s.reference ? `${s.method === 'CHEQUE' ? 'cheque' : 'bank'} ${s.reference}${s.receivedOn ? `, ${formatDate(s.receivedOn)}` : ''}` : '';

  return (
    <li className="space-y-1.5 px-3 py-2 text-sm">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <span>
          <span className="font-medium">{SETTLEMENT_KIND[s.kind]}</span>
          {how && <span className="text-muted-foreground"> · {how}</span>}
        </span>
        <span className="font-medium tabular-nums">{formatMoney(s.amount)}</span>
      </div>
      <p className="text-xs text-muted-foreground">
        {STATUS_WORDS[s.status] ?? s.status} · recorded {formatInstant(s.recordedAt)} by <StaffName subject={s.recordedBy} />
        {s.decidedBy && <> · decided by <StaffName subject={s.decidedBy} />{s.actedAlone ? ' (alone)' : ''}</>}
        {s.decisionReason && <> · {s.decisionReason}</>}
        {s.note && <> · {s.note}</>}
      </p>
      {error && <InlineError error={error} />}
      {mayDecide && armed === null && (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setArmed('confirm')}>Confirm</Button>
          <Button size="sm" variant="ghost" onClick={() => setArmed('refuse')}>Refuse</Button>
        </div>
      )}
      {armed === 'confirm' && (
        <ConfirmAct
          heading="Confirm this settlement?"
          consequence={s.kind === 'PAID'
            ? <><strong>{formatMoney(s.amount)}</strong> goes to Payments to make, to be transferred to the reinsurer.</>
            : <><strong>{formatMoney(s.amount)}</strong> posts to the ledger now.</>}
          reversal="A posted settlement is corrected by a manual journal."
          confirmLabel={s.kind === 'PAID' ? 'Send to Payments to make' : 'Post it'}
          busy={busy}
          onConfirm={() => void decide(true)}
          onCancel={() => setArmed(null)}
        />
      )}
      {armed === 'refuse' && (
        <div className="space-y-2">
          <FormField required label="Why is it refused?">
            <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          </FormField>
          <div className="flex gap-2">
            <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy} onClick={() => void decide(false)}>
              Refuse settlement
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setArmed(null)}>Cancel</Button>
          </div>
        </div>
      )}
    </li>
  );
}
