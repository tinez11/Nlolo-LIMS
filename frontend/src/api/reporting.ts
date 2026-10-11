import { get, post } from '@/lib/http';
import type { components } from '@/types/api/reporting';

/**
 * Management reports (backend `reporting` module). Every report returns one shape -- sections of typed columns
 * whose values are all strings -- so one page renders all of them, and an export is the same report as a file.
 */

export type ReportDefinition = components['schemas']['ReportDefinition'];
export type ReportResult = components['schemas']['ReportResult'];
export type ReportSection = components['schemas']['ReportSection'];
export type ReportColumn = components['schemas']['ReportColumn'];
export type RebuildStatus = components['schemas']['RebuildStatus'];
export type ReportParams = Record<string, string>;
export type ExportFormat = 'xlsx' | 'csv' | 'pdf';

const ACCEPT: Record<ExportFormat, string> = {
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  csv: 'text/csv',
  pdf: 'application/pdf',
};

export function getCatalogue(): Promise<ReportDefinition[]> {
  return get<ReportDefinition[]>('/reports/catalogue');
}

export function runReport(key: string, params: ReportParams): Promise<ReportResult> {
  return get<ReportResult>(`/reports/${encodeURIComponent(key)}`, { params });
}

export function exportReport(key: string, params: ReportParams, format: ExportFormat): Promise<Blob> {
  return get<Blob>(`/reports/${encodeURIComponent(key)}/export`, {
    params: { ...params, format },
    responseType: 'blob',
    headers: { Accept: ACCEPT[format] },
  });
}

/** ADMIN only. Re-derives the tenant's reporting data from the event journal, in the background. */
export function startRebuild(): Promise<RebuildStatus> {
  return post<RebuildStatus>('/reports/rebuild', {});
}

export function getRebuildStatus(): Promise<RebuildStatus> {
  return get<RebuildStatus>('/reports/rebuild/status');
}
