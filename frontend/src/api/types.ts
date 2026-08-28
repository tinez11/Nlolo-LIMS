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
import type { components as BillingComponents } from '@/types/api/billing';
import type { components as ClaimsComponents } from '@/types/api/claims';
import type { components as CommonComponents } from '@/types/api/common';
import type { components as PartyComponents } from '@/types/api/party';
import type { components as PolicyComponents } from '@/types/api/policy';
import type { components as DistributionComponents } from '@/types/api/distribution';
import type { components as FinaccountingComponents } from '@/types/api/finaccounting';
import type { components as PolicyLoanComponents } from '@/types/api/policyloan';
import type { components as ProductComponents } from '@/types/api/product';
import type { components as RegreportingComponents } from '@/types/api/regreporting';
import type { components as ReinsuranceComponents } from '@/types/api/reinsurance';
import type { components as UnderwritingComponents } from '@/types/api/underwriting';

export type PolicyView = PolicyComponents['schemas']['PolicyView'];
export type PolicyStatus = NonNullable<PolicyView['status']>;
export type BeneficiaryInput = PolicyComponents['schemas']['BeneficiaryInput'];
/** The reverse direction: a policy that names some party as beneficiary. */
export type BeneficiaryOfView = PolicyComponents['schemas']['BeneficiaryOfView'];
export type CoverageStatusView = PolicyComponents['schemas']['CoverageStatusView'];

export type InvoiceView = BillingComponents['schemas']['InvoiceView'];
export type LoanView = PolicyLoanComponents['schemas']['LoanView'];

export type PageMeta = CommonComponents['schemas']['PageMeta'];
export type Money = CommonComponents['schemas']['Money'];

export type ClaimView = ClaimsComponents['schemas']['ClaimView'];
export type ClaimStatus = ClaimView['status'];
export type ClaimType = ClaimView['claimType'];
export type ClaimDetails = ClaimsComponents['schemas']['ClaimDetails'];
export type RegisterClaimRequest = ClaimsComponents['schemas']['RegisterClaimRequest'];

export type ClaimAssessmentView = ClaimsComponents['schemas']['ClaimAssessmentView'];
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

/**
 * The chart of accounts is NOT read-only (added on explicit request, after the
 * module first shipped read-only) -- `accountType`/`normalBalance` stay
 * derived server-side from `accountCode`'s own leading digit and are
 * deliberately absent from both request shapes below.
 */
export type CreateAccountRequest = FinaccountingComponents['schemas']['CreateAccountRequest'];
export type RenameAccountRequest = FinaccountingComponents['schemas']['RenameAccountRequest'];

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
export type PartyDocumentView = PartyComponents['schemas']['PartyDocumentView'];
export type KycStatus = NonNullable<PartyView['kycStatus']>;
export const KYC_STATUSES: readonly KycStatus[] = ['PENDING', 'VERIFIED', 'REJECTED'];
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
];

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
export type PremiumFrequency = NonNullable<PolicyView['premiumFrequency']>;

export const PREMIUM_FREQUENCIES: readonly PremiumFrequency[] = ['MONTHLY', 'QUARTERLY', 'ANNUALLY'];

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
export type UnderwritingCaseStatus = NonNullable<UnderwritingCaseView['status']>;
export const UNDERWRITING_CASE_STATUSES: readonly UnderwritingCaseStatus[] = ['OPEN', 'IN_REVIEW', 'DECIDED'];
export type UnderwritingReferralStatus = NonNullable<UnderwritingCaseView['referralStatus']>;
export type UnderwritingDecisionOutcome = NonNullable<UnderwritingCaseView['decisionOutcome']>;
export type OpenCaseRequest = UnderwritingComponents['schemas']['OpenCaseRequest'];
export type SubmitAssessmentRequest = UnderwritingComponents['schemas']['SubmitAssessmentRequest'];
export type AssessmentType = NonNullable<SubmitAssessmentRequest['assessmentType']>;

export const ASSESSMENT_TYPES: readonly AssessmentType[] = ['MEDICAL', 'FINANCIAL', 'OCCUPATIONAL'];
