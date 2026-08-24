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
import type { components as BillingComponents } from '@/types/api/billing';
import type { components as ClaimsComponents } from '@/types/api/claims';
import type { components as CommonComponents } from '@/types/api/common';
import type { components as PolicyComponents } from '@/types/api/policy';
import type { components as PolicyLoanComponents } from '@/types/api/policyloan';

export type PolicyView = PolicyComponents['schemas']['PolicyView'];
export type PolicyStatus = NonNullable<PolicyView['status']>;
export type BeneficiaryInput = PolicyComponents['schemas']['BeneficiaryInput'];
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
