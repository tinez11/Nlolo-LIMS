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
  member: {
    ACTIVE: 'active',
    EXITED: 'neutral', // left the employer; the row stays, because claims arrive late
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

/** Turn `SETTLEMENT_REQUESTED` into `Settlement requested` for display. */
export function humanizeStatus(value: string): string {
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
