/**
 * Narrow re-exports of the generated OpenAPI types.
 *
 * Application code imports from here rather than reaching into
 * `src/types/api/<module>` directly, so the module-per-spec layout of codegen stays
 * an implementation detail and a spec reshuffle does not ripple through screens.
 *
 * Note that almost every field on these views is OPTIONAL. That is not laziness in
 * the codegen: the specs declare no `required` list on PolicyView, InvoiceView or
 * LoanView, so the generated types are an accurate reflection of what the contract
 * actually promises. Screens must defend on each field.
 */
import type { components as AuditComponents } from '@/types/api/audit';
import type { components as BenefitPayoutComponents } from '@/types/api/benefitpayout';
import type { components as AccumulationComponents } from '@/types/api/accumulation';
import type { components as BillingComponents } from '@/types/api/billing';
import type { components as ClaimsComponents } from '@/types/api/claims';
import type { components as CommonComponents } from '@/types/api/common';
import type { components as CommunicationComponents } from '@/types/api/communication';
import type { components as PartyComponents } from '@/types/api/party';
import type { components as PaymentComponents } from '@/types/api/payment';
import type { components as PolicyComponents, paths as PolicyPaths } from '@/types/api/policy';
import type { components as DistributionComponents } from '@/types/api/distribution';
import type { components as FinaccountingComponents } from '@/types/api/finaccounting';
import type { components as PolicyLoanComponents } from '@/types/api/policyloan';
import type { components as ProductComponents } from '@/types/api/product';
import type { components as RegreportingComponents } from '@/types/api/regreporting';
import type { components as ReinsuranceComponents } from '@/types/api/reinsurance';
import type { components as UnderwritingComponents } from '@/types/api/underwriting';

export type PolicyView = PolicyComponents['schemas']['PolicyView'];
export type PolicyStatus = NonNullable<PolicyView['status']>;
/** A customer surrender in flight (product step 1). */
export type SurrenderRequestView = PolicyComponents['schemas']['SurrenderRequest'];
/** What a surrender would pay today -- a quote, not a commitment. */
export type SurrenderQuote = NonNullable<
  PolicyPaths['/policies/{policyNumber}/surrender-value']['get']['responses']['200']['content']['application/json']
>;
export type BeneficiaryInput = PolicyComponents['schemas']['BeneficiaryInput'];
/** The reverse direction: a policy that names some party as beneficiary. */
export type BeneficiaryOfView = PolicyComponents['schemas']['BeneficiaryOfView'];
export type CoverageStatusView = PolicyComponents['schemas']['CoverageStatusView'];

/**
 * Group business: one master policy, many insured lives.
 *
 * A `GroupSchemeView` is the same contract a `PolicyView` describes, read as a
 * scheme instead of as a policy. Both are fetched for the scheme page — the
 * policy read answers premium and lifecycle, the scheme read answers who is
 * covered and for how much.
 */
export type GroupSchemeView = PolicyComponents['schemas']['GroupSchemeView'];
export type BenefitBasis = NonNullable<GroupSchemeView['benefitBasis']>;
export type GroupSchemeGrade = PolicyComponents['schemas']['GroupSchemeGrade'];
export type PolicyMemberView = PolicyComponents['schemas']['PolicyMemberView'];

/**
 * A lender's monthly files. The two submission views are structurally identical and differ in
 * exactly one field -- `enrolledCount` where the other has `exitedCount` -- which is what lets one
 * panel render both. They mean opposite things, so nothing above that panel should merge them.
 */
export type EnrolmentSubmissionView = PolicyComponents['schemas']['EnrolmentSubmissionView'];
export type EnrolmentRowView = PolicyComponents['schemas']['EnrolmentRowView'];
export type ExitSubmissionView = PolicyComponents['schemas']['ExitSubmissionView'];
export type ExitRowView = PolicyComponents['schemas']['ExitRowView'];
/** PENDING / ACCEPTED / WITHDRAWN. PENDING is the one that matters: it means NOTHING has happened yet. */
export type SubmissionStatus = NonNullable<EnrolmentSubmissionView['status']>;
export type MemberStatus = NonNullable<PolicyMemberView['status']>;

/**
 * One bank transfer a finance officer has to go and make.
 *
 * The console's first reach into the payment spec at all, and it exists because the EFT rail has
 * no callback: a mobile-money payout completes itself when the aggregator calls back, while a
 * credit-life claim's millions go by bank transfer and complete only when a person records that
 * they moved the money.
 */
