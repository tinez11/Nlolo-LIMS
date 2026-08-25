import { get } from '@/lib/http';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './policies';
import type { ChartOfAccountView, JournalEntryView, Page } from './types';

/**
 * Finaccounting read surface. Read-only, permanently: every journal entry is
 * derived from a domain event by this module's own listeners -- there is no
 * write endpoint anywhere on this surface, and there never will be.
 */

export interface GlPostingSearchParams {
  period?: string;
  policyNumber?: string;
  page?: number;
  pageSize?: number;
}

/**
 * `GET /gl-postings` -- one of only four paged endpoints on the platform
 * (added by M9's final review: it used to answer with a bare unbounded array,
 * the one table this platform guarantees grows without bound).
 */
export async function listGlPostings(
  params: GlPostingSearchParams = {},
): Promise<Page<JournalEntryView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: JournalEntryView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/gl-postings', {
    params: {
      ...(params.period ? { period: params.period } : {}),
      ...(params.policyNumber ? { policyNumber: params.policyNumber } : {}),
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

export function getGlPosting(journalEntryId: string): Promise<JournalEntryView> {
  return get<JournalEntryView>(`/gl-postings/${encodeURIComponent(journalEntryId)}`);
}

/** `GET /chart-of-accounts` -- a bare array; account rows are seeded, never
 *  authored through this API. */
export function listChartOfAccounts(): Promise<ChartOfAccountView[]> {
  return get<ChartOfAccountView[]>('/chart-of-accounts');
}
