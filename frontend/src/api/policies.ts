import { get } from '@/lib/http';
import type {
  CoverageStatusView,
  InvoiceView,
  LoanView,
  Page,
  PolicyStatus,
  PolicyView,
} from './types';

/**
 * Policy read surface, hand-written over the generated types.
 *
 * Hand-written rather than generated because no spec declares an `operationId`, so
 * a client generator would invent names for all 68 operations -- and because the
 * Axios layer here owns the error normalization and idempotency interceptors.
 */

export interface PolicySearchParams {
  status?: PolicyStatus;
  policyholderPartyId?: string;
  page?: number;
  pageSize?: number;
}

/** Server caps pageSize at 100 regardless of what is sent. */
export const MAX_PAGE_SIZE = 100;
export const DEFAULT_PAGE_SIZE = 20;

/**
 * `GET /policies` -- one of only four paged endpoints on the platform.
 *
 * The spec's response envelope is an anonymous inline object with no `required`, so
 * `items` and `page` are both optional on the wire. Normalized here to a total
 * `Page<T>` so every caller does not repeat the same defaulting.
 */
export async function searchPolicies(params: PolicySearchParams = {}): Promise<Page<PolicyView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: PolicyView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/policies', {
    params: {
      ...(params.status ? { status: params.status } : {}),
      ...(params.policyholderPartyId ? { policyholderPartyId: params.policyholderPartyId } : {}),
      page,
      pageSize,
    },
  });

  return {
    items: body.items ?? [],
    page: {
      page: body.page?.page ?? page,
      pageSize: body.page?.pageSize ?? pageSize,
      totalElements: body.page?.totalElements ?? 0,
    },
  };
}

export function getPolicy(policyNumber: string): Promise<PolicyView> {
  return get<PolicyView>(`/policies/${encodeURIComponent(policyNumber)}`);
}

export function getCoverageStatus(
  policyNumber: string,
  asOf?: string,
): Promise<CoverageStatusView> {
  return get<CoverageStatusView>(
    `/policies/${encodeURIComponent(policyNumber)}/coverage-status`,
    asOf ? { params: { asOf } } : undefined,
  );
}

/** `GET /policies/{n}/invoices` -- a bare unpaged array, so the whole set arrives. */
export function listInvoices(policyNumber: string): Promise<InvoiceView[]> {
  return get<InvoiceView[]>(`/policies/${encodeURIComponent(policyNumber)}/invoices`);
}

/** `GET /policies/{n}/loans` -- also a bare unpaged array. */
export function listLoans(policyNumber: string): Promise<LoanView[]> {
  return get<LoanView[]>(`/policies/${encodeURIComponent(policyNumber)}/loans`);
}