export type AwaitingEftView = PaymentComponents['schemas']['AwaitingEftView'];
export type DisbursementView = PaymentComponents['schemas']['DisbursementView'];
/**
 * Four states, not a boolean. `EVIDENCE_REQUIRED` and `DECLINED` produce the same
 * covered amount and mean opposite things about whether anyone is still waiting.
 */
export type MemberUnderwritingStatus = NonNullable<PolicyMemberView['underwritingStatus']>;
export type IssueGroupSchemeRequest = PolicyComponents['schemas']['IssueGroupSchemeRequest'];
export type GroupSchemeGradeInput = PolicyComponents['schemas']['GroupSchemeGradeInput'];
export type GroupMemberInput = PolicyComponents['schemas']['GroupMemberInput'];

export type InvoiceView = BillingComponents['schemas']['InvoiceView'];
export type PremiumCreditView = BillingComponents['schemas']['PremiumCreditView'];
/**
 * One row of the collections queue. Its money and due date come from the invoice the case was
 * opened against, and are null -- never zero -- when that invoice cannot be resolved.
 */
export type ArrearsCaseView = BillingComponents['schemas']['ArrearsCaseView'];
/**
 * One agent-captured premium receipt. Both timestamps are carried on purpose: the gap between
 * capturedAtClient and capturedAtServer separates "we were slow" from "the field was offline".
 */
export type FieldReceiptView = BillingComponents['schemas']['FieldReceiptView'];
export type FieldReceiptStatus = NonNullable<FieldReceiptView['status']>;
/** Order taken from the table's own CHECK constraint, not invented. */
export const FIELD_RECEIPT_STATUSES: readonly FieldReceiptStatus[] = [
  'PENDING_RECONCILIATION',
  'RECONCILIATION_OVERDUE',
  'RECONCILED',
];
export type LoanView = PolicyLoanComponents['schemas']['LoanView'];

export type PageMeta = CommonComponents['schemas']['PageMeta'];
export type Money = CommonComponents['schemas']['Money'];

export type ClaimView = ClaimsComponents['schemas']['ClaimView'];
export type ClaimStatus = ClaimView['status'];
export type ClaimType = ClaimView['claimType'];
export type ClaimDetails = ClaimsComponents['schemas']['ClaimDetails'];
export type RegisterClaimRequest = ClaimsComponents['schemas']['RegisterClaimRequest'];

export type ClaimAssessmentView = ClaimsComponents['schemas']['ClaimAssessmentView'];
export type ClaimCoverView = ClaimsComponents['schemas']['ClaimCoverView'];
export type SubmitClaimAssessmentRequest = ClaimsComponents['schemas']['SubmitClaimAssessmentRequest'];

/**
 * `POST /claims/{claimId}/evidence` and `GET .../evidence` -- built since M6/M11
 * but with zero staff UI until this staff-portal CRUD audit found the gap. The
 * download endpoint (`GET .../evidence/{documentRef}`) returns raw bytes with a
 * real Content-Type from a closed 4-value set (image/jpeg, image/png,
 * application/pdf, application/octet-stream) -- there is no metadata field for
 * it on ClaimEvidenceView itself, so the frontend reads it off the actual
 * download response rather than a separate lookup.
 */
export type ClaimEvidenceView = ClaimsComponents['schemas']['ClaimEvidenceView'];

/**
 * `POST /claims/{claimId}/settlement-decision` and `.../reopen` both declare
 * their request bodies INLINE in the spec (no named schema), so codegen never
 * produced a type for either -- hand-written here, transcribed from the actual
 * generated path entry in `types/api/claims.ts`, not guessed.
 */
export interface SettlementDecisionRequest {
  approved: boolean;
  approvedAmount?: Money;
  rejectionReason?: string | null;
  payeeRef?: string | null;
}
export interface ReopenClaimRequest {
  reason: string;
}

/**
 * `POST /invoices/{invoiceId}/waiver` and `.../payment-request` both declare
 * their request bodies INLINE in the spec (no named schema, same shape as
 * claims' settlement-decision/reopen) -- hand-written here, transcribed from
 * the actual generated path entry in `types/api/billing.ts`.
 */
