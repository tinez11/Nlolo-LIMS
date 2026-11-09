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
export type ReportFigure = components['schemas']['ReportFigure'];
export type ReportChart = components['schemas']['ReportChart'];
export type ReportParts = components['schemas']['ReportParts'];
export type ReportParams = Record<string, string>;

/**
 * What one line of a report is made of -- the computation tree (2026-11-09). The same filters as the report; `page`
 * pages a leaf account's postings.
 */
export function getReportParts(key: string, lineId: string, params: ReportParams, page = 0): Promise<ReportParts> {
  return get<ReportParts>(`/reports/${encodeURIComponent(key)}/parts`, { params: { ...params, lineId, page: String(page) } });
}
export type ExportFormat = 'xlsx' | 'csv' | 'pdf';

const ACCEPT: Record<ExportFormat, string> = {
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  // A report with several tables comes back as a zip of CSVs.
  csv: 'text/csv, application/zip',
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

export type SolvencyFactorSet = components['schemas']['SolvencyFactorSet'];
export type NewSolvencyFactorSet = components['schemas']['NewSolvencyFactorSet'];
export type SolvencyFactors = components['schemas']['SolvencyFactors'];

/** Every version of the solvency factors, newest first, ending with the built-in placeholder set (2026-11-09). */
export function listSolvencyFactorSets(): Promise<SolvencyFactorSet[]> {
  return get<SolvencyFactorSet[]>('/reports/settings/solvency-factors');
}

/** Publishes a new version; a version is never edited. */
export function publishSolvencyFactorSet(set: NewSolvencyFactorSet): Promise<SolvencyFactorSet> {
  return post<SolvencyFactorSet>('/reports/settings/solvency-factors', set);
}
