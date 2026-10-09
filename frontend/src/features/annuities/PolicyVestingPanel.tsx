import { useEffect, useState } from 'react';
import type { VestingView } from '@/api/types';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate, formatInstant, todayIso } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAccumulationStore } from '@/store/accumulationStore';
import { useAnnuityStore } from '@/store/annuityStore';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { useAnnuityTerms } from './useAnnuityTerms';
import { VestingInstructionForm } from './VestingInstructionForm';

const FREQUENCY_WORDS: Record<string, string> = {
  MONTHLY: 'monthly',
  QUARTERLY: 'quarterly',
  SEMI_ANNUAL: 'every six months',
  ANNUAL: 'annually',
};

/** The sweep's own words for an age hold -- the one hold re-confirmation clears. */
const AGE_HOLD = 'Age re-confirmation needed: the date of birth or sex on record has changed since it was confirmed';

/** What it will vest into, in words: "LIFE-0G · monthly · lump sum 25%", or the default's. */
function describeChoice(v: VestingView): string {
  const choice = `${v.formCode} · ${FREQUENCY_WORDS[v.frequency] ?? v.frequency}`;
  return v.instructed ? `${choice} · lump sum ${v.lumpSumPercent}%` : `the default: ${choice}, no lump sum`;
}

/**
 * A pension still saving (product step 5 D2): when it vests and into what, the balance it will vest
 * with, what age was confirmed at sale, and -- when the sweep could not vest it -- why. Staff record
 * an instruction to vest early, defer, or choose a form and lump sum; and re-confirm age when the
 * party record changed since it was confirmed.
 */
export function PolicyVestingPanel({ policyNumber, productId }: { policyNumber: string; productId: string }) {
  const vesting = useAnnuityStore((s) => s.vesting[policyNumber]);
  const loadVesting = useAnnuityStore((s) => s.loadVesting);
  const reconfirmAge = useAnnuityStore((s) => s.reconfirmAge);
  const acting = useAnnuityStore((s) => s.acting[`vesting.${policyNumber}`]);
  const account = useAccumulationStore((s) => s.account[policyNumber]);
  // The instruction is priced on the product's CURRENT version, so its forms are the ones offered.
  const snapshot = useProductStore(selectProductSnapshot(productId));
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);
  const terms = useAnnuityTerms(productId, snapshot.data?.productVersionId ?? '');
  const [instructing, setInstructing] = useState(false);

  useEffect(() => {
    void loadVesting(policyNumber);
  }, [policyNumber, loadVesting]);
  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  if (!vesting || isInitialLoad(vesting)) return <LoadingBlock />;
  if (vesting.status === 'error' && vesting.error && vesting.data === null) {
    return <ErrorPanel error={vesting.error} onRetry={() => void loadVesting(policyNumber)} />;
  }
  if (!vesting.data) return null;
  const v = vesting.data;

  return (
    <div className="space-y-4">
      <p className="text-title">Vests on {formatDate(v.vestingDate)}</p>
      {v.holdReason && (
        <div role="alert" className="rounded-md border border-status-danger-fg/40 bg-status-danger-bg px-4 py-3 text-sm">
          <p className="font-medium">Held from vesting since {formatInstant(v.heldAt)}. It is retried every day.</p>
          <p className="text-muted-foreground">{v.holdReason}</p>
        </div>
      )}
      <dl className="divide-y divide-border rounded-md border border-border">
        <Field label="Status" value={<StatusBadge kind="annuityContract" value="ACCUMULATING" />} />
        <Field label="Target date" value={formatDate(v.targetDate)} />
        <Field label="Window" value={`${formatDate(v.earliestVestingDate)} to ${formatDate(v.latestVestingDate)}`} />
        <Field label="Balance" value={account?.data ? formatMoney(account.data.balance) : '—'} />
        <Field label="Vests into" value={describeChoice(v)} />
        {v.contributions && (
          <Field label="Contributions" value={v.contributions === 'CONTINUE' ? 'Continue to the vesting date' : `Stopped at ${formatDate(v.targetDate)}`} />
        )}
        <Field
          label="Age confirmed"
          value={`${formatDate(v.confirmedDateOfBirth)}${v.confirmedSex ? `, ${v.confirmedSex.toLowerCase()}` : ''} · by ${v.ageConfirmedBy}`}
        />
      </dl>

      {acting?.status === 'error' && acting.error && !instructing && <InlineError error={acting.error} />}
      {instructing && terms?.terms ? (
        <VestingInstructionForm
          policyNumber={policyNumber}
          vesting={v}
          terms={terms.terms}
          today={todayIso()}
          onDone={() => setInstructing(false)}
        />
      ) : (
        <div className="flex gap-2">
          <Button size="sm" onClick={() => setInstructing(true)} disabled={!terms?.terms}>
            Record vesting instruction
          </Button>
          {v.holdReason === AGE_HOLD && (
            <Button size="sm" variant="ghost" onClick={() => void reconfirmAge(policyNumber)} disabled={acting?.status === 'loading'}>
              Re-confirm age
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
