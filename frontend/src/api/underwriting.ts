import { get, post } from '@/lib/http';
import type {
  DecideRequest,
  MedicalDisclosureView,
  OpenCaseRequest,
  Page,
  RecordDisclosuresRequest,
  SubmitAssessmentRequest,
  UnderwritingCaseStatus,
  UnderwritingCaseView,
} from './types';

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
 * role specifically (not just staff).
 *
 * Records EVIDENCE and recomputes the rules engine's `recommendationOutcome`. It settles
 * nothing: the case stays `IN_REVIEW`, no event is published, and no policy is issued.
 * Use {@link decide} for that.
 *
 * This used to decide the case outright -- the engine ran after every submission and its
 * verdict went straight into the decision fields, so the FIRST assessment settled a case
 * whatever type it was, and a clean medical issued a contract before anyone had looked at
 * the applicant's finances or occupation.
 *
 * Still 409s on a case that is already decided, because a decided case is closed to further
 * evidence -- except a POSTPONED one, which means "come back with more" and stays open.
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

/**
 * `POST /underwriting/cases/{caseId}/decision` -- the human underwriting decision.
 *
 * The ONLY thing that settles a case, and therefore the only thing that puts a policy in
 * force. Submitting an assessment records evidence and recomputes the engine's
 * recommendation; it decides nothing. It used to do both, which meant a placeholder rules
 * engine issued real contracts with nobody signing them off.
 *
 * A decision whose `outcome` differs from the case's `recommendationOutcome` is an override
 * and answers **403 `SENIOR_UNDERWRITER_APPROVAL_REQUIRED`** unless the caller holds
 * `SENIOR_UNDERWRITER`. Callers should gate on that, but must still handle the 403: whether
 * a decision counts as an override depends on the recommendation on the case at the moment
 * the server reads it, which can have moved since the form rendered.
 *
 * 422 for a case with no assessment, a blank reason, or a loading that does not match the
 * outcome. 409 for a case already decided.
 */
export function decide(caseId: string, request: DecideRequest): Promise<UnderwritingCaseView> {
  return post<UnderwritingCaseView>(
    `/underwriting/cases/${encodeURIComponent(caseId)}/decision`,
    request,
  );
}

/** `POST /underwriting/cases/{caseId}/referral` -- `UNDERWRITER` role. Takes no
 *  body, returns no body, and has no status guard server-side (callable even on
 *  an already-decided case, and repeatable). */
export function referCase(caseId: string): Promise<void> {
  return post<void>(`/underwriting/cases/${encodeURIComponent(caseId)}/referral`);
}

/**
 * `POST /underwriting/cases/{caseId}/disclosures` -- agents or staff, deliberately NOT the
 * UNDERWRITER role that gates assessment: the person who asked the questions records the
 * answers.
 *
 * The medical_disclosure table existed from M4 with no writer at all, while claims computes
 * and displays `requiresContestabilityReview` -- a review with nothing to review. This is what
 * gives it something.
 */
export function recordDisclosures(
  caseId: string,
  request: RecordDisclosuresRequest,
): Promise<MedicalDisclosureView> {
  return post<MedicalDisclosureView>(
    `/underwriting/cases/${encodeURIComponent(caseId)}/disclosures`,
    request,
  );
}

/** `GET /underwriting/cases/{caseId}/disclosures` -- oldest first; a later set never replaces an earlier one. */
export function listDisclosures(caseId: string): Promise<MedicalDisclosureView[]> {
  return get<MedicalDisclosureView[]>(`/underwriting/cases/${encodeURIComponent(caseId)}/disclosures`);
}
