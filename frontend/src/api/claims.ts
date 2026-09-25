import { post, get } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  ClaimAssessmentView,
  ClaimCoverView,
  ClaimEvidenceView,
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
  q?: string;
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
      ...(params.q ? { q: params.q } : {}),
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
 * `GET /claims/{claimId}/assessments` -- newest first. `CLAIMS_ASSESSOR` or
 * `CLAIMS_MANAGER`, and unlike most claim reads NOT open to a customer or an
 * agent: findings are internal and `fraudIndicator` is a scrutiny signal about
 * the claimant that must not travel back to them.
 *
 * The manager deciding a settlement is, by the separation-of-duties rule,
 * never the person who assessed it -- so this is the only way they can see the
 * recommendation they are being asked to approve.
 */
export function listClaimAssessments(claimId: string): Promise<ClaimAssessmentView[]> {
  return get<ClaimAssessmentView[]>(`/claims/${encodeURIComponent(claimId)}/assessments`);
}

/**
 * `GET /claims/{claimId}/claimable-cover` -- the most this claim may pay.
 *
 * The exact figure `Claim.approve` bounds an approval with, resolved from the
 * claim's own stored facts. On credit life it is the outstanding loan balance
 * on the date of event, so it declines every month and must be read fresh
 * rather than remembered.
 *
 * 409s when the claim's facts no longer resolve to cover (its policy changed
 * after registration -- registration runs the same resolution). Callers render
 * that rather than falling back to a zero, which would read as "this claim
 * pays nothing".
 */
export function getClaimableCover(claimId: string): Promise<ClaimCoverView> {
  return get<ClaimCoverView>(`/claims/${encodeURIComponent(claimId)}/claimable-cover`);
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

/**
 * `POST /claims/{claimId}/evidence` -- customer/agent/staff, object-level
 * ownership enforced server-side. 409s once the claim is SETTLED (reopen it
 * first). `description` is genuinely optional on the wire (`type: [string,
 * "null"]`) -- omitted here rather than sent as `null` when blank, since
 * FormData has no `null`, only "absent" or a string.
 */
export function attachClaimEvidence(
  claimId: string,
  file: File,
  description?: string,
): Promise<ClaimEvidenceView> {
  const form = new FormData();
  form.append('file', file);
  if (description) form.append('description', description);
  return post<ClaimEvidenceView>(`/claims/${encodeURIComponent(claimId)}/evidence`, form);
}

/** `GET /claims/{claimId}/evidence` -- a bare unpaged array. */
export function listClaimEvidence(claimId: string): Promise<ClaimEvidenceView[]> {
  return get<ClaimEvidenceView[]>(`/claims/${encodeURIComponent(claimId)}/evidence`);
}

/**
 * `GET /claims/{claimId}/evidence/{documentRef}` -- returns the raw file with
 * its real Content-Type (one of image/jpeg, image/png, application/pdf,
 * application/octet-stream -- the closed upload allowlist). A mismatched
 * claimId/documentRef pairing 404s identically to a nonexistent ref, so
 * "not yours" and "does not exist" stay indistinguishable to the caller.
 */
export function downloadClaimEvidence(claimId: string, documentRef: string): Promise<Blob> {
  return get<Blob>(`/claims/${encodeURIComponent(claimId)}/evidence/${encodeURIComponent(documentRef)}`, {
    responseType: 'blob',
  });
}
