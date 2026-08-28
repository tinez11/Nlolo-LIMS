import { get, post } from '@/lib/http';
import type { OpenCaseRequest, Page, SubmitAssessmentRequest, UnderwritingCaseStatus, UnderwritingCaseView } from './types';

/**
 * Underwriting read/write surface.
 *
 * `GET /underwriting/cases` (added alongside this comment) is tenant-scoped and
 * status-filterable, but carries no free-text search -- a case has no
 * human-facing identifier the way a policy number or claim does, only a raw
 * UUID `caseId`. `POST /policies`'s own `underwritingCaseId` still never
 * round-trips back out through `GET /policies` either (`PolicyResponseDto` --
 * the actual wire DTO, not the internal `PolicyView` -- has no such field, and
 * neither does the OpenAPI spec's `PolicyView` response schema): the ONLY way
 * to reach a specific case afterward is either the id captured when it was
 * opened, or browsing the list below.
 */

export interface UnderwritingListParams {
  status?: UnderwritingCaseStatus;
  /** One applicant, for a client's underwriting panel. */
  applicantPartyId?: string;
  page?: number;
  pageSize?: number;
}

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

/**
 * `GET /underwriting/cases` -- agent or staff, tenant-scoped, no free-text search.
 *
 * An agents-realm caller is force-scoped server-side to applicants it registered,
 * and cannot widen that by passing `applicantPartyId` for someone else's client --
 * that returns an empty page, not a 403. So an empty underwriting panel on an
 * agent's screen means "none among your clients", which is not the same statement
 * the staff screen makes.
 */
export async function listCases(params: UnderwritingListParams = {}): Promise<Page<UnderwritingCaseView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: UnderwritingCaseView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/underwriting/cases', {
    params: {
      ...(params.status ? { status: params.status } : {}),
      ...(params.applicantPartyId ? { applicantPartyId: params.applicantPartyId } : {}),
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

/** `POST /underwriting/cases` -- agent or staff. No Idempotency-Key enforcement
 *  yet (accepted, not required, per the controller's own comment). */
export function openCase(request: OpenCaseRequest): Promise<UnderwritingCaseView> {
  return post<UnderwritingCaseView>('/underwriting/cases', request);
}

/** `GET /underwriting/cases/{caseId}` -- agent or staff. 404s on an unknown or
 *  cross-tenant id. */
export function getCase(caseId: string): Promise<UnderwritingCaseView> {
  return get<UnderwritingCaseView>(`/underwriting/cases/${encodeURIComponent(caseId)}`);
}

/**
 * `POST /underwriting/cases/{caseId}/assessments` -- requires the `UNDERWRITER`
 * role specifically (not just staff). `decideIfPossible` runs unconditionally
 * after EVERY submission, not once "enough" evidence exists -- so this call
 * decides the case outright and 409s on any second call once decided
 * (`UnderwritingCaseAlreadyDecidedException`). There is no separate "decide"
 * action to model: submitting one assessment IS the decision.
 */
export function submitAssessment(
  caseId: string,
  request: SubmitAssessmentRequest,
): Promise<UnderwritingCaseView> {
  return post<UnderwritingCaseView>(
    `/underwriting/cases/${encodeURIComponent(caseId)}/assessments`,
    request,
  );
}

/** `POST /underwriting/cases/{caseId}/referral` -- `UNDERWRITER` role. Takes no
 *  body, returns no body, and has no status guard server-side (callable even on
 *  an already-decided case, and repeatable). */
export function referCase(caseId: string): Promise<void> {
  return post<void>(`/underwriting/cases/${encodeURIComponent(caseId)}/referral`);
}