export interface WaiverRequest {
  reason: string;
}
export interface PaymentRequest {
  payerRef: string;
}

/**
 * `POST /policies/{n}/loans` and `POST /loans/{loanId}/repayments` declare their
 * bodies inline too -- transcribed from `OriginateLoanRequestDto` and
 * `RepaymentRequestDto`. Both accept an `Idempotency-Key` but do not enforce it
 * (see `lib/idempotency.ts`'s own note and its assertion for these two paths),
 * so neither client call mints an attempt.
 */
export interface OriginateLoanRequest {
  requestedAmount: Money;
  /** Mobile-money / bank destination the disbursement is paid to. */
  payeeRef: string;
}
export interface LoanRepaymentRequest {
  amount: Money;
  paymentReference: string;
}

/**
 * `agents`/`commission-plans` -- there is no `GET /agents` list or search
 * endpoint at all (matches parties/payments/documents' shape), and no
 * `GET /commission-plans/{id}` either: a plan is readable only through an
 * agent's applicable-plan lookup (agent + product), never by its own id.
 */
export type AgentView = DistributionComponents['schemas']['AgentView'];
export type LicenseStatus = NonNullable<AgentView['licenseStatus']>;
/** Order taken from the enum in openapi-distribution.yaml, not invented. */
export const LICENSE_STATUSES: readonly LicenseStatus[] = ['ACTIVE', 'EXPIRED', 'SUSPENDED'];

/**
 * One recorded domain event. Note what is ABSENT: no actor, no before/after, no
 * reason — `audit.audit_log` has no such columns. This is an event journal, not
 * a who-did-what trail, and anything rendering it must say so.
 */
export type AuditEntryView = AuditComponents['schemas']['AuditEntryView'];

/** What this platform says to customers, and the record of having said it. */
export type NotificationTemplateView = CommunicationComponents['schemas']['NotificationTemplateView'];
export type NotificationDispatchView = CommunicationComponents['schemas']['NotificationDispatchView'];
export type NotificationStatus = NonNullable<NotificationDispatchView['status']>;

/**
 * The four the outbox can show. PENDING and CLAIMED are a queued reminder mid-flight; SENT means
 * accepted by the transport, never delivered or read.
 */
export const NOTIFICATION_STATUSES: readonly NotificationStatus[] = ['PENDING', 'CLAIMED', 'SENT', 'FAILED'];
export type OnboardAgentRequest = DistributionComponents['schemas']['OnboardAgentRequest'];

export type CommissionPlanView = DistributionComponents['schemas']['CommissionPlanView'];
export type PlanStatus = NonNullable<CommissionPlanView['status']>;
export type CreateCommissionPlanRequest = DistributionComponents['schemas']['CreateCommissionPlanRequest'];
export type CommissionRuleInput = DistributionComponents['schemas']['CommissionRuleInput'];
export type CommissionRuleView = DistributionComponents['schemas']['CommissionRuleView'];
export type TierType = DistributionComponents['schemas']['TierType'];

export type CommissionStatementView = DistributionComponents['schemas']['CommissionStatementView'];
export type StatementStatus = NonNullable<CommissionStatementView['status']>;
export type CommissionAccrualView = DistributionComponents['schemas']['CommissionAccrualView'];
export type RequestPayoutRequest = DistributionComponents['schemas']['RequestPayoutRequest'];

/**
 * `GET /treaties` is a real list endpoint (unlike underwriting cases and
 * agents) -- `TreatiesPage` gets the same drawer-previews-page-acts shape as
 * Policies/Claims/Products, not the create-only exception.
 */
export type TreatyView = ReinsuranceComponents['schemas']['TreatyView'];
export type TreatyType = ReinsuranceComponents['schemas']['TreatyType'];
export type TreatyStatus = ReinsuranceComponents['schemas']['TreatyStatus'];
export type CreateTreatyRequest = ReinsuranceComponents['schemas']['CreateTreatyRequest'];
export type CessionView = ReinsuranceComponents['schemas']['CessionView'];
/** A treaty's own totals. Count plus two money figures, never a percentage -- see the endpoint. */
export type TreatyUtilisationView = ReinsuranceComponents['schemas']['TreatyUtilisationView'];
export type ClaimRecoveryView = ReinsuranceComponents['schemas']['ClaimRecoveryView'];

