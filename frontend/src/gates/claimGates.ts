import type { ClaimType, CoverageStatusView, PolicyView } from '@/api/types';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import type { Gate } from './types';

/**
 * What can honestly be said about cover at claim intake.
 *
 * The question that matters is "was this policy providing the claimed cover on
 * the day the event happened". **This platform cannot answer it**, and the gates
 * below are shaped around that fact rather than around the endpoint that looks
 * like it should.
 *
 * `GET /policies/{n}/coverage-status?asOf=` accepts the date, echoes it back in
 * the response, and ignores it: `PolicyApiImpl.getCoverageStatus` queries
 * `findByPolicyNumberAndActiveTrue`, a boolean flag with no date bound. Verified
 * three ways — two dates 25 years apart return byte-identical coverage on a
 * policy issued in 2026; the source computes `effectiveAsOf` only to put it in
 * the response; and `policy.coverage` has no `effective_from`/`effective_to`
 * columns at all, so no per-benefit history exists to query. (A comment on
 * `isPolicyInForce` excuses its own ignored `asOf` by delegating date-bounded
 * work to `getCoverageStatus`. That delegation is false.)
 *
 * So these gates use only facts that are real:
 *
 * 1. `issueDate` from `PolicyView` — a date of event before it means risk had
 *    not commenced. Hard, and genuinely date-bounded.
 * 2. Today's `status` — not a coverage-on-the-day answer, but an honest flag: if
 *    the policy is not currently in force, whether cover was running on the day
 *    needs a person, because no lapse date is recorded anywhere. Soft, because a
 *    lapsed policy must route a claim to investigation, never auto-refuse it —
 *    refusing without verifying the lapse notices were provably sent is how an
 *    insurer ends up in front of a regulator.
 * 3. The benefit set from `coverage-status` — date-inert, so it answers "does
 *    this policy carry this benefit", and is labelled that way rather than as
 *    "carried it on the day".
 *
 * Closing gap 2 properly needs coverage dates in the backend; it is recorded in
 * the M13 design spec's open items.
 */
export interface ClaimGateInput {
  policy: PolicyView | null;
  /** Benefit set for the policy. Current, NOT as at the date of event. */
  coverage: CoverageStatusView | null;
  claimType: ClaimType | null;
  dateOfEvent: string | null;
}

/** ISO dates compare correctly as strings, so no parsing and no timezone to get wrong. */
function isOnOrAfter(date: string, bound: string): boolean {
  return date >= bound;
}

export function claimGates({ policy, coverage, claimType, dateOfEvent }: ClaimGateInput): Gate[] {
  if (!dateOfEvent) return [];
  const gates: Gate[] = [];
  const onTheDay = formatDate(dateOfEvent);

  if (policy?.issueDate) {
    const commenced = isOnOrAfter(dateOfEvent, policy.issueDate);
    gates.push({
      ok: commenced,
      hard: true,
      title: 'Risk had commenced by the date of event',
      detail: commenced
        ? `Cover began ${formatDate(policy.issueDate)}`
        : `The policy was not issued until ${formatDate(policy.issueDate)}, after ${onTheDay} — there was no contract on the day`,
    });
  }

  if (policy?.status) {
    // A MATURED policy claiming MATURITY is the normal, expected path, not a flag.
    const expected =
      policy.status === 'ACTIVE' ||
      policy.status === 'REINSTATED' ||
      (policy.status === 'MATURED' && claimType === 'MATURITY');
    gates.push({
      ok: expected,
      hard: false,
      title: 'Coverage on the day can be read from the record',
      detail: expected
        ? `The policy is ${policy.status.toLowerCase()} and no coverage history is in question`
        : `The policy is ${policy.status.toLowerCase()} today, and the platform records no date for that change — whether cover was running on ${onTheDay} must be verified by a person. This flags the claim for investigation; it does not refuse it.`,
    });
  }

  if (coverage && claimType) {
    // Every field generates optional: the spec declares no `required` list on
    // CoverageStatusView, the same gap PLAN.md §6 records for PageMeta.
    const active = (coverage.activeCoverages ?? []).filter((c) => c.benefitType);
    const matched = active.find((c) => c.benefitType === claimType);
    const readable = claimType.replace(/_/g, ' ').toLowerCase();
    gates.push({
      ok: !!matched,
      hard: true,
      title: `The policy carries ${readable} cover`,
      detail: matched
        ? `${formatMoney(matched.sumAssured)} on the current record`
        : active.length > 0
          ? `The policy carries ${active.map((c) => c.benefitType).join(', ')} — not ${claimType}`
          : 'No benefit of any kind is on record for this policy',
    });
  }

  return gates;
}
