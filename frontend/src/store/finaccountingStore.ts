import { create } from 'zustand';
import {
  getGlPosting,
  listChartOfAccounts,
  listGlPostings,
  type GlPostingSearchParams,
} from '@/api/finaccounting';
import type { ChartOfAccountView, JournalEntryView, Page } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `finaccounting` domain store. Entirely read-only -- there is no write
 * action anywhere in this module, by design.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface FinaccountingState {
  list: Resource<Page<JournalEntryView>>;
  detail: Keyed<JournalEntryView>;
  chartOfAccounts: Resource<ChartOfAccountView[]>;

  loadList: (params: GlPostingSearchParams) => Promise<void>;
  loadDetail: (journalEntryId: string) => Promise<void>;
  loadChartOfAccounts: () => Promise<void>;
}

export const useFinaccountingStore = create<FinaccountingState>((set, getState) => ({
  list: idle(),
  detail: {},
  chartOfAccounts: idle(),

  loadList: (params) =>
    track(
      'finaccounting.list',
      getState().list,
      (next) => set({ list: next }),
      () => listGlPostings(params),
    ),

  loadDetail: (journalEntryId) =>
    track(
      `finaccounting.detail.${journalEntryId}`,
      getState().detail[journalEntryId] ?? idle<JournalEntryView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [journalEntryId]: next } })),
      () => getGlPosting(journalEntryId),
    ),

  loadChartOfAccounts: () =>
    track(
      'finaccounting.chartOfAccounts',
      getState().chartOfAccounts,
      (next) => set({ chartOfAccounts: next }),
      () => listChartOfAccounts(),
    ),
}));

export const selectJournalEntryDetail = (journalEntryId: string) => (s: FinaccountingState) =>
  s.detail[journalEntryId] ?? idle<JournalEntryView>();