export const TREATY_TYPES: readonly TreatyType[] = ['QUOTA_SHARE', 'SURPLUS', 'XOL'];
export const TREATY_STATUSES: readonly TreatyStatus[] = ['ACTIVE', 'EXPIRED'];

/**
 * `finaccounting` is read-only, permanently: every posting is derived from a
 * domain event by this module's own listeners, and there is no write
 * endpoint here at all, by design -- a correction is a future reversal entry
 * (deferred), never an edit to an existing one.
 */
export type JournalEntryView = FinaccountingComponents['schemas']['JournalEntryView'];
export type GlPostingView = FinaccountingComponents['schemas']['GlPostingView'];
export type ChartOfAccountView = FinaccountingComponents['schemas']['ChartOfAccountView'];
export type AccountType = ChartOfAccountView['accountType'];
export type PostingDirection = GlPostingView['direction'];
/** The chart WITH its balances. Structurally a superset of ChartOfAccountView minus the
 *  audit columns, plus the money — kept separate because the two endpoints answer different
 *  questions and only one of them aggregates the posting table. */
export type TrialBalanceView = FinaccountingComponents['schemas']['TrialBalanceView'];
export type AccountBalanceView = FinaccountingComponents['schemas']['AccountBalanceView'];

/**
 * The chart of accounts is NOT read-only (added on explicit request, after the
 * module first shipped read-only) -- `accountType`/`normalBalance` stay
 * derived server-side from `accountCode`'s own leading digit and are
 * deliberately absent from both request shapes below.
 */
export type CreateAccountRequest = FinaccountingComponents['schemas']['CreateAccountRequest'];
export type UpdateAccountRequest = FinaccountingComponents['schemas']['UpdateAccountRequest'];
export type AccountStatus = FinaccountingComponents['schemas']['AccountStatus'];

/**
 * `ClaimDetails` has no `discriminator` keyword in the spec (deliberately -- see the
 * generated type's own comment: any discriminator+oneOf shape fails
 * swagger-request-validator's syntax check before a single request is evaluated).
 * It discriminates correctly anyway: every subtype's `claimType` is a literal enum
 * of exactly one value, so a plain `switch (details.claimType)` narrows the union
 * natively -- no manual type guard needed.
 */
export const CLAIM_TYPES: readonly ClaimType[] = [
  'DEATH',
  'DISABILITY',
  'CRITICAL_ILLNESS',
  'MATURITY',
];

export type ProductSummary = ProductComponents['schemas']['ProductSummary'];
export type ProductSnapshot = ProductComponents['schemas']['ProductSnapshot'];
export type ProductStatus = NonNullable<ProductSummary['status']>;
export type ProductCategory = NonNullable<ProductSummary['category']>;
export type CreateProductRequest = ProductComponents['schemas']['CreateProductRequest'];
export type ProductVersionSpec = ProductComponents['schemas']['ProductVersionSpec'];
export type RatingFactorType = NonNullable<
  NonNullable<ProductVersionSpec['ratingTable'][number]['factorType']>
>;
export type BenefitScheduleType = NonNullable<
  NonNullable<ProductVersionSpec['benefitSchedule'][number]['benefitType']>
>;
/** How a benefit's amount is worked out from the policy's sum assured. */
export type BenefitCalculationMethod = NonNullable<
  NonNullable<ProductVersionSpec['benefitSchedule'][number]['calculationMethod']>
>;
export type IfrsMeasurementModel = NonNullable<ProductVersionSpec['ifrsMeasurementModel']>;
export type VersionRatingView = ProductComponents['schemas']['VersionRatingView'];
export type BaseRate = ProductComponents['schemas']['BaseRate'];
export const IFRS_MEASUREMENT_MODELS: readonly IfrsMeasurementModel[] = ['GMM', 'PAA'];

export type ManualIssueRequest = PolicyComponents['schemas']['ManualIssueRequest'];

/**
 * `POST /policies/{n}/suspend` (staff only) -- suspend/resume/reinstate had real,
 * tested domain logic since M3 but no HTTP endpoint at all until this staff-portal
 * CRUD audit found the gap. `resume`/`reinstate` take no request body.
 */
export type SuspendPolicyRequest = PolicyComponents['schemas']['SuspendPolicyRequest'];

