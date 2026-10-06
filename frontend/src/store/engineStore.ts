import { create } from 'zustand';
import {
  acceptDifference,
  approveEngineRun,
  createExtract,
  explainDifference,
  getEngineRun,
  listEngineRuns,
  listExtracts,
  rejectEngineRun,
  uploadEngineResults,
} from '@/api/ifrs17Engine';
import type { EngineExtractView, EngineRunView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The IFRS 17 engine period cycle (IFRS 17 I5a): a period's extracts and runs, and the run open on screen. Every action
 * runs under its own `acting` slot and refreshes what it changed, so a refusal (a 409 with its reason, a 422 listing
 * every problem) shows where it happened.
 */
interface EngineState {
  extracts: Resource<EngineExtractView[]>;
  runs: Resource<EngineRunView[]>;
  current: Resource<EngineRunView>;
  acting: Record<string, Resource<unknown>>;

  loadPeriod: (period: string) => Promise<void>;
  load: (runId: string) => Promise<void>;
  createExtract: (period: string) => Promise<boolean>;
  /** Resolves the run (VALIDATED or REJECTED), or null when the upload itself was refused. */
  upload: (period: string, file: File) => Promise<EngineRunView | null>;
  approve: (runId: string, signOffReference: string, report: File) => Promise<boolean>;
  reject: (runId: string, reason: string) => Promise<boolean>;
  explain: (runId: string, group: string, figure: string, text: string) => Promise<boolean>;
  accept: (runId: string, group: string, figure: string) => Promise<boolean>;
}

export const useEngineStore = create<EngineState>((set, getState) => {
  /** Runs one call under acting[key]; resolves its result, or null when it was refused (track() never rethrows). */
  const run = async <T>(key: string, call: () => Promise<T>): Promise<T | null> => {
    let result: T | null = null;
    await track(
      `engine.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
        return result;
      },
    );
    return getState().acting[key]?.status === 'success' ? result : null;
  };
  const refresh = (view: EngineRunView) =>
    set({ current: { status: 'success', data: view, error: null, loadedAt: Date.now() } });

  return {
    extracts: idle(),
    runs: idle(),
    current: idle(),
    acting: {},

    loadPeriod: async (period) => {
      await Promise.all([
        track('engine.extracts', getState().extracts, (next) => set({ extracts: next }), () => listExtracts(period)),
        track('engine.runs', getState().runs, (next) => set({ runs: next }), () => listEngineRuns(period)),
      ]);
    },

    load: (runId) =>
      track('engine.current', getState().current, (next) => set({ current: next }), () => getEngineRun(runId)),

    createExtract: async (period) => {
      const done = await run(`extract.${period}`, () => createExtract(period));
      if (done) await getState().loadPeriod(period);
      return done !== null;
    },

    upload: async (period, file) => {
      const done = await run(`upload.${period}`, () => uploadEngineResults(file));
      if (done) await getState().loadPeriod(period);
      return done;
    },

    approve: async (runId, signOffReference, report) => {
      const done = await run(`run.${runId}`, () => approveEngineRun(runId, signOffReference, report));
      if (done) refresh(done);
      return done !== null;
    },

    reject: async (runId, reason) => {
      const done = await run(`run.${runId}`, () => rejectEngineRun(runId, reason));
      if (done) refresh(done);
      return done !== null;
    },

    explain: async (runId, group, figure, text) => {
      const done = await run(`exception.${runId}.${group}.${figure}`, () => explainDifference(runId, group, figure, text));
      if (done) refresh(done);
      return done !== null;
    },

    accept: async (runId, group, figure) => {
      const done = await run(`exception.${runId}.${group}.${figure}`, () => acceptDifference(runId, group, figure));
      if (done) refresh(done);
      return done !== null;
    },
  };
});
