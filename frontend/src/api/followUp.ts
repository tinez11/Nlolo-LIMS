import { get, post } from '@/lib/http';
import type { components as Reporting } from '@/types/api/reporting';
import type { components as Billing } from '@/types/api/billing';
import type { ExportFormat } from './reporting';

/**
 * Premiums to follow up (2026-11-11, analytics plan 1): the premiums due today, due soon or overdue, with the client to
 * call and what was already done -- for staff across the company, for an agent their own team's. Calls and reminders
 * are recorded in billing; the list and its files come from reporting.
 */

export type FollowUpPage = Reporting['schemas']['FollowUpPage'];
export type FollowUpRow = Reporting['schemas']['FollowUpRow'];
export type FollowUpAction = Reporting['schemas']['FollowUpAction'];
export type CollectionFigures = Reporting['schemas']['CollectionFigures'];
export type CollectionActionView = Billing['schemas']['CollectionActionView'];

export type FollowUpView = 'DUE_TODAY' | 'DUE_SOON' | 'OVERDUE';
export type CallOutcome = 'WILL_PAY' | 'CANNOT_PAY_NOW' | 'NO_ANSWER' | 'WRONG_NUMBER';

export const LATE_BANDS = ['1–7 days', '8–14 days', '15–30 days', '31–60 days', '61–90 days', 'More than 90 days'] as const;
export const SOON_DAYS = [7, 14, 30] as const;

export interface FollowUpQuery {
  view: FollowUpView;
  soonDays: number;
  lateBand?: string;
  agentId?: string;
  branchCode?: string;
  productId?: string;
  search?: string;
  page?: number;
  pageSize?: number;
}

export interface RecordCallBody {
  invoiceId: string;
  outcome: CallOutcome;
  promisedOn?: string | null;
  note?: string | null;
}

const ACCEPT: Record<ExportFormat, string> = {
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  csv: 'text/csv',
  pdf: 'application/pdf',
};

/** Only the filters that are set: an empty value would be sent as "" and refused. No page -- a file is every row. */
function filters(query: FollowUpQuery): Record<string, string> {
  const out: Record<string, string> = { view: query.view, soonDays: String(query.soonDays) };
  if (query.lateBand) out.lateBand = query.lateBand;
  if (query.agentId) out.agentId = query.agentId;
  if (query.branchCode) out.branchCode = query.branchCode;
  if (query.productId) out.productId = query.productId;
  if (query.search?.trim()) out.search = query.search.trim();
  return out;
}

function params(query: FollowUpQuery): Record<string, string> {
  const out = filters(query);
  if (query.page !== undefined) out.page = String(query.page);
  if (query.pageSize !== undefined) out.pageSize = String(query.pageSize);
  return out;
}

export function listFollowUp(query: FollowUpQuery): Promise<FollowUpPage> {
  return get<FollowUpPage>('/collections/follow-up', { params: params(query) });
}

/** The signed-in agent's own list: the policies their team sold. */
export function listMyFollowUp(query: FollowUpQuery): Promise<FollowUpPage> {
  return get<FollowUpPage>('/agents/me/follow-up', { params: params(query) });
}

/** The list as a file, fetched with the sign-in (never a plain link); every download is recorded. */
export function exportFollowUp(query: FollowUpQuery, format: ExportFormat): Promise<Blob> {
  return get<Blob>('/collections/follow-up/export', {
    params: { ...filters(query), format },
    responseType: 'blob',
    headers: { Accept: ACCEPT[format] },
  });
}

export function exportMyFollowUp(query: FollowUpQuery, format: ExportFormat): Promise<Blob> {
  return get<Blob>('/agents/me/follow-up/export', {
    params: { ...filters(query), format },
    responseType: 'blob',
    headers: { Accept: ACCEPT[format] },
  });
}

export function recordCall(body: RecordCallBody): Promise<CollectionActionView> {
  return post<CollectionActionView>('/collections/actions/calls', body);
}

/** At most one a day per premium; a second is refused with the reason in words. */
export function sendReminder(invoiceId: string): Promise<CollectionActionView> {
  return post<CollectionActionView>('/collections/actions/reminders', { invoiceId });
}

/** Premiums due from `from` to `to` (ISO dates): how much is paid, and the rate; due today and overdue as of today. */
export function collectionFigures(from: string, to: string): Promise<CollectionFigures> {
  return get<CollectionFigures>('/collections/figures', { params: { from, to } });
}
