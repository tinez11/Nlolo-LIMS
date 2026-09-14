import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  CessionView,
  ClaimRecoveryView,
  CreateTreatyRequest,
  Page,
  TreatyStatus,
  TreatyUtilisationView,
  TreatyView,
} from './types';

/**
 * Reinsurance read/write surface: treaty authoring plus the cession/recovery
 * records treaties automatically produce (both entirely event-derived --
 * `CessionCalculator`/`RecoveryCalculator` run off `policy.PolicyIssued` and
 * `claims.ClaimSettled`; there is no manual "create a cession" endpoint).
 */

/** `POST /treaties` -- staff FINANCE_OFFICER/ADMIN only. Idempotency-Key is
 *  hard-required. */
export function createTreaty(
  request: CreateTreatyRequest,
  attempt: MutationAttempt,
): Promise<TreatyView> {
  return post<TreatyView>('/treaties', request, { headers: attempt.headers() });
}

export function getTreaty(treatyId: string): Promise<TreatyView> {
  return get<TreatyView>(`/treaties/${encodeURIComponent(treatyId)}`);
}

/** Bare array, no pager -- same shape as products' catalog listing. */
export function listTreaties(status?: TreatyStatus): Promise<TreatyView[]> {
  return get<TreatyView[]>('/treaties', status ? { params: { status } } : undefined);
}

export function listCessionsForPolicy(policyNumber: string): Promise<CessionView[]> {
  return get<CessionView[]>(`/policies/${encodeURIComponent(policyNumber)}/cessions`);
}

const CESSION_PAGE_SIZE = 20;

/**
 * `GET /treaties/{id}/cessions` — what has been ceded TO a treaty, newest first.
 *
 * Paged, unlike the per-policy read above: a policy has a handful of cessions and a
 * pager over a fully-downloaded array lies about the network, while a treaty gains one
 * per policy it covers for as long as it runs.
 */
export async function listCessionsForTreaty(
  treatyId: string,
  page = 0,
): Promise<Page<CessionView>> {
  const body = await get<{
    items?: CessionView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>(`/treaties/${encodeURIComponent(treatyId)}/cessions`, {
    params: { page, pageSize: CESSION_PAGE_SIZE },
  });
  return {
    items: body.items ?? [],
    page: {
      page: body.page?.page ?? page,
      pageSize: body.page?.pageSize ?? CESSION_PAGE_SIZE,
      totalElements: body.page?.totalElements ?? 0,
    },
  };
}

/**
 * `GET /treaties/{id}/utilisation` — the treaty's totals, summed server-side.
 *
 * A count and two totals, never a percentage: retention is what the cedant keeps per
 * life rather than a cap on the treaty, so there is no honest denominator to divide by.
 * Its own call rather than fields on the treaty, so that listing treaties does not
 * aggregate the cession table per row — and so this console never sums a page and calls
 * the result utilisation.
 */
export function getTreatyUtilisation(treatyId: string): Promise<TreatyUtilisationView> {
  return get<TreatyUtilisationView>(`/treaties/${encodeURIComponent(treatyId)}/utilisation`);
}

export function listRecoveriesForClaim(claimId: string): Promise<ClaimRecoveryView[]> {
  return get<ClaimRecoveryView[]>(`/claims/${encodeURIComponent(claimId)}/recoveries`);
}

/**
 * `POST .../confirm` -- staff FINANCE_OFFICER/ADMIN only. Idempotency-Key is
 * hard-required. Marks that the reinsurer actually paid; there is no
 * un-confirm. 202, not 200 -- confirmed synchronously here, but finaccounting's
 * journal entry reacts to the published event asynchronously.
 */
export function confirmRecovery(
  claimId: string,
  recoveryId: string,
  attempt: MutationAttempt,
): Promise<ClaimRecoveryView> {
  return post<ClaimRecoveryView>(
    `/claims/${encodeURIComponent(claimId)}/recoveries/${encodeURIComponent(recoveryId)}/confirm`,
    undefined,
    { headers: attempt.headers() },
  );
}
