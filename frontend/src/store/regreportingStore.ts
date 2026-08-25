import { create } from 'zustand';
import { generateReturn, getReturn, listReturns } from '@/api/regreporting';
import type { GenerateReturnRequest, RegulatoryReturnView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `regreporting` domain store.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface RegreportingState {
  list: Resource<RegulatoryReturnView[]>;
  detail: Keyed<RegulatoryReturnView>;
  // A single slot, not keyed: generating targets a (returnType, period) pair
  // that may not have an existing row yet -- same shape as treaties' `creating`.
  // Regenerating an existing pair replaces its lines server-side (idempotent
  // by design, per RegreportingApi.generateReturn's own doc), not an error.
  generating: Resource<RegulatoryReturnView>;

  loadList: (period?: string) => Promise<void>;
  loadDetail: (returnId: string) => Promise<void>;
  generateReturn: (request: GenerateReturnRequest) => Promise<void>;
  resetGenerateReturn: () => void;
}

export const useRegreportingStore = create<RegreportingState>((set, getState) => ({
  list: idle(),
  detail: {},
  generating: idle(),

  loadList: (period) =>
    track(
      `regreporting.list.${period ?? ''}`,
      getState().list,
      (next) => set({ list: next }),
      () => listReturns(period),
    ),

  loadDetail: (returnId) =>
    track(
      `regreporting.detail.${returnId}`,
      getState().detail[returnId] ?? idle<RegulatoryReturnView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [returnId]: next } })),
      () => getReturn(returnId),
    ),

  generateReturn: (request) =>
    track(
      'regreporting.generate',
      getState().generating,
      (next) => set({ generating: next }),
      async () => {
        const created = await generateReturn(request);
        // The list shows every return -- refresh it so a newly generated one
        // (or a regenerated one's updated lines) appears without a manual reload.
        await getState().loadList();
        return created;
      },
    ),

  resetGenerateReturn: () => set({ generating: idle() }),
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectReturnDetail = (returnId: string) => (s: RegreportingState) =>
  s.detail[returnId] ?? idle<RegulatoryReturnView>();
