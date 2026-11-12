import { get } from '@/lib/http';
import type { components } from '@/types/api/reporting';
import type { ExportFormat, ReportResult } from './reporting';

/**
 * The signed-in agent's sales in money (2026-11-12, analytics plan 3): sold means cover started; premium a year,
 * single premiums and savings deposits apart. Scoped by the token; `agentId` (Me) only inside the agent's team.
 */
export type SalesTotals = components['schemas']['SalesTotals'];
export type PolicySold = components['schemas']['PolicySold'];
export type SalesBy = 'MONTH' | 'QUARTER' | 'YEAR';
export type CompareWith = 'PRIOR_PERIOD' | 'SAME_PERIOD_LAST_YEAR' | 'NONE';

export interface SalesQuery {
  from: string;
  to: string;
  by: SalesBy;
  compareWith: CompareWith;
  agentId?: string;
}

function params(q: SalesQuery): Record<string, string> {
  return { from: q.from, to: q.to, by: q.by, compareWith: q.compareWith, ...(q.agentId ? { agentId: q.agentId } : {}) };
}

/** The buckets themselves (2026-11, 2026-Q4, 2026), so each row can open the policies behind it. */
export function getMySales(q: SalesQuery): Promise<SalesTotals[]> {
  return get<SalesTotals[]>('/agents/me/sales', {
    params: { from: q.from, to: q.to, by: q.by, ...(q.agentId ? { agentId: q.agentId } : {}) },
  });
}

export function getMySalesReport(q: SalesQuery): Promise<ReportResult> {
  return get<ReportResult>('/agents/me/sales/report', { params: params(q) });
}

/** The policies behind a figure: sold (cover started) between two days. */
export function getPoliciesSold(from: string, to: string, agentId?: string): Promise<PolicySold[]> {
  return get<PolicySold[]>('/agents/me/sales/policies', { params: { from, to, ...(agentId ? { agentId } : {}) } });
}

const ACCEPT: Record<ExportFormat, string> = {
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  csv: 'text/csv',
  pdf: 'application/pdf',
};

/** The report as a file, fetched with the sign-in (never a plain link); every download is recorded. */
export function exportMySales(q: SalesQuery, format: ExportFormat): Promise<Blob> {
  return get<Blob>('/agents/me/sales/export', {
    params: { ...params(q), format },
    responseType: 'blob',
    headers: { Accept: ACCEPT[format] },
  });
}
