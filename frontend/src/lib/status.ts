/**
 * The whole semantic vocabulary for the platform's status enums.
 *
 * Six buckets, deliberately distinct hues, so that a "pending" invoice and a
 * "pending" claim look alike and staff learn the colour once. The literals below
 * are transcribed from the Java enums and the OpenAPI specs -- never invented.
 * `refdata` holds only 9 numeric placeholder code sets and backs no dropdown or
 * badge anywhere on this platform, so there is no runtime source for these.
 *
 * The map is keyed by DOMAIN, not by literal, because the same literal genuinely
 * means different things: an underwriting case that is OPEN is waiting for a human,
 * while a commission statement that is OPEN is actively accruing.
 */

export type StatusBucket = 'neutral' | 'pending' | 'active' | 'success' | 'warning' | 'danger';

export const STATUS_MAPS = {
  // policy/api/PolicyStatus.java
  policy: {
    PROPOSED: 'pending',
    ACTIVE: 'active',
    REINSTATED: 'active',
    SUSPENDED: 'warning',
    LAPSED: 'danger', // coverage lost to non-payment
    MATURED: 'success', // ran its full term
    SURRENDERED: 'neutral', // closed early, but not a failure
    // An offer that expired unpaid. Neutral, not danger: nobody lost cover, because cover never
    // started -- which is the whole reason this is not LAPSED. Colouring it as a failure would
    // put it in the same visual bucket as the lapses it was deliberately kept out of.
    NOT_TAKEN_UP: 'neutral',
    // A term policy that ran its full term and paid nothing, as term cover does. Neutral, beside
    // SURRENDERED and NOT_TAKEN_UP: it is a clean, expected ending, not a failure. MATURED stays
    // success because a maturity benefit was actually paid; EXPIRED paid nothing, so it is not
    // dressed as a good outcome, only as an uneventful one.
    EXPIRED: 'neutral',
    // The customer stopped paying and keeps reduced cover. Active: it is in force, cover is running
    // -- only the premium stopped. Beside ACTIVE and REINSTATED, not a warning or a failure.
    PAID_UP: 'active',
  },

  // claims/api/ClaimStatus.java
  claim: {
    REGISTERED: 'pending',
    UNDER_ASSESSMENT: 'pending',
    SETTLEMENT_REQUESTED: 'pending',
    REOPENED: 'pending',
    APPROVED: 'success',
    SETTLED: 'success',
    REJECTED: 'danger',
  },

  // payment/api/DisbursementStatus.java -- a payout, as the claim that ordered it reads it.
  // AWAITING_EXECUTION is `warning`, not `pending`: nothing will move it but a person in
  // finance, so it is a queue somebody has to work rather than something in flight.
  disbursement: {
    PENDING: 'pending',
    AWAITING_EXECUTION: 'warning',
    IN_DOUBT: 'warning',
    COMPLETED: 'success',
    FAILED: 'danger',
  },

  // billing/api/InvoiceStatus.java
  invoice: {
    DUE: 'pending',
    PARTIALLY_PAID: 'warning',
    IN_GRACE: 'warning',
    OVERDUE: 'danger',
    PAID: 'success',
    WAIVED: 'neutral', // resolved, but not by payment
  },

  // finaccounting/api/AccountStatus.java
  account: {
    ACTIVE: 'active',
    // Retired from NEW postings; every posting already booked to it stays readable.
    // Neutral rather than danger: taking an account out of service is routine
    // housekeeping, not a failure, and nothing about the ledger is wrong.
    INACTIVE: 'neutral',
  },

  // payment/api/PaymentStatus.java + DisbursementStatus.java (the API presents them
  // as one union discriminated by `kind`).
  payment: {
    PENDING: 'pending',
    IN_DOUBT: 'warning', // outcome unknown, NOT lost
    CONFIRMED: 'success',
    COMPLETED: 'success',
    FAILED: 'danger',
  },

  // policyloan/api/LoanStatus.java
  loan: {
    RESERVED_PENDING_ORIGINATION: 'pending',
    DISBURSEMENT_REQUESTED: 'pending',
    ORIGINATED: 'active',
    DISBURSED: 'active',
    REPAYING: 'active',
    SETTLED: 'success',
    DISBURSEMENT_FAILED: 'danger',
    FORCED_LAPSE_TRIGGERED: 'danger',
  },

  // policy/api/MemberStatus.java -- a life on a group scheme.
  // policy/api/SubmissionStatus.java -- a lender's monthly file, enrolment or exits.
  //
  // PENDING is `pending` and not `neutral`, and the distinction is the whole reason this map
  // exists: a PENDING submission has changed NOTHING. Nobody is on cover, nobody is off it, and
  // a second staff user has to accept it before either happens. Dressing that as neutral would
  // let an uploaded file read as a finished one, which is the single most expensive misreading
  // available on this screen -- borrowers a lender believes are insured and are not.
  // policy/api/EnrolmentOutcome -- what one row of a lender's schedule became.
  //
  // ENROLLED_CAPPED is `warning` and NOT `success`, which is the only interesting decision in
  // this map. That borrower IS covered -- to the free cover limit -- but not for the whole loan,
  // and the excess is the lender's own credit risk. Colouring it like a clean enrolment would
  // hide the one row on the file somebody may want to talk to the lender about.
  enrolmentOutcome: {
    ENROLLED: 'success',
    ENROLLED_CAPPED: 'warning',
    REJECTED: 'danger', // this borrower is not covered
  },

  // policy/api/ExitRowView.outcome. EXITED is `neutral` rather than `success`: cover ending is
  // the file doing its job, not a good outcome for anybody in particular.
  exitOutcome: {
    EXITED: 'neutral',
    REJECTED: 'danger',
  },

  submission: {
    PENDING: 'pending',
    ACCEPTED: 'success',
    WITHDRAWN: 'neutral', // nothing happened, deliberately; the corrected file is the next one
  },

  member: {
    ACTIVE: 'active',
    EXITED: 'neutral', // left the employer; the row stays, because claims arrive late
    // Not a backend status -- the member is ACTIVE until the claim pays. The roll derives it
    // from openDeathClaimId, because "ACTIVE" alone reads as a live loan.
    DEATH_CLAIM_IN_PROGRESS: 'warning',
  },

  // policy/api/MemberUnderwritingStatus.java -- where a member stands against the
  // scheme's free cover limit.
  //
  // EVIDENCE_REQUIRED and DECLINED produce the SAME covered amount and are
  // deliberately different colours: one is a queue somebody has to work, the other
  // is finished business. Colouring them alike would hide the only difference that
  // matters. DECLINED is `warning` rather than `danger` because the member is not
  // uninsured -- they keep the free cover limit; the excess was refused.
  memberUnderwriting: {
    WITHIN_FCL: 'success', // fully covered, no evidence needed
    EVIDENCE_REQUIRED: 'pending', // over the limit, underwriting outstanding
    ACCEPTED: 'success', // excess granted
    DECLINED: 'warning', // excess refused; cover stays at the limit
  },

  // underwriting/api/UnderwritingCaseStatus.java
  underwritingCase: {
    OPEN: 'pending',
    IN_REVIEW: 'pending',
    DECIDED: 'neutral', // terminal; the sentiment lives in the decision outcome
  },

  // underwriting/api/DecisionOutcome.java
  underwritingDecision: {
    ACCEPT: 'success',
    LOADED: 'success', // an acceptance carrying a premium loading
    POSTPONED: 'warning', // a soft decline
    DECLINED: 'danger',
  },

  // underwriting/api/ReferralStatus.java
  referral: {
    NONE: 'neutral',
    REFERRED_TO_SENIOR: 'pending',
    REFERRAL_RESOLVED: 'success',
  },

  // reinsurance/api/TreatyStatus.java
  treaty: {
    ACTIVE: 'active',
    EXPIRED: 'neutral',
  },

  // party/api/KycStatus.java
  kyc: {
    PENDING: 'pending',
    VERIFIED: 'success',
    REJECTED: 'danger',
  },

  // distribution/api/StatementStatus.java
  commissionStatement: {
    OPEN: 'active', // still accruing -- the live state, unlike an OPEN uw case
    PAYOUT_REQUESTED: 'pending',
    CLOSED: 'neutral',
    PAID: 'success',
    PAYOUT_FAILED: 'danger',
  },

  // distribution/api/LicenseStatus.java
  agentLicense: {
    ACTIVE: 'active',
    SUSPENDED: 'warning', // actionable
    EXPIRED: 'danger', // cannot legally sell
  },

  // distribution/api/PlanStatus.java
  commissionPlan: {
    ACTIVE: 'active',
    RETIRED: 'neutral',
  },

  // product/api/ProductStatus.java
  product: {
    DRAFT: 'neutral',
    ACTIVE: 'active',
    RETIRED: 'neutral',
  },

  // openapi-payment.yaml PayoutBatchView.status
  payoutBatch: {
    IN_PROGRESS: 'pending',
    COMPLETED: 'success',
    PARTIAL_FAILURE: 'warning',
  },

  // openapi-billing.yaml field-receipt status (+ the internal overdue state).
  //
  // RECONCILED was missing until the reconciliation queue was built. It has been in the
  // table's CHECK constraint since the first billing migration and became reachable when
  // FieldReceipt.reconcile() was implemented, so a matched receipt was rendering through
  // StatusBadge's unrecognised-literal path -- correct behaviour for a genuinely unknown
  // literal, and the wrong answer for a known one. Nothing displayed a field receipt at all
  // until now, which is why nobody saw it.
  fieldReceipt: {
    PENDING_RECONCILIATION: 'pending',
    RECONCILED: 'success',
    RECONCILIATION_OVERDUE: 'danger',
  },

  // regreporting/domain/RegulatoryReturn.java -- a plain String field, always
  // "READY" today (generation is synchronous); GENERATING is the DB CHECK's
  // only other value, unreachable under the current synchronous design.
  regulatoryReturn: {
    GENERATING: 'pending',
    READY: 'success',
  },

  // openapi-common.yaml ProcessStatus.status
  process: {
    IN_PROGRESS: 'pending',
    COMPENSATING: 'warning',
    COMPLETED: 'success',
    FAILED: 'danger',
  },
} as const satisfies Record<string, Record<string, StatusBucket>>;

