import { get, post } from '@/lib/http';
import type { OpenCaseRequest, SubmitAssessmentRequest, UnderwritingCaseView } from './types';

/**
 * Underwriting read/write surface.
 *
 * There is no `GET /underwriting/cases` list or search endpoint anywhere on this
 * platform (confirmed against `UnderwritingController` -- only the 4 routes
 * below exist), and `POST /policies`'s own `underwritingCaseId` never round-trips
 * back out through `GET /policies` either (`PolicyResponseDto` -- the actual wire
 * DTO, not the internal `PolicyView` -- has no such field, and neither does the
 * OpenAPI spec's `PolicyView` response schema). A case is therefore reachable
 * ONLY by an id you already hold: the response of opening it, or one handed to
 * you out of band. There is no browse-back path once that id is lost.
 */

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
