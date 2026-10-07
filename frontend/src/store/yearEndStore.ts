import { create } from 'zustand';
import {
  approveYearEndClose,
  getYearEndClose,
  listYearEndCloses,
  prepareYearEnd,
  previewYearEnd,
  rejectYearEndClose,
} from '@/api/yearEnd';
import type { YearEndCloseView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The year-end close (IFRS 17 I6): a year's preview and closes, and the close open on screen. Every action runs under its
 * own `acting` slot, so a refusal (a 409 with its reason) shows where it happened.
 */
interface YearEndState {
  preview: Resource<YearEndCloseView>;
  closes: Resource<YearEndCloseView[]>;
  current: Resource<YearEndCloseView>;
  acting: Record<string, Resource<unknown>>;

  loadYear: (year: number) => Promise<void>;
  prepare: (year: number) => Promise<boolean>;
  load: (id: string) => Promise<void>;
  approve: (id: string) => Promise<boolean>;
  reject: (id: string, reason: string) => Promise<boolean>;
}

export const useYearEndStore = create<YearEndState>((set, getState) => {
  const run = async <T>(key: string, call: () => Promise<T>): Promise<T | null> => {
    let result: T | null = null;
    await track(
      `yearEnd.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
        return result;
      },
    );
    return getState().acting[key]?.status === 'success' ? result : null;
  };
  const refresh = (view: YearEndCloseView) =>
    set({ current: { status: 'success', data: view, error: null, loadedAt: Date.now() } });

  return {
    preview: idle(),
    closes: idle(),
    current: idle(),
    acting: {},

    loadYear: async (year) => {
      await Promise.all([
        track('yearEnd.preview', getState().preview, (next) => set({ preview: next }), () => previewYearEnd(year)),
        track('yearEnd.closes', getState().closes, (next) => set({ closes: next }), () => listYearEndCloses(year)),
      ]);
    },

    prepare: async (year) => {
      const done = await run(`prepare.${year}`, () => prepareYearEnd(year));
      if (done) await getState().loadYear(year);
      return done !== null;
    },

    load: (id) => track('yearEnd.current', getState().current, (next) => set({ current: next }), () => getYearEndClose(id)),

    approve: async (id) => {
      const done = await run(`decide.${id}`, () => approveYearEndClose(id));
      if (done) refresh(done);
      return done !== null;
    },

    reject: async (id, reason) => {
      const done = await run(`decide.${id}`, () => rejectYearEndClose(id, reason));
      if (done) refresh(done);
      return done !== null;
    },
  };
});
