import { get, post, put } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  BordereauView,
  CessionView,
  ClaimRecoveryView,
  CreateTreatyRequest,
  Page,
  ReinsuranceStatementStatus,
  ReinsuranceStatementView,
  TreatyStatus,
  TreatyUtilisationView,
  TreatyView,
  UpdateReinsuranceStatementRequest,
} from './types';

/**
 * Reinsurance read/write surface: treaty authoring plus the cession/recovery
 * records treaties automatically produce (all event- or job-derived --
 * `CessionCalculator`/`RecoveryCalculator` run off `policy.PolicyActivated` and
 * `claims.ClaimApproved`, the bordereau off the month-end job; there is no manual
 * "create" endpoint for any of them).
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
 * `GET /treaties/{id}/bordereaux` -- the monthly bordereaux the month-end job wrote, newest month first (IFRS 17
 * I3c). Rows carry no lines; read one bordereau for those.
 */
export function listBordereaux(treatyId: string): Promise<BordereauView[]> {
  return get<BordereauView[]>(`/treaties/${encodeURIComponent(treatyId)}/bordereaux`);
}

export function getBordereau(bordereauId: string): Promise<BordereauView> {
  return get<BordereauView>(`/bordereaux/${encodeURIComponent(bordereauId)}`);
}

/** The current month as it would be written now -- computed, never stored. */
export function previewBordereau(treatyId: string): Promise<BordereauView> {
  return get<BordereauView>(`/treaties/${encodeURIComponent(treatyId)}/bordereau-preview`);
}

// ---- The quarterly statement (IFRS 17 I3d) ----

const stmt = (id: string) => `/reinsurance-statements/${encodeURIComponent(id)}`;

export function listStatements(status?: ReinsuranceStatementStatus, treatyId?: string): Promise<ReinsuranceStatementView[]> {
  const params: Record<string, string> = {};
  if (status) params.status = status;
  if (treatyId) params.treatyId = treatyId;
  return get<ReinsuranceStatementView[]>('/reinsurance-statements', { params });
}

/** A DRAFT for one ended calendar quarter (e.g. 2026-Q3), from the treaty's bordereaux and recoveries. */
export function prepareStatement(treatyId: string, quarter: string): Promise<ReinsuranceStatementView> {
  return post<ReinsuranceStatementView>(`/treaties/${encodeURIComponent(treatyId)}/statements`, { quarter });
}

export function getStatement(id: string): Promise<ReinsuranceStatementView> {
  return get<ReinsuranceStatementView>(stmt(id));
}

/** What the reinsurer's statement states -- funds withheld and profit commission as decimal strings -- and why. */
export function updateStatement(id: string, body: UpdateReinsuranceStatementRequest): Promise<ReinsuranceStatementView> {
  return put<ReinsuranceStatementView>(stmt(id), body);
}

export function attachStatementDocument(id: string, file: File): Promise<ReinsuranceStatementView> {
  const form = new FormData();
  form.append('file', file);
  return post<ReinsuranceStatementView>(`${stmt(id)}/documents`, form);
}

export type StatementAction = 'submission' | 'withdrawal' | 'approval';

export function actOnStatement(id: string, action: StatementAction): Promise<ReinsuranceStatementView> {
  return post<ReinsuranceStatementView>(`${stmt(id)}/${action}`, {});
}

export function rejectStatement(id: string, reason: string): Promise<ReinsuranceStatementView> {
  return post<ReinsuranceStatementView>(`${stmt(id)}/rejection`, { reason });
}