export type StatusKind = keyof typeof STATUS_MAPS;

export interface ResolvedStatus {
  bucket: StatusBucket;
  /**
   * False when the backend sent a literal this map has never heard of. The badge
   * renders it plainly rather than dressing it up as a deliberate neutral, so a
   * newly-added backend enum surfaces instead of hiding.
   */
  known: boolean;
}

/** Map a raw backend status literal onto its semantic bucket for `kind`. */
export function resolveStatus(kind: StatusKind, value: string): ResolvedStatus {
  const map: Record<string, StatusBucket> = STATUS_MAPS[kind];
  const bucket = map[value];
  return bucket ? { bucket, known: true } : { bucket: 'neutral', known: false };
}

/**
 * Acronyms a staff member reads as acronyms. "Within fcl" is what the generic lower-casing
 * made of WITHIN_FCL on the member roll -- a free cover limit is never written in lower case
 * anywhere in the business.
 *
 * Every entry is a whole token in a real literal in `types/api`, not a guess: this function
 * is applied far beyond statuses (claim types, benefit and factor types, ledger sides,
 * identity document kinds), so an invented acronym would shout a word that is not one.
 * Whole tokens only, so GLOBAL does not become "GLobal".
 */
const ACRONYMS = new Set(['FCL', 'KYC', 'EFT', 'SMS', 'ID', 'XOL', 'PAA', 'GMM', 'CR', 'DR']);

/** Turn `SETTLEMENT_REQUESTED` into `Settlement requested` for display. */
export function humanizeStatus(value: string): string {
  return value
    .split('_')
    .map((word, index) => {
      if (ACRONYMS.has(word)) return word;
      const lower = word.toLowerCase();
      return index === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(' ');
}
