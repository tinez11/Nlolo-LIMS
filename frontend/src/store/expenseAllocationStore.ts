import { create } from 'zustand';
import {
  approveAllocation,
  getAllocation,
  listAllocations,
  prepareAllocation,
  previewAllocation,
  rejectAllocation,
} from '@/api/ifrs17Engine';
import type { ExpenseAllocationInput, ExpenseAllocationPreview, ExpenseAllocationView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * P-19, the month's expense allocation (IFRS 17 I5b, month-end step 5): a period's allocations, the live preview of the
 * totals being typed, and the allocation open on screen. Every action runs under its own `acting` slot, so a refusal (a
 * 409 with its reason, a 422) shows where it happened.
 */
interface ExpenseAllocationState {
  allocations: Resource<ExpenseAllocationView[]>;
  preview: Resource<ExpenseAllocationPreview>;
  current: Resource<ExpenseAllocationView>;
  acting: Record<string, Resource<unknown>>;

  loadPeriod: (period: string) => Promise<void>;
  previewTotals: (period: string, maintenance: number, claimsHandling: number, acquisition: number) => Promise<void>;
  clearPreview: () => void;
  prepare: (period: string, input: ExpenseAllocationInput) => Promise<boolean>;
  load: (id: string) => Promise<void>;
  approve: (id: string, aboveThePool: boolean) => Promise<boolean>;
  reject: (id: string, reason: string) => Promise<boolean>;
}

export const useExpenseAllocationStore = create<ExpenseAllocationState>((set, getState) => {
  /** Runs one call under acting[key]; resolves its result, or null when it was refused (track() never rethrows). */
  const run = async <T>(key: string, call: () => Promise<T>): Promise<T | null> => {
    let result: T | null = null;
    await track(
      `allocation.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        result = await call();
        return result;
      },
    );
    return getState().acting[key]?.status === 'success' ? result : null;
  };
  const refresh = (view: ExpenseAllocationView) =>
    set({ current: { status: 'success', data: view, error: null, loadedAt: Date.now() } });

  return {
    allocations: idle(),
    preview: idle(),
    current: idle(),
    acting: {},

    loadPeriod: (period) =>
      track('allocation.list', getState().allocations, (next) => set({ allocations: next }), () =>
        listAllocations(period),
      ),

    previewTotals: (period, maintenance, claimsHandling, acquisition) =>
      track('allocation.preview', getState().preview, (next) => set({ preview: next }), () =>
        previewAllocation(period, maintenance, claimsHandling, acquisition),
      ),

    clearPreview: () => set({ preview: idle() }),

    prepare: async (period, input) => {
      const done = await run(`prepare.${period}`, () => prepareAllocation(period, input));
      if (done) {
        set({ preview: idle() });
        await getState().loadPeriod(period);
      }
      return done !== null;
    },

    load: (id) => track('allocation.current', getState().current, (next) => set({ current: next }), () => getAllocation(id)),

    approve: async (id, aboveThePool) => {
      const done = await run(`decide.${id}`, () => approveAllocation(id, aboveThePool));
      if (done) refresh(done);
      return done !== null;
    },

    reject: async (id, reason) => {
      const done = await run(`decide.${id}`, () => rejectAllocation(id, reason));
      if (done) refresh(done);
      return done !== null;
    },
  };
});
