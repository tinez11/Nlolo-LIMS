import type { ClaimType, CoverageStatusView, PolicyView } from '@/api/types';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import type { Gate } from './types';

/**
 * What can honestly be said about cover at claim intake.
 *
 * The question that matters is "was this policy providing the claimed cover on
 * the day the event happened". The backend now answers the date-bounded part of
 * it — `GET /policies/{n}/in-force?asOf=` calls `Policy.wasOnRiskOn`, which reads
 * the commencement, the maturity date and the lapse/suspension dates — and
 * refuses a claim registered for a day the policy was not on risk. So the whole
 * judgement lives on the server, and the submit surfaces it.
 *
 * These gates prove, ahead of that submit, the parts a `PolicyView` carries and
 * nothing more:
 *
 * 1. `issueDate` — a date of event before it means risk had not commenced. Hard,
 *    genuinely date-bounded.
 * 2. `maturityDate` — a term policy's cover ends as its maturity date begins, so
 *    a non-maturity event on or after it fell outside the term. Hard. And a
 *    MATURITY claim is only claimable once that date is reached, on a policy that
 *    has one at all. Hard.
 * 3. The benefit set from `coverage-status` — date-inert, so it answers "does
 *    this policy carry this benefit", labelled as current rather than as-at-the-
 *    day. (`coverage-status` still ignores its `asOf`: `policy.coverage` has no
 *    effective dates, so no per-benefit history exists to query. Only `in-force`
 *    became date-aware.)
 * 4. A **soft** note when today's status is not plainly in force: the lapse and
 *    suspension dates are not on `PolicyView`, so whether cover was running on the
 *    day is the server's call at submit, not one this gate can make. It flags the
 *    claim for that check; it never refuses it, because a lapsed policy must route
 *    to investigation, never auto-refuse.
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

  // The maturity window, from the one date PolicyView carries for it.
  if (policy?.maturityDate) {
    if (claimType === 'MATURITY') {
      const reached = isOnOrAfter(dateOfEvent, policy.maturityDate);
      gates.push({
        ok: reached,
        hard: true,
        title: 'The policy has reached maturity',
        detail: reached
          ? `Matured ${formatDate(policy.maturityDate)}`
          : `The policy does not mature until ${formatDate(policy.maturityDate)}, after ${onTheDay} — there is nothing to mature yet`,
      });
    } else {
      const withinTerm = !isOnOrAfter(dateOfEvent, policy.maturityDate);
      gates.push({
        ok: withinTerm,
        hard: true,
        title: 'The event falls within the policy term',
        detail: withinTerm
          ? `Cover runs to ${formatDate(policy.maturityDate)}`
          : `The term ended ${formatDate(policy.maturityDate)}, on or before ${onTheDay} — cover had already run out`,
      });
    }
  } else if (claimType === 'MATURITY') {
    // No maturity date means no maturity benefit; the server refuses this outright.
    gates.push({
      ok: false,
      hard: true,
      title: 'The policy has reached maturity',
      detail: 'This policy carries no maturity date, so there is no maturity benefit to claim',
    });
  }

  if (policy?.status) {
    // Plainly in force today needs no flag. Otherwise the lapse/suspension date — which
    // PolicyView does not carry — decides whether cover was running on the day, and that is the
    // server's call at submit (Policy.wasOnRiskOn). Soft: it routes the claim to that check, it
    // does not pre-empt it. MATURED/EXPIRED are handled by the term gate above, so they are not
    // re-flagged here.
    const plainlyInForce =
      policy.status === 'ACTIVE' ||
      policy.status === 'REINSTATED' ||
      policy.status === 'MATURED' ||
      policy.status === 'EXPIRED';
    if (!plainlyInForce) {
      gates.push({
        ok: false,
        hard: false,
        title: 'Cover on the day is confirmed on submit',
        detail: `The policy is ${policy.status.toLowerCase()} today. Whether it was on risk on ${onTheDay} is checked against its lapse and suspension dates when the claim is registered; this flags it for that check and does not refuse it.`,
      });
    }
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
