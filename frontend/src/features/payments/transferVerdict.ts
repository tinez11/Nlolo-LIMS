import type { ClaimView, PolicyMemberView, PolicyView } from '@/api/types';
import { exitSummary } from '@/features/policies/memberStanding';

/**
 * What finance needs to know about one bank transfer before making it, and whether they may.
 *
 * The queue used to show `mobile`, a raw claim id and `CLAIM_SETTLEMENT`: nothing about whose
 * death it paid, or whether that death had already been paid. And a life COULD be paid twice --
 * three approved claims on one borrower sat here side by side, each with its own transfer, and
 * recording a second would have settled a second claim and booked a second expense for one death.
 *
 * Payment cannot check this itself: it may depend on reference data only, never on claims or
 * policy, and "Record payment made" is pressed AFTER the money has left the bank, so refusing it
 * then would hide a real payment rather than prevent one. The protection belongs here, before the
 * transfer is made. Since one-death-claim-per-life, no new pair can be approved; this catches
 * pairs from before it, and any life that has since left cover.
 */
export interface TransferVerdict {
  /** False when this transfer must not be made; `reasons` says why. */
  payable: boolean;
  reasons: string[];
  /** A warning that does not block: e.g. a payee typed at approval that is not the lender. */
  notes: string[];
  /** Credit life: the lender, who is always the payee. Null otherwise. */
  lenderPartyId: string | null;
}

export function transferVerdict(input: {
  claimId: string;
  payeeRef: string;
  claim: ClaimView | null;
  policy: PolicyView | null;
  member: PolicyMemberView | null;
}): TransferVerdict {
  const { claimId, payeeRef, claim, policy, member } = input;
  const reasons: string[] = [];
  const notes: string[] = [];
  const creditLife = policy?.productCategory === 'CREDIT_LIFE';

  if (claim && claim.status !== 'SETTLEMENT_REQUESTED') {
    reasons.push(`The claim is ${claim.status.replace(/_/g, ' ').toLowerCase()}, not awaiting payment.`);
  }

  if (member) {
    if (member.status === 'EXITED') {
      const left = exitSummary(member);
      reasons.push(
        `This borrower has already left cover${left ? ` (${left})` : ''}. Their death is not paid again.`,
      );
    } else if (member.openDeathClaimId && member.openDeathClaimId !== claimId) {
      reasons.push(
        `Another death claim (${member.openDeathClaimId}) is the one in progress for this borrower. A life is paid once — resolve which claim is real before transferring.`,
      );
    }
  }

  // Individual business: a policy already closed by a death has been paid for.
  if (!member && policy && (policy.status === 'SURRENDERED' || policy.status === 'MATURED')) {
    reasons.push(`Policy ${policy.policyNumber} is already closed (${policy.status.toLowerCase()}).`);
  }

  // A payee typed into the old "Mobile-money destination" box before the lender became the
  // fixed payee. Not blocking -- the lender is known -- but finance must not read it as an
  // account.
  if (creditLife && !payeeRef.includes('policyholder of')) {
    notes.push(`Recorded at approval as “${payeeRef}”. The payee is the lender, not this.`);
  }

  return {
    payable: reasons.length === 0,
    reasons,
    notes,
    lenderPartyId: creditLife ? (policy?.policyholderPartyId ?? null) : null,
  };
}
