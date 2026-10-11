import { create } from 'zustand';
import { getCatalogue, runReport, type ReportDefinition, type ReportParams, type ReportResult } from '@/api/reporting';
import { idle, track, type Resource } from './createResourceSlice';

/** The `reporting` domain store. One result slot: a single report page is open at a time, and track()'s
 * anti-clobber guard (constant key) keeps a slow earlier run from overwriting a newer one. */
interface ReportingState {
  catalogue: Resource<ReportDefinition[]>;
  result: Resource<ReportResult>;
  loadCatalogue: () => Promise<void>;
  runReport: (key: string, params: ReportParams) => Promise<void>;
}

export const useReportingStore = create<ReportingState>((set, getState) => ({
  catalogue: idle(),
  result: idle(),

  loadCatalogue: () =>
    track('reporting.catalogue', getState().catalogue, (next) => set({ catalogue: next }), () => getCatalogue()),

  runReport: (key, params) =>
    track('reporting.result', getState().result, (next) => set({ result: next }), () => runReport(key, params)),
}));
