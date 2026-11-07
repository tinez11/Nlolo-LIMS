import type { LoanRepaidPayout } from '@/api/policies';
import { Field } from '@/components/Field';
import { formatMoney } from '@/lib/money';
import { useLoanRepaid } from './useLoanRepaid';

const WHAT: Record<LoanRepaidPayout, string> = {
  CLAIM: 'claim', MATURITY: 'payout', SURRENDER: 'surrender', ACCOUNT: 'payout',
};

/**
 * "Loan repaid first" (2026-11-07, the user, decision E7): a claim, maturity or surrender repays the policy's loans
 * before anything is paid out, and the screen says how much. Nothing is shown when the payout repaid no loan.
 */
export function LoanRepaidField({ payout, payoutRef }: { payout: LoanRepaidPayout; payoutRef: string | null | undefined }) {
  const repaid = useLoanRepaid(payout, payoutRef);
  if (!repaid) return null;
  return (
    <Field
      label="Loan repaid first"
      value={formatMoney(repaid)}
      note={`Taken off this ${WHAT[payout]} to repay the policy's loan; the payee is paid the rest.`}
    />
  );
}
