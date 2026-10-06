import { get, post } from '@/lib/http';
import type { EngineExtractView, EngineRunView } from './types';

/**
 * The IFRS 17 engine period cycle (IFRS 17 I5a): a closing period's extract for the engine (month-end step 6), the
 * engine's results uploaded, approved by a finance approver who did not upload them -- with the appointed actuary's
 * sign-off reference and report -- and posted through 9160, and the reconciliation's differences explained and accepted
 * by two people (step 7).
 */

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

export function createExtract(period: string): Promise<EngineExtractView> {
  return post<EngineExtractView>(`/ifrs17/periods/${encodeURIComponent(period)}/extracts`, {});
}

export function listExtracts(period: string): Promise<EngineExtractView[]> {
  return get<EngineExtractView[]>(`/ifrs17/periods/${encodeURIComponent(period)}/extracts`);
}

/** The extract as it was sent: the workbook, or one sheet (cash-flows, balances, policies) as CSV. */
export function downloadExtract(id: string, format: 'xlsx' | 'csv' = 'xlsx', sheet?: string): Promise<Blob> {
  const params: Record<string, string> = { format };
  if (sheet) params.sheet = sheet;
  return get<Blob>(`/ifrs17/extracts/${encodeURIComponent(id)}/download`, {
    params,
    responseType: 'blob',
    headers: { Accept: format === 'csv' ? 'text/csv' : XLSX },
  });
}

export function downloadResultsTemplate(): Promise<Blob> {
  return get<Blob>('/ifrs17/results-template', { responseType: 'blob', headers: { Accept: XLSX } });
}

export function uploadEngineResults(file: File): Promise<EngineRunView> {
  const form = new FormData();
  form.append('file', file);
  return post<EngineRunView>('/ifrs17/engine-runs', form);
}

export function listEngineRuns(period?: string, status?: string): Promise<EngineRunView[]> {
  const params: Record<string, string> = {};
  if (period) params.period = period;
  if (status) params.status = status;
  return get<EngineRunView[]>('/ifrs17/engine-runs', { params });
}

export function getEngineRun(id: string): Promise<EngineRunView> {
  return get<EngineRunView>(`/ifrs17/engine-runs/${encodeURIComponent(id)}`);
}

export function approveEngineRun(id: string, signOffReference: string, report: File): Promise<EngineRunView> {
  const form = new FormData();
  form.append('signOffReference', signOffReference);
  form.append('report', report);
  return post<EngineRunView>(`/ifrs17/engine-runs/${encodeURIComponent(id)}/approval`, form);
}

export function rejectEngineRun(id: string, reason: string): Promise<EngineRunView> {
  return post<EngineRunView>(`/ifrs17/engine-runs/${encodeURIComponent(id)}/rejection`, { reason });
}

const exception = (id: string, group: string, figure: string) =>
  `/ifrs17/engine-runs/${encodeURIComponent(id)}/exceptions/${encodeURIComponent(group)}/${encodeURIComponent(figure)}`;

export function explainDifference(id: string, group: string, figure: string, text: string): Promise<EngineRunView> {
  return post<EngineRunView>(`${exception(id, group, figure)}/explanation`, { text });
}

export function acceptDifference(id: string, group: string, figure: string): Promise<EngineRunView> {
  return post<EngineRunView>(`${exception(id, group, figure)}/acceptance`, {});
}
