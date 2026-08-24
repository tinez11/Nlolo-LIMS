import { post, get } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  ClaimAssessmentView,
  ClaimStatus,
  ClaimView,
  Page,
  RegisterClaimRequest,
  ReopenClaimRequest,
  SettlementDecisionRequest,
  SubmitClaimAssessmentRequest,
} from './types';
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

/**
 * `POST /claims/{claimId}/assessments` -- `CLAIMS_ASSESSOR` role only. Callable
 * repeatedly while a claim is REGISTERED, REOPENED, or already UNDER_ASSESSMENT
 * (unlike underwriting's single-shot assessment, `Claim.beginAssessment()` is a
 * no-op once already UNDER_ASSESSMENT rather than a 409) -- there is no cap on
 * how many assessments a claim can carry before a decision is made.
 */
export function submitClaimAssessment(
  claimId: string,
  request: SubmitClaimAssessmentRequest,
): Promise<ClaimAssessmentView> {
  return post<ClaimAssessmentView>(`/claims/${encodeURIComponent(claimId)}/assessments`, request);
}

/**
 * `POST /claims/{claimId}/settlement-decision` -- `CLAIMS_MANAGER` role only,
 * deliberately distinct from `CLAIMS_ASSESSOR`. `ClaimsApiImpl.decideSettlement`
 * additionally checks the PERSISTED assessor identity, not just the role claim:
 * the same person who assessed a claim cannot also decide it, even if they
 * somehow held both roles. `Idempotency-Key` is only load-bearing when
 * `approved` is true (it becomes `claim.settlement_idempotency_key`), but is
 * minted and sent unconditionally here -- simpler than threading "approved or
 * not" into whether a key exists, and harmless to include on a rejection.
 */
export function decideSettlement(
  claimId: string,
  request: SettlementDecisionRequest,
  attempt: MutationAttempt,
): Promise<ClaimView> {
  return post<ClaimView>(`/claims/${encodeURIComponent(claimId)}/settlement-decision`, request, {
    headers: attempt.headers(),
  });
}

/** `POST /claims/{claimId}/reopen` -- `CLAIMS_MANAGER` role only. Only valid from
 *  REJECTED or SETTLED; any other status 409s. */
export function reopenClaim(claimId: string, request: ReopenClaimRequest): Promise<ClaimView> {
  return post<ClaimView>(`/claims/${encodeURIComponent(claimId)}/reopen`, request);
}
