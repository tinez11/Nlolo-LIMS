import type { PolicyView } from '@/api/types';

/**
 * In what capacity is a given party connected to a given policy.
 *
 * `GET /policies?relatedPartyId=` matches on three legs -- owner, life assured, active named
 * beneficiary -- and deliberately does NOT return which one matched. It does not need to:
 * `policyholderPartyId`, `lifeAssuredPartyId` and `beneficiaries[]` are all already on
 * `PolicyView`, so the capacity is derivable here and the wire shape stays unchanged for every
 * other caller of a list this endpoint has served since M3.
 *
 * The capacity is the whole point of showing the list at all. "POL-6BD5702F, TZS 1.5m" tells a
 * claims clerk almost nothing; "POL-6BD5702F -- this claimant is the beneficiary, the insured
 * life is someone else" tells them which claim they are about to register and on whose death.
 */

export type PolicyCapacity = 'POLICYHOLDER' | 'LIFE_ASSURED' | 'BENEFICIARY';

/** Ordered owner -> life assured -> beneficiary, which is how the relationship is explained. */
const CAPACITY_ORDER: PolicyCapacity[] = ['POLICYHOLDER', 'LIFE_ASSURED', 'BENEFICIARY'];

export const CAPACITY_LABELS: Record<PolicyCapacity, string> = {
  POLICYHOLDER: 'Owner',
  LIFE_ASSURED: 'Life assured',
  BENEFICIARY: 'Beneficiary',
};

/**
 * Longer forms, used where there is room for a sentence. These say what the capacity MEANS for
 * a claim rather than restating the label, because that is the part a clerk is deciding on.
 */
export const CAPACITY_HINTS: Record<PolicyCapacity, string> = {
  POLICYHOLDER: 'owns this contract',
  LIFE_ASSURED: 'is the insured life on this contract',
  BENEFICIARY: 'is named to receive the benefit',
};

/**
 * Every capacity in which `partyId` is connected to `policy`. Usually one; genuinely more than
 * one on a self-insured policy, where the owner and the insured life are the same person -- and
 * a policy can also name its own owner as a beneficiary. Returning a LIST rather than a single
 * "primary" capacity keeps that visible instead of picking one and quietly dropping the rest.
 *
 * Returns empty for a party with no recorded connection. That is a real case, not a bug: the
 * backend enforces no claimant-to-policy relationship whatsoever (`ClaimsApiImpl.registerClaim`
 * checks only that the claimant exists and the policy is in force), so a claim from an executor
 * or an assignee is legitimate and will have no capacity to show.
 */
export function policyCapacities(policy: PolicyView, partyId: string): PolicyCapacity[] {
  if (!partyId) return [];
  const found = new Set<PolicyCapacity>();

  if (policy.policyholderPartyId === partyId) found.add('POLICYHOLDER');
  if (policy.lifeAssuredPartyId === partyId) found.add('LIFE_ASSURED');
  // Only PARTY beneficiaries can match. A FREEFORM designee ("My Estate") is a string with no
  // party id behind it, so it can never be the claimant here even when it names the same human.
  if ((policy.beneficiaries ?? []).some((b) => b.partyId && b.partyId === partyId)) {
    found.add('BENEFICIARY');
  }

  return CAPACITY_ORDER.filter((c) => found.has(c));
}

/**
 * The statuses a claim can be registered on, as `POST /claims` rules it (2026-11-04): the policy
 * must have been on risk on the day of the event (`Policy.wasOnRiskOn`). That check is date-bounded
 * now, so a lapsed, suspended or surrendered policy still takes a claim for an event before cover
 * ended -- the old "reinstate it first" was the known gap, since closed.
 */
const CLAIMABLE = new Set(['ACTIVE', 'REINSTATED', 'PAID_UP', 'EXPIRED', 'ANNUITY_ENDED', 'LAPSED', 'SUSPENDED',
  'SURRENDERED']);
/** A scheme's claim is on a member, and the scheme itself must be in force today. */
const SCHEME_CLAIMABLE = new Set(['ACTIVE', 'REINSTATED']);
/** Cover ran and then stopped: a claim is taken only for an event before it stopped. */
const ENDED = new Set(['LAPSED', 'SUSPENDED', 'SURRENDERED', 'EXPIRED', 'ANNUITY_ENDED']);

const isScheme = (category: string | null | undefined) => category === 'GROUP_LIFE' || category === 'CREDIT_LIFE';

/** Whether a claim can be registered on a policy in this status at all. */
export function claimableStatus(status: string | null | undefined, category?: string | null): boolean {
  return (isScheme(category) ? SCHEME_CLAIMABLE : CLAIMABLE).has(status ?? '');
}

/**
 * What to tell the clerk before they choose this policy for a claim: why it will be refused, or what the date of the
 * event has to be. Null when nothing stands in the way.
 *
 * Deliberately NOT used to disable the choice in the chooser: the server's refusal says the same, and a clerk holding
 * the right contract should still be able to see it said.
 */
export function claimWarning(policy: Pick<PolicyView, 'status' | 'productCategory'>): string | null {
  const status = policy.status ?? '';
  if (isScheme(policy.productCategory)) {
    return SCHEME_CLAIMABLE.has(status) ? null : 'The scheme is not in force, so a claim on it will be refused.';
  }
  switch (status) {
    case 'PROPOSED':
      return 'This is an offer, not yet on cover — it starts when the first premium clears. A claim on it will be refused.';
    case 'NOT_TAKEN_UP':
      return 'This offer was never taken up, so it was never on cover. A claim on it will be refused.';
    case 'CANCELLED_FREE_LOOK':
      return 'Cancelled in the free-look period, so void from the start. A claim on it will be refused.';
    case 'MATURED':
      return 'This policy has matured and closed. A claim on it will be refused.';
  }
  return ENDED.has(status)
    ? `Cover has ended (${status.toLowerCase().replace(/_/g, ' ')}). A claim is accepted only for an event before it ended.`
    : null;
}
