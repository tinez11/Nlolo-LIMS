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
 * The statuses `POST /claims` will actually accept today.
 *
 * Transcribed from `PolicyApiImpl.isPolicyInForce`, which is a plain "is this policy currently
 * ACTIVE-or-REINSTATED" status read -- it accepts an `asOf` date and, by its own comment, never
 * consults it. So a claim for an event that happened while cover was running is still refused
 * if the policy has lapsed since, which is the wrong answer and a known, recorded gap.
 */
const IN_FORCE_STATUSES = new Set(['ACTIVE', 'REINSTATED']);

/**
 * Whether registration will be accepted for this policy right now.
 *
 * Deliberately NOT used to disable the choice. `claimGates` makes the same fact a SOFT gate on
 * purpose, and its reasoning governs here too: a lapsed policy must route a claim to
 * investigation, never auto-refuse it, because refusing without proving the lapse notices were
 * sent is how an insurer ends up in front of a regulator. Hiding or disabling the policy in the
 * chooser would be that refusal, made earlier and more quietly.
 *
 * What it IS for: warning honestly before the click, so a 422 is expected rather than
 * mysterious, and naming the remedy (reinstate first) that `ClaimsApiImpl` says staff need.
 */
export function willAcceptClaimToday(policy: PolicyView): boolean {
  return !!policy.status && IN_FORCE_STATUSES.has(policy.status);
}
