import { del, get, post, put } from '@/lib/http';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './policies';
import type {
  AccountStatus,
  ChartOfAccountView,
  CreateAccountRequest,
  JournalEntryView,
  Page,
  UpdateAccountRequest,
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
 *  Name and description. accountCode/accountType/normalBalance are not editable, and
 *  neither are parentCode/level: moving an account is a separate, unbuilt concern. */
export function updateAccount(
  accountCode: string,
  request: UpdateAccountRequest,
): Promise<ChartOfAccountView> {
  return put<ChartOfAccountView>(`/chart-of-accounts/${encodeURIComponent(accountCode)}`, request);
}

/**
 * `POST /chart-of-accounts/{accountCode}/{activate|deactivate}` -- staff
 * FINANCE_OFFICER/ADMIN only.
 *
 * Deactivating is the retirement path for an account that has history: the ledger
 * refuses every new posting leg naming it, while each posting already booked to it
 * stays resolvable. Deleting cannot do that, which is why it stays restricted to
 * accounts nothing has ever posted to.
 */
export function setAccountStatus(
  accountCode: string,
  status: AccountStatus,
): Promise<ChartOfAccountView> {
  const action = status === 'ACTIVE' ? 'activate' : 'deactivate';
  return post<ChartOfAccountView>(
    `/chart-of-accounts/${encodeURIComponent(accountCode)}/${action}`,
    {},
  );
}

/**
 * `DELETE /chart-of-accounts/{accountCode}` -- staff FINANCE_OFFICER/ADMIN
 * only. Rejected with a real 409 once any GL posting references the account
 * (`ACCOUNT_IN_USE`) or once it has children (`ACCOUNT_HAS_CHILDREN`).
 * Retiring an account that has history is what `setAccountStatus` above is for.
 */
export function deleteAccount(accountCode: string): Promise<void> {
  return del<void>(`/chart-of-accounts/${encodeURIComponent(accountCode)}`);
}
