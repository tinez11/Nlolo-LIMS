import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  CessionView,
  ClaimRecoveryView,
  CreateTreatyRequest,
  TreatyStatus,
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
