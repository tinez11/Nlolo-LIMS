import { del, get, post, put } from '@/lib/http';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './policies';
import type {
  ChartOfAccountView,
  CreateAccountRequest,
  JournalEntryView,
  Page,
  RenameAccountRequest,
} from './types';

/**
 * Finaccounting read/write surface.
 *
 * Journal entries and GL postings stay read-only, permanently: every entry is
 * derived from a domain event by this module's own listeners, and there is no
 * write endpoint for either, ever. The chart of accounts is NOT read-only --
 * create/rename/delete were added on explicit request, after the module first
 * shipped read-only; `accountType`/`normalBalance` stay derived server-side
 * from the account code's own leading digit and are never independently
 * settable on either endpoint below.
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

/** `GET /chart-of-accounts` -- a bare array; some rows are seeded, others
 *  created through the endpoint below. */
export function listChartOfAccounts(): Promise<ChartOfAccountView[]> {
  return get<ChartOfAccountView[]>('/chart-of-accounts');
}

/** `POST /chart-of-accounts` -- staff FINANCE_OFFICER/ADMIN only. A duplicate
 *  accountCode 409s; a malformed one (not 4 digits with a leading 1-5 block) 400s. */
export function createAccount(request: CreateAccountRequest): Promise<ChartOfAccountView> {
  return post<ChartOfAccountView>('/chart-of-accounts', request);
}

/** `PUT /chart-of-accounts/{accountCode}` -- staff FINANCE_OFFICER/ADMIN only.
 *  A plain rename; accountCode/accountType/normalBalance are not editable. */
export function renameAccount(
  accountCode: string,
  request: RenameAccountRequest,
): Promise<ChartOfAccountView> {
  return put<ChartOfAccountView>(`/chart-of-accounts/${encodeURIComponent(accountCode)}`, request);
}

/**
 * `DELETE /chart-of-accounts/{accountCode}` -- staff FINANCE_OFFICER/ADMIN
 * only. Rejected with a real 409 once any GL posting references the account
 * -- retiring an in-use account is a distinct, deferred concern this endpoint
 * does not attempt.
 */
export function deleteAccount(accountCode: string): Promise<void> {
  return del<void>(`/chart-of-accounts/${encodeURIComponent(accountCode)}`);
}
