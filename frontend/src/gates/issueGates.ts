import type { AgentView, PartyDetailView, ProductSnapshot } from '@/api/types';
import { formatDate, formatMonths } from '@/lib/dates';
import type { Gate } from './types';

/**
 * What must be true before a policy is issued.
 *
 * Promised by name in `frontend/PLAN.md` §13 C2 and never written; committed to
 * again in §14.3, where the decision was that a mutating surface declares its
 * preconditions before it renders a submit button. This is that.
 *
 * **Every gate here asserts something the platform can actually prove**, which is
 * the same discipline `claimGates` applies by refusing to claim
 * coverage-as-at-the-date-of-event. Nothing below infers, estimates, or asks the
 * user to confirm something the record does not hold.
 *
 * ## Hard and soft are not interchangeable
 *
 * Confirmed with the client, and the reasoning is worth keeping because it
 * decides whether staff trust the panel:
 *
 * - **Entry age and term are hard.** An age outside the product's bound cannot be
 *   priced at all — `base_rate_table` is keyed on `(age range, sex, smoker
 *   status)` and a quote already 422s with `PremiumNotQuotableException` when no
 *   cell matches. The gate invents no refusal; it surfaces one that already
 *   exists, while the underwriter can still act on it. Term is hard for a
 *   different reason: it is staff-entered configuration, and a term the product
 *   was not designed for is the wrong product, not a referral.
 * - **Sum assured is soft.** Above retention is not invalid business — it is
 *   exactly what this platform's reinsurance treaties exist to absorb. Blocking
 *   would refuse business the platform was built to cede. The breach must instead
 *   be named in the audit reason, so it is a recorded decision with an owner
 *   rather than a warning someone clicked past.
 *
 * A hard block you cannot override is a revenue risk; a soft flag with no record
 * is a compliance risk.
 */

export interface IssueGateInput {
  /** The product version being issued against, with its eligibility bounds. */
  snapshot: ProductSnapshot | null;
  /** The policyholder, for KYC. Null while the picker is still empty. */
  policyholder: PartyDetailView | null;
  /** The agent of record, when one is named. A direct sale has none, which is not a failure. */
  agent: AgentView | null;
  /** ISO date the cover starts, and what entry age is computed against. */
  commencementDate: string | null;
  policyTermMonths: number | null;
  sumAssured: number | null;
  /** ISO date; the entry-age calculation needs a reference point that is not "now" in a test. */
  today: string;
}

/**
 * Whole years between two ISO dates.
 *
 * Deliberately not a millisecond division: age is a calendar fact, and
 * `(now - dob) / 365.25` disagrees with the backend on leap years and on the
 * applicant's birthday itself. This mirrors `ProductApiImpl`'s
 * `Period.between(dateOfBirth, asOf).getYears()`.
 */
export function ageOn(dateOfBirth: string, asOf: string): number | null {
  const dob = /^(\d{4})-(\d{2})-(\d{2})/.exec(dateOfBirth);
  const ref = /^(\d{4})-(\d{2})-(\d{2})/.exec(asOf);
  if (!dob || !ref) return null;

  let years = Number(ref[1]) - Number(dob[1]);
  const beforeBirthday =
    Number(ref[2]) < Number(dob[2]) ||
    (Number(ref[2]) === Number(dob[2]) && Number(ref[3]) < Number(dob[3]));
  if (beforeBirthday) years -= 1;
  return years;
}

