import { del, get, post, put } from '@/lib/http';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './policies';
import type {
  AccountingPeriodView,
  AccountStatus,
  ChartOfAccountView,
  PolicyElectionInput,
  PolicyClassificationView,
  PolicyElectionView,
  CreateAccountRequest,
  JournalEntryView,
  JournalTemplateView,
  ManualJournalInput,
  ManualJournalLineInput,
  ManualJournalView,
  Page,
  PostingRulesView,
  TrialBalanceView,
  UnpostedEventView,
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
  /** Restricts to entries with at least one leg against this account -- what lets a
   *  balance on the chart be opened up into the postings that made it. */
  accountCode?: string;
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
      ...(params.accountCode ? { accountCode: params.accountCode } : {}),
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
 *  created through the endpoint below. Structure only: balances come from
 *  {@link getTrialBalance}, so rendering the tree does not pay for an
 *  aggregation over the whole posting table. */
export function listChartOfAccounts(): Promise<ChartOfAccountView[]> {
  return get<ChartOfAccountView[]>('/chart-of-accounts');
}

/**
 * `GET /chart-of-accounts/balances` -- the chart with its balances, and whether
 * the ledger balances.
 *
 * The aggregation this module never had: nothing summed a posting anywhere, so a
 * ledger of thousands of balanced entries could not state one account's balance.
 *
 * Balances ROLL UP the hierarchy (a parent reports itself plus every descendant);
 * the TOTALS deliberately do not, because adding rolled figures would count each
 * posting once per ancestor. `balanced` is the server's own assertion -- this
 * console never does arithmetic on money.
 *
 * @param period `YYYY-MM`, or omitted for inception-to-date.
 */
export function getTrialBalance(period?: string): Promise<TrialBalanceView> {
  return get<TrialBalanceView>('/chart-of-accounts/balances', period ? { params: { period } } : undefined);
}

/** `POST /chart-of-accounts` -- staff FINANCE_OFFICER/ADMIN only. A duplicate
 *  accountCode 409s; a malformed one (not 4 digits in the guide's classes 1-9) 400s. */
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

/** `GET /finance/policy-classifications/{policyNumber}` (IFRS 17 I2) -- finance/admin; oldest first. */
export function getPolicyClassifications(policyNumber: string): Promise<PolicyClassificationView[]> {
  return get<PolicyClassificationView[]>(`/finance/policy-classifications/${encodeURIComponent(policyNumber)}`);
}

// ---- Accounting periods (IFRS 17 spec §5.4) ----

/** `GET /finance/periods` -- the periods that have left OPEN at least once, newest first. */
export function listPeriods(): Promise<AccountingPeriodView[]> {
  return get<AccountingPeriodView[]>('/finance/periods');
}

export type PeriodAction = 'closing' | 'lock' | 'reopen-approval';

/** Start closing, lock, or approve a reopening. A refusal is a 409 carrying the reason. */
export function actOnPeriod(period: string, action: PeriodAction): Promise<AccountingPeriodView> {
  return post<AccountingPeriodView>(`/finance/periods/${encodeURIComponent(period)}/${action}`, {});
}

export function requestReopen(period: string, reason: string): Promise<AccountingPeriodView> {
  return post<AccountingPeriodView>(`/finance/periods/${encodeURIComponent(period)}/reopen-request`, {
    reason,
  });
}

// ---- The accounting policy register (IFRS 17 spec §3) ----

/** In force on `asOf` (today when omitted), one per key and scope; then those approved to take effect
 *  later; then every PROPOSED one. */
export function listPolicyElections(asOf?: string): Promise<PolicyElectionView[]> {
  return get<PolicyElectionView[]>('/finance/accounting-policies', asOf ? { params: { asOf } } : undefined);
}

export function proposePolicyElection(input: PolicyElectionInput): Promise<PolicyElectionView> {
  return post<PolicyElectionView>('/finance/accounting-policies', input);
}

export function approvePolicyElection(electionId: string, signOffRef: string): Promise<PolicyElectionView> {
  return post<PolicyElectionView>(
    `/finance/accounting-policies/${encodeURIComponent(electionId)}/approval`,
    { signOffRef },
  );
}

export function rejectPolicyElection(electionId: string, reason: string): Promise<PolicyElectionView> {
  return post<PolicyElectionView>(
    `/finance/accounting-policies/${encodeURIComponent(electionId)}/rejection`,
    { reason },
  );
}

// ---- The posting rules and the events they could not post (IFRS 17 I3a) ----

/** `GET /finance/posting-rules` -- the rules in force, read-only (the rules file is the source of truth). */
export function getPostingRules(): Promise<PostingRulesView> {
  return get<PostingRulesView>('/finance/posting-rules');
}

/** `GET /finance/unposted-events` -- open first (oldest first), then the most recently resolved. */
export function listUnpostedEvents(openOnly = false): Promise<UnpostedEventView[]> {
  return get<UnpostedEventView[]>('/finance/unposted-events', openOnly ? { params: { openOnly: true } } : undefined);
}

/** Posts it with the rules in force now; still unpostable, it stays open with the new reason. */
export function retryUnpostedEvent(id: string): Promise<UnpostedEventView> {
  return post<UnpostedEventView>(`/finance/unposted-events/${encodeURIComponent(id)}/retry`, {});
}

export function dismissUnpostedEvent(id: string, reason: string): Promise<UnpostedEventView> {
  return post<UnpostedEventView>(`/finance/unposted-events/${encodeURIComponent(id)}/dismissal`, { reason });
}

// ---- Manual journals (IFRS 17 I4) ----

const mj = (id: string) => `/finance/manual-journals/${encodeURIComponent(id)}`;

export function listManualJournals(status?: string, period?: string, preparer?: string): Promise<ManualJournalView[]> {
  const params: Record<string, string> = {};
  if (status) params.status = status;
  if (period) params.period = period;
  if (preparer) params.preparer = preparer;
  return get<ManualJournalView[]>('/finance/manual-journals', { params });
}

export function getManualJournal(id: string): Promise<ManualJournalView> {
  return get<ManualJournalView>(mj(id));
}

export function createManualJournal(input: ManualJournalInput): Promise<ManualJournalView> {
  return post<ManualJournalView>('/finance/manual-journals', input);
}

export function updateManualJournal(id: string, input: ManualJournalInput): Promise<ManualJournalView> {
  return put<ManualJournalView>(mj(id), input);
}

export type ManualJournalAction = 'submission' | 'withdrawal' | 'approval';

/** Submit, withdraw or approve. A refusal is a 422 listing every problem, or a 409 with the reason. */
export function actOnManualJournal(id: string, action: ManualJournalAction): Promise<ManualJournalView> {
  return post<ManualJournalView>(`${mj(id)}/${action}`, {});
}

export function rejectManualJournal(id: string, reason: string): Promise<ManualJournalView> {
  return post<ManualJournalView>(`${mj(id)}/rejection`, { reason });
}

/** A new draft reversing a posted journal; it is approved like any other. */
export function reverseManualJournal(id: string): Promise<ManualJournalView> {
  return post<ManualJournalView>(`${mj(id)}/reversal`, {});
}

export function attachJournalDocument(id: string, file: File): Promise<ManualJournalView> {
  const form = new FormData();
  form.append('file', file);
  return post<ManualJournalView>(`${mj(id)}/documents`, form);
}

export function detachJournalDocument(id: string, documentRef: string): Promise<ManualJournalView> {
  return del<ManualJournalView>(`${mj(id)}/documents/${encodeURIComponent(documentRef)}`);
}

/** Replaces a draft's lines from a CSV or Excel file (account, side, amount, description, branch, fund, reference). */
export function uploadJournalLines(id: string, file: File): Promise<ManualJournalView> {
  const form = new FormData();
  form.append('file', file);
  return post<ManualJournalView>(`${mj(id)}/lines`, form);
}

export function listJournalTemplates(): Promise<JournalTemplateView[]> {
  return get<JournalTemplateView[]>('/finance/journal-templates');
}

export function saveJournalTemplate(
  name: string,
  description: string | null,
  lines: ManualJournalLineInput[],
  reasonCode: string | null,
): Promise<JournalTemplateView> {
  return post<JournalTemplateView>('/finance/journal-templates', { name, description, lines, reasonCode });
}
