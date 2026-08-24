import { post, get } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type { ClaimStatus, ClaimView, Page, RegisterClaimRequest } from './types';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './policies';

/**
 * Claims read/write surface, hand-written for the same reasons as api/policies.ts:
 * no spec declares an `operationId`, and the Axios layer here owns idempotency and
 * error normalization that a generated client would fight.
 */

export interface ClaimSearchParams {
  status?: ClaimStatus;
  claimantPartyId?: string;
  page?: number;
  pageSize?: number;
}

/** `GET /claims` -- paged, using the shared `{items, page}` envelope. */
export async function searchClaims(params: ClaimSearchParams = {}): Promise<Page<ClaimView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: ClaimView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/claims', {
    params: {
      ...(params.status ? { status: params.status } : {}),
      ...(params.claimantPartyId ? { claimantPartyId: params.claimantPartyId } : {}),
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

export function getClaim(claimId: string): Promise<ClaimView> {
  return get<ClaimView>(`/claims/${encodeURIComponent(claimId)}`);
}

/**
 * `POST /claims` -- one of only six endpoints on the platform that HARD-REQUIRES
 * `Idempotency-Key` (a 400 without it). `attempt` is minted once by the caller for
 * the lifetime of one registration attempt and reused across retries, so a
 * network timeout followed by a retry cannot register the same event twice.
 */
export function registerClaim(
  request: RegisterClaimRequest,
  attempt: MutationAttempt,
): Promise<ClaimView> {
  return post<ClaimView>('/claims', request, { headers: attempt.headers() });
}
