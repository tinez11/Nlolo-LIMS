import { get, post } from '@/lib/http';
import type { GenerateReturnRequest, RegulatoryReturnView } from './types';

/**
 * Regulatory-returns read/write surface, hand-written for the same reasons as
 * api/policies.ts. Fully built and staff-reachable (FINANCE_OFFICER/ADMIN)
 * since M10; the gap this closes is entirely on the frontend.
 */

/**
 * `POST /regulatory-returns` -- generates (or REGENERATES, replacing prior
 * lines) the return for `returnType`/`period`. A 422 for an unseeded
 * returnType, or a period whose format does not match that definition's
 * periodKind (QUARTERLY, the only implemented kind today).
 */
export function generateReturn(request: GenerateReturnRequest): Promise<RegulatoryReturnView> {
  return post<RegulatoryReturnView>('/regulatory-returns', request);
}

/** `GET /regulatory-returns` -- a bare unpaged array, optionally filtered by period. */
export function listReturns(period?: string): Promise<RegulatoryReturnView[]> {
  return get<RegulatoryReturnView[]>('/regulatory-returns', period ? { params: { period } } : undefined);
}

export function getReturn(returnId: string): Promise<RegulatoryReturnView> {
  return get<RegulatoryReturnView>(`/regulatory-returns/${encodeURIComponent(returnId)}`);
}
