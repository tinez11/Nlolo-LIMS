import { useEffect } from 'react';
import type { AnnuityContractView } from '@/api/types';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate, todayIso } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAnnuityStore } from '@/store/annuityStore';

const FREQUENCY_LABEL: Record<string, string> = {
  MONTHLY: 'a month',
  QUARTERLY: 'a quarter',
  SEMI_ANNUAL: 'every six months',
  ANNUAL: 'a year',
};

/** The four settings in words: "10-year guarantee · joint, 50% to the survivor · 3% a year · capital protected". */
function describeForm(c: Pick<AnnuityContractView, 'guaranteeYears' | 'joint' | 'survivorPercent' | 'escalationPercent' | 'capitalProtected'>): string {
  const parts: string[] = [];
  parts.push(c.guaranteeYears ? `${c.guaranteeYears}-year guarantee` : 'Life only');
  if (c.joint) parts.push(`joint, ${c.survivorPercent}% to the survivor`);
  if (c.escalationPercent && Number(c.escalationPercent) > 0) parts.push(`rising ${c.escalationPercent}% a year`);
  if (c.capitalProtected) parts.push('capital protected');
  return parts.join(' · ');
}

/** The next anniversary of the first payment on or after today: when the income next escalates. */
function nextEscalation(firstDue: string): string {
  const today = todayIso();
  const [y, m, d] = firstDue.split('-').map(Number);
  let year = y!;
  let candidate = `${String(year).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  while (candidate <= today) {
    year += 1;
    candidate = `${String(year).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  }
  return candidate;
}

/**
 * An annuity policy's contract (product step 5): the income it locked, the rate cell it came from,
 * and where it is -- paying, paying a survivor, paying the beneficiaries inside a guarantee, ended.
 * The figures are the contract's own, locked once when the premium arrived; nothing here recomputes.
 */
export function PolicyAnnuityPanel({ policyNumber }: { policyNumber: string }) {
  const contract = useAnnuityStore((s) => s.contract[policyNumber]);
  const loadContract = useAnnuityStore((s) => s.loadContract);

  useEffect(() => {
    void loadContract(policyNumber);
  }, [policyNumber, loadContract]);

  if (!contract || isInitialLoad(contract)) return <LoadingBlock />;
  if (contract.status === 'error' && contract.error && contract.data === null) {
    return <ErrorPanel error={contract.error} onRetry={() => void loadContract(policyNumber)} />;
  }
  if (!contract.data) return null;
  const c = contract.data;
  const locked = c.instalment != null;

  return (
    <div className="space-y-4">
      {c.status === 'LOCK_FAILED' && (
        <div role="alert" className="rounded-md border border-status-danger-fg/40 bg-status-danger-bg px-4 py-3 text-sm">
          <p className="font-medium">This annuity could not be set up, and pays nothing.</p>
          <p className="text-muted-foreground">{c.lockFailureReason}</p>
        </div>
      )}
      <dl className="divide-y divide-border rounded-md border border-border">
        <Field label="Status" value={<StatusBadge kind="annuityContract" value={c.status} />} />
        {locked && (
          <Field label="Income" value={`${formatMoney(c.instalment)} ${FREQUENCY_LABEL[c.frequency ?? ''] ?? ''}`} emphasis />
        )}
        <Field label="Form" value={c.formCode ? `${c.formCode} — ${describeForm(c)}` : '—'} />
        <Field label="Purchase price" value={formatMoney(c.purchasePrice)} />
        {locked && (
          <>
            <Field label="Annual income" value={formatMoney(c.annualIncome)} />
            <Field
              label="Priced on"
              value={`${c.annualRatePerMille} per 1,000 at age ${c.annuitantAge}${c.jointAge != null ? ` (joint life ${c.jointAge})` : ''}${
                c.rateSex ? `, ${c.rateSex.toLowerCase()}` : ''
              } · frequency factor ${c.factor} · locked ${formatDate(c.lockedOn)}`}
            />
            <Field label="First payment" value={formatDate(c.firstDueDate)} />
            {c.escalationPercent && Number(c.escalationPercent) > 0 && c.firstDueDate && (
              <Field label="Next increase" value={formatDate(nextEscalation(c.firstDueDate))} />
            )}
            {c.guaranteeEndDate && <Field label="Guarantee ends" value={formatDate(c.guaranteeEndDate)} />}
          </>
        )}
        {!locked && c.status === 'AWAITING_PAYMENT' && (
          <Field label="Income" value="Locked when the single premium is collected, at the rate in force that day" />
        )}
        {c.firstDeathDate && <Field label="First death" value={formatDate(c.firstDeathDate)} />}
        {c.lastDeathDate && <Field label="Last death" value={formatDate(c.lastDeathDate)} />}
        {Number(c.overpaymentOwed.amount) > 0 && (
          <Field label="Overpayment owed back" value={formatMoney(c.overpaymentOwed)} />
        )}
      </dl>
    </div>
  );
}