/**
 * `GET /parties/{partyId}` has always existed; `POST .../kyc-evidence` (upload) and
 * `POST .../kyc` (decide) did not, until this staff-portal CRUD audit found the
 * gap -- `PartyApi.submitKycEvidence` has always required a real
 * `evidenceDocumentRef`, but there was no upload path anywhere on the platform
 * that could produce one for a KYC purpose. `PartyView`'s fields are all optional
 * on the wire (the spec declares no `required` list).
 */
export type PartyView = PartyComponents['schemas']['PartyView'];
/** The full record from `GET /parties/{id}`. Deliberately not what the list returns. */
export type PartyDetailView = PartyComponents['schemas']['PartyDetailView'];
/** Always present on a PartyDetailView; an unrecorded document is two nulls. */
export type IdentityDocumentView = PartyComponents['schemas']['IdentityDocumentView'];
export type PartyDocumentView = PartyComponents['schemas']['PartyDocumentView'];
export type KycStatus = NonNullable<PartyView['kycStatus']>;
export const KYC_STATUSES: readonly KycStatus[] = ['PENDING', 'VERIFIED', 'REJECTED'];
/**
 * What KIND of client a party is, and the dimension the register is split on. Three
 * values, not two: `GROUP` is its own type, distinct from `CORPORATE`, even though no
 * endpoint can create one yet (`registerGroup` exists in `PartyApi` with no HTTP path).
 */
export type PartyType = NonNullable<PartyView['partyType']>;
export type KycEvidenceUploadResponse = PartyComponents['schemas']['KycEvidenceUploadResponse'];

/**
 * `POST /parties/individuals` / `POST /parties/corporates` -- both existed and were already
 * agent-scoped server-side (individual: self-service or agent-assisted; corporate: agent or
 * staff) before any frontend called either. Staff-assisted individual registration was added
 * alongside this frontend work, closing an asymmetry with corporates that had no documented
 * rationale (staff portal review, 2026-08-25).
 */
export type RegisterIndividualRequest = PartyComponents['schemas']['RegisterIndividualRequest'];
export type RegisterCorporateRequest = PartyComponents['schemas']['RegisterCorporateRequest'];

/**
 * Correcting a company, which is NOT RegisterCorporateRequest: there is no registrationNumber.
 * That number is the company's identity in the national register, and an edit form must not be
 * a route to becoming a different company.
 */
export type AmendCorporateRequest = PartyComponents['schemas']['AmendCorporateRequest'];

/**
 * `POST /regulatory-returns` (generate) + `GET` (list/get) -- fully built and
 * staff-reachable (FINANCE_OFFICER/ADMIN) since M10, but with zero staff UI
 * until this staff-portal CRUD audit found the gap. `returnType` is
 * deliberately not a closed enum -- the return catalog is DATA (seeded
 * `return_definition` rows), not code, so there is no dropdown to back it
 * with; staff type the code they know is seeded (`QUARTERLY_PRUDENTIAL`
 * today).
 */
export type RegulatoryReturnView = RegreportingComponents['schemas']['RegulatoryReturnView'];
export type ReturnLineView = RegreportingComponents['schemas']['ReturnLineView'];
export type GenerateReturnRequest = RegreportingComponents['schemas']['GenerateReturnRequest'];

export const PRODUCT_CATEGORIES: readonly ProductCategory[] = [
  'TERM_LIFE',
  'ENDOWMENT',
  'WHOLE_LIFE',
  'ANNUITY',
  'UNIT_LINKED',
  'GROUP_LIFE',
  'EDUCATION_SAVINGS',
  'CREDIT_LIFE',
];

/**
 * Whether a product insures one life and so may be proposed or issued as a single policy.
 * GROUP_LIFE and CREDIT_LIFE insure a schedule of members and are set up as schemes; the
 * server refuses them on `POST /underwriting/cases` and `POST /policies/manual-issue`.
 */
export function isSingleLifeProduct(p: { category?: ProductCategory | null }): boolean {
  return p.category !== 'GROUP_LIFE' && p.category !== 'CREDIT_LIFE';
}

export const RATING_FACTOR_TYPES: readonly RatingFactorType[] = [
  'AGE',
  'OCCUPATION_CLASS',
  'SMOKER_STATUS',
  'SUM_ASSURED_BAND',
];

export const BENEFIT_TYPES: readonly BenefitScheduleType[] = [
  'DEATH',
  'DISABILITY',
  'CRITICAL_ILLNESS',
  'MATURITY',
  'SURRENDER',
];

