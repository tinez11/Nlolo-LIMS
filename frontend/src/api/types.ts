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