export function issueGates({
  snapshot,
  policyholder,
  agent,
  commencementDate,
  policyTermMonths,
  sumAssured,
  today,
}: IssueGateInput): Gate[] {
  const gates: Gate[] = [];
  const bounds = snapshot?.eligibility;

  // ---- Entry age (HARD) ---------------------------------------------------
  // Computed against the commencement date, not today: entry age is the age at
  // which cover starts, and a proposal commencing next quarter can cross a
  // birthday before the risk begins.
  const asOf = commencementDate ?? today;
  if (policyholder?.dateOfBirth && bounds && (bounds.minEntryAge != null || bounds.maxEntryAge != null)) {
    const age = ageOn(policyholder.dateOfBirth, asOf);
    if (age !== null) {
      const belowMin = bounds.minEntryAge != null && age < bounds.minEntryAge;
      const aboveMax = bounds.maxEntryAge != null && age > bounds.maxEntryAge;
      const range = describeRange(bounds.minEntryAge, bounds.maxEntryAge, (n) => `${n}`);
      gates.push({
        ok: !belowMin && !aboveMax,
        hard: true,
        title: 'The applicant is within the product’s entry age',
        detail:
          belowMin || aboveMax
            ? `They are ${age} at commencement, and this product accepts ${range}. There is no rate for that age, so no premium can be calculated.`
            : `They are ${age} at commencement (${formatDate(asOf)}), within ${range}`,
      });
    }
  }

  // ---- Term (HARD) --------------------------------------------------------
  if (policyTermMonths != null && bounds && (bounds.minTermMonths != null || bounds.maxTermMonths != null)) {
    const belowMin = bounds.minTermMonths != null && policyTermMonths < bounds.minTermMonths;
    const aboveMax = bounds.maxTermMonths != null && policyTermMonths > bounds.maxTermMonths;
    const range = describeRange(bounds.minTermMonths, bounds.maxTermMonths, formatMonths);
    gates.push({
      ok: !belowMin && !aboveMax,
      hard: true,
      title: 'The term is one this product offers',
      detail:
        belowMin || aboveMax
          ? `${formatMonths(policyTermMonths)} is outside ${range}. A term this product was not designed for needs a new product version, not an override.`
          : `${formatMonths(policyTermMonths)}, within ${range}`,
    });
  }

  // ---- Sum assured (SOFT) -------------------------------------------------
  if (sumAssured != null && bounds && (bounds.minSumAssured != null || bounds.maxSumAssured != null)) {
    const belowMin = bounds.minSumAssured != null && sumAssured < bounds.minSumAssured;
    const aboveMax = bounds.maxSumAssured != null && sumAssured > bounds.maxSumAssured;
    const range = describeRange(bounds.minSumAssured, bounds.maxSumAssured, (n) => n.toLocaleString());
    gates.push({
      ok: !belowMin && !aboveMax,
      // Soft on purpose. Above retention is cedeable business, not invalid
      // business, and refusing it here would turn the console into the reason
      // the insurer declined a large case.
      hard: false,
      title: 'The sum assured is within the product’s normal range',
      detail: aboveMax
        ? `${sumAssured.toLocaleString()} is above ${range}. That is not a refusal — it is reinsurance territory — but say why in the reason for issue, because it is the only place this decision gets recorded.`
        : belowMin
          ? `${sumAssured.toLocaleString()} is below ${range}, which may not be worth writing. Say why in the reason for issue if you proceed.`
          : `${sumAssured.toLocaleString()}, within ${range}`,
    });
  }

  // ---- Policyholder KYC (SOFT) --------------------------------------------
  // Soft deliberately: no backend @PreAuthorize or domain rule refuses issuance
  // to a PENDING party, so a hard gate here would be the UI inventing a refusal
  // the platform does not make. Flagging it is honest; blocking would not be.
  if (policyholder?.kycStatus) {
    const verified = policyholder.kycStatus === 'VERIFIED';
    gates.push({
      ok: verified,
      hard: false,
      title: 'The policyholder’s identity is verified',
      detail: verified
        ? 'KYC verified'
        : policyholder.kycStatus === 'REJECTED'
          ? 'KYC was REJECTED for this client. Issuing anyway needs a stated reason.'
          : 'KYC is still pending. The platform does not refuse issuance on this, but the record will show a policy written before the identity was confirmed.',
    });
  }

  // ---- Agent licence (HARD when an agent is named) ------------------------
  // A direct sale names no agent, which is not a failure and produces no gate.
  if (agent) {
    const active = agent.licenseStatus === 'ACTIVE';
    gates.push({
      ok: active,
      hard: true,
      title: 'The agent of record holds an active licence',
      detail: active
        ? `Licence ${agent.licenseNumber ?? ''} is active`.trim()
        : `Their licence is ${String(agent.licenseStatus).toLowerCase()}. Writing business through it is a regulatory problem, not a paperwork one.`,
    });

    if (agent.licenseExpiryDate) {
      // Checked against commencement, not today: a licence valid now but expired
      // by the date cover starts has not licensed this sale.
      const validAtCommencement = agent.licenseExpiryDate >= asOf;
      gates.push({
        ok: validAtCommencement,
        hard: true,
        title: 'That licence is still valid when cover starts',
        detail: validAtCommencement
          ? `Expires ${formatDate(agent.licenseExpiryDate)}`
          : `It expires ${formatDate(agent.licenseExpiryDate)}, before cover starts on ${formatDate(asOf)}`,
      });
    }
  }

  return gates;
}

/** "18 to 65", "at least 18", or "up to 65" — whichever bounds are actually set. */
function describeRange<T extends number>(
  min: T | null | undefined,
  max: T | null | undefined,
  render: (value: T) => string,
): string {
  if (min != null && max != null) return `${render(min)} to ${render(max)}`;
  if (min != null) return `at least ${render(min)}`;
  if (max != null) return `up to ${render(max)}`;
  return 'any value';
}

/**
 * Whether the reason-for-issue field must explain something.
 *
 * A soft gate that fails is a decision somebody is making, and the only place
 * this platform records it is `reasonForManualIssue`. Requiring the reason to be
 * non-trivial when a soft gate fails is what stops the panel becoming a warning
 * staff click past — the difference between a flagged decision with an owner and
 * a silent one.
 */
export function softBreaches(gates: Gate[]): Gate[] {
  return gates.filter((gate) => !gate.ok && !gate.hard);
}

/** A hard gate that fails means the platform will refuse the issue. */
export function hasHardFailure(gates: Gate[]): boolean {
  return gates.some((gate) => !gate.ok && gate.hard);
}