export const BENEFIT_CALCULATION_METHODS: readonly BenefitCalculationMethod[] = [
  'SUM_ASSURED',
  'PERCENTAGE_OF_SUM_ASSURED',
  'FLAT_AMOUNT',
];

/**
 * What each method pays, in the words an actuary would use rather than the wire literal.
 * `humanizeStatus` would render PERCENTAGE_OF_SUM_ASSURED as "Percentage of sum assured",
 * which is the mechanism and not the answer to "what does this benefit pay?".
 */
export const BENEFIT_CALCULATION_METHOD_LABELS: Record<BenefitCalculationMethod, string> = {
  SUM_ASSURED: 'Full sum assured',
  PERCENTAGE_OF_SUM_ASSURED: '% of sum assured',
  FLAT_AMOUNT: 'Flat amount',
};
export type PremiumFrequency = NonNullable<PolicyView['premiumFrequency']>;

export const PREMIUM_FREQUENCIES: readonly PremiumFrequency[] = [
  'MONTHLY',
  'QUARTERLY',
  'ANNUALLY',
  'SINGLE',
];

/**
 * How a premium reads BESIDE its amount — "TZS 10,625 /month", "TZS 125,000 once".
 *
 * A map rather than string surgery on the enum, because the surgery does not survive SINGLE.
 * The registers derived this by stripping a trailing "LY" and lowercasing, which turns
 * MONTHLY into "month" and SINGLE into "single" — and "TZS 125,000 /single" says nothing a
 * reader wants. A single premium is not a rate per period; it is the whole price, once.
 */
export const PREMIUM_FREQUENCY_SUFFIXES: Record<PremiumFrequency, string> = {
  MONTHLY: '/month',
  QUARTERLY: '/quarter',
  ANNUALLY: '/year',
  SINGLE: 'once',
};

/** The frequency as a noun, for a picker or a label: "Single premium", not "SINGLE". */
export const PREMIUM_FREQUENCY_LABELS: Record<PremiumFrequency, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  ANNUALLY: 'Annually',
  SINGLE: 'Single premium',
};

/** Why a policy is being issued by hand. Required on manual issue. */
export type IssuanceBasis = NonNullable<ManualIssueRequest['issuanceBasis']>;

/**
 * The five bases, and whether each starts cover at once.
 *
 * <p>Mirrors the backend's `IssuanceBasis` enum, including the flag: the three that start cover
 * are the three where the contract is already in force somewhere else or the money has already
 * arrived. The other two are new business wearing an exception's clothes, and wait for the first
 * premium like anything else. Kept here rather than derived from the generated type so the form
 * can tell the user which of the two they are about to do.
 */
export const ISSUANCE_BASES: readonly { value: IssuanceBasis; label: string; startsCoverImmediately: boolean }[] = [
  { value: 'MIGRATION', label: 'Migration from another system', startsCoverImmediately: true },
  { value: 'CONVERSION', label: 'Conversion from another policy', startsCoverImmediately: true },
  { value: 'REINSTATEMENT', label: 'Reinstatement after arrears settled', startsCoverImmediately: true },
  { value: 'UNDERWRITING_OVERRIDE', label: 'Underwriting override', startsCoverImmediately: false },
  { value: 'GUARANTEED_ISSUE', label: 'Guaranteed issue', startsCoverImmediately: false },
];

/** The 7 claim lifecycle states, for the status filter. */
export const CLAIM_STATUSES: readonly ClaimStatus[] = [
  'REGISTERED',
  'UNDER_ASSESSMENT',
  'SETTLEMENT_REQUESTED',
  'REOPENED',
  'APPROVED',
  'SETTLED',
  'REJECTED',
];

/**
 * Money the insurer pays OUT while the life assured is still alive (product step 2):
 * a maturity, a money-back plan's survival benefits, an income stream, a premium
 * return. Unlike most views here these ARE fully required in the spec, so a screen
 * can rely on `status`, `kind`, `dueDate` and `attempts` being present.
 */
