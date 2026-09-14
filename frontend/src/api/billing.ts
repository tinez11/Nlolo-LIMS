import { get, post } from '@/lib/http';
import type { ArrearsCaseView, FieldReceiptStatus, FieldReceiptView, Page } from './types';

/**
 * The billing module's read surface, which until now the console did not have a module for.
 *
 * Invoices live in `api/policies.ts` because every invoice read is keyed by a policy number
 * and belongs to the policy record that shows them. This file exists for the two billing reads
 * that are not about a single policy: the collections queue and the reconciliation queue.
 */

export const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

/** The five dunning levels, in the order the platform escalates through them. */
export const DUNNING_LEVELS = [1, 2, 3, 4, 5] as const;

/**
 * The level at which the platform stops chasing and recommends lapse. Not a display
 * threshold: level 5 publishes `billing.PolicyLapseRecommended`, which `policy` consumes to
 * lapse the contract, so a row at 5 is a policy on its way out rather than one being chased.
 */
export const LAPSE_RECOMMENDATION_LEVEL = 5;

export interface ArrearsSearchParams {
  /**
   * A FLOOR, not an exact match — the endpoint treats it that way, and the question is "what
   * is at this level or worse". An exact filter would hide the level-5 cases from somebody
   * triaging level 3 upward.
   */
  minDunningLevel?: number;
  /** Omit for both, false for the live queue, true for cases already settled or waived. */
  resolved?: boolean;
  page?: number;
  pageSize?: number;
}

/**
 * `GET /arrears` — this tenant's arrears cases, worst escalation first.
 *
 * Finance-gated server-side (`REALM_STAFF` and `FINANCE_OFFICER`/`ADMIN`), so a staff member
 * without the finance role gets a 403 rather than an empty list. Same anonymous-envelope
 * normalisation as every other paged search here: `items` and `page` are both optional on the
 * wire.
 */
export async function searchArrears(params: ArrearsSearchParams = {}): Promise<Page<ArrearsCaseView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: ArrearsCaseView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/arrears', {
    params: {
      ...(params.minDunningLevel !== undefined ? { minDunningLevel: params.minDunningLevel } : {}),
      // Sent when explicitly false as well as true: `false` is the live queue, and dropping a
      // falsy value would silently widen it to include resolved history.
      ...(params.resolved !== undefined ? { resolved: params.resolved } : {}),
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

export interface FieldReceiptSearchParams {
  status?: FieldReceiptStatus;
  page?: number;
  pageSize?: number;
}

/**
 * `GET /field-receipts` — the reconciliation queue, oldest unmatched cash first.
 *
 * The read this entity never had: capture was its only endpoint, so a receipt could breach its
 * SLA and raise a medium-severity Prometheus alert while no screen could name it.
 *
 * Finance-gated server-side. Deliberately NOT agent-scoped: an agent seeing their own
 * submissions is a separate, unbuilt need, and this is unreconciled money across the tenant.
 */
export async function searchFieldReceipts(
  params: FieldReceiptSearchParams = {},
): Promise<Page<FieldReceiptView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: FieldReceiptView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/field-receipts', {
    params: {
      ...(params.status ? { status: params.status } : {}),
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

/**
 * `POST /field-receipts/{id}/reconcile` — matches field-captured cash to money received.
 *
 * The action this queue never had. `FieldReceipt.reconcile()` existed server-side with no
 * caller at all, so a receipt could only ratchet PENDING_RECONCILIATION →
 * RECONCILIATION_OVERDUE and stay there: the queue filled and never drained.
 *
 * Finance-gated server-side. Idempotent — reconciling an already-reconciled receipt returns it
 * unchanged rather than erroring, because two officers clearing the same row is a race rather
 * than a mistake. Returns the updated receipt so the row can be replaced without a refetch.
 */
export function reconcileFieldReceipt(receiptId: string): Promise<FieldReceiptView> {
  return post<FieldReceiptView>(`/field-receipts/${encodeURIComponent(receiptId)}/reconcile`);
}