export type PayoutInstalmentView = BenefitPayoutComponents['schemas']['PayoutInstalment'];
export type InstalmentStatus = BenefitPayoutComponents['schemas']['InstalmentStatus'];
export type PayoutKind = PayoutInstalmentView['kind'];
export type ProofOfLifeMethod = BenefitPayoutComponents['schemas']['ProofOfLifeMethod'];
/** One day's batch of income instalments, released by one person. */
export type PaymentRunView = BenefitPayoutComponents['schemas']['PaymentRun'];
/** A customer walking away inside the free-look window, and what is withheld. */
export type FreeLookCancellationView = BenefitPayoutComponents['schemas']['FreeLookCancellation'];

/** A savings account and its whole ledger (product step 3). */
export type AccountView = AccumulationComponents['schemas']['Account'];
export type LedgerEntryView = AccumulationComponents['schemas']['LedgerEntry'];
export type EntryType = LedgerEntryView['type'];
export type WithdrawalView = AccumulationComponents['schemas']['Withdrawal'];
export type TopUpView = AccumulationComponents['schemas']['TopUp'];
export type TransferInView = AccumulationComponents['schemas']['TransferIn'];
export type AdjustmentView = AccumulationComponents['schemas']['Adjustment'];
export type ClosingQuoteView = AccumulationComponents['schemas']['ClosingQuote'];
export type StatementView = AccumulationComponents['schemas']['Statement'];
export type StatementRecordView = AccumulationComponents['schemas']['StatementRecord'];
export type RateDeclarationView = AccumulationComponents['schemas']['RateDeclaration'];

/** The payout register's status filter, in the order a queue is worked. */
export const INSTALMENT_STATUSES: readonly InstalmentStatus[] = [
  'DUE',
  'REVIEWED',
  'APPROVED',
  'ON_HOLD',
  'FAILED',
  'IN_DOUBT',
  'PAID',
  'SCHEDULED',
  'CANCELLED',
];

/** The `{ items, page }` envelope the 4 paged endpoints return. */
export interface Page<T> {
  items: T[];
  page: PageMeta;
}

/** The 7 policy lifecycle states, for the status filter. */
export const POLICY_STATUSES: readonly PolicyStatus[] = [
  'PROPOSED',
  'ACTIVE',
  'REINSTATED',
  'SUSPENDED',
  'LAPSED',
  'MATURED',
  'SURRENDERED',
];

/**
 * `UnderwritingCaseView` deliberately omits `productVersionId`, `sumAssuredAmount`
 * and `sumAssuredCurrency` -- `underwriting.api.UnderwritingCaseView` marks all
 * three `@JsonIgnore` (a real regression fix: they leaked into the JSON response
 * and violated this very spec's `additionalProperties:false` until that was
 * added). A case's sum assured is therefore knowable only at the moment you
 * open it, from that response -- there is no way to read it back afterward.
 */
export type UnderwritingCaseView = UnderwritingComponents['schemas']['UnderwritingCaseView'];
export type OpenGroupCaseRequest = UnderwritingComponents['schemas']['OpenGroupCaseRequest'];
export type GroupProposal = UnderwritingComponents['schemas']['GroupProposal'];
export type MedicalDisclosureView = UnderwritingComponents['schemas']['MedicalDisclosureView'];
export type DisclosureAnswer = UnderwritingComponents['schemas']['DisclosureAnswer'];
export type RecordDisclosuresRequest = UnderwritingComponents['schemas']['RecordDisclosuresRequest'];
export type UnderwritingCaseStatus = NonNullable<UnderwritingCaseView['status']>;
export const UNDERWRITING_CASE_STATUSES: readonly UnderwritingCaseStatus[] = ['OPEN', 'IN_REVIEW', 'DECIDED'];
export type UnderwritingReferralStatus = NonNullable<UnderwritingCaseView['referralStatus']>;
export type UnderwritingDecisionOutcome = NonNullable<UnderwritingCaseView['decisionOutcome']>;
export type OpenCaseRequest = UnderwritingComponents['schemas']['OpenCaseRequest'];
export type SubmitAssessmentRequest = UnderwritingComponents['schemas']['SubmitAssessmentRequest'];
export type AssessmentType = NonNullable<SubmitAssessmentRequest['assessmentType']>;
export type DecideRequest = UnderwritingComponents['schemas']['DecideRequest'];
export type BeneficiaryNomination = UnderwritingComponents['schemas']['BeneficiaryNomination'];

export const ASSESSMENT_TYPES: readonly AssessmentType[] = ['MEDICAL', 'FINANCIAL', 'OCCUPATIONAL'];
