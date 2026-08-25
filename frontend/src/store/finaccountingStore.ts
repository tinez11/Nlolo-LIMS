import { create } from 'zustand';
import {
  createAccount,
  deleteAccount,
  getGlPosting,
  listChartOfAccounts,
  listGlPostings,
  renameAccount,
  type GlPostingSearchParams,
} from '@/api/finaccounting';
import type {
  ChartOfAccountView,
  CreateAccountRequest,
  JournalEntryView,
  Page,
  RenameAccountRequest,
} from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `finaccounting` domain store. Journal entries/GL postings stay
 * read-only. Chart-of-accounts create/rename/delete are mutations, so each
 * gets its own tracked resource -- same shape as productStore's
 * creating/publishing.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface FinaccountingState {
  list: Resource<Page<JournalEntryView>>;
  detail: Keyed<JournalEntryView>;
  chartOfAccounts: Resource<ChartOfAccountView[]>;
  // A single slot, not keyed: creation makes a NEW account, so there is no
  // existing accountCode to key against yet -- same shape as products' `creating`.
  creating: Resource<ChartOfAccountView>;
  // Keyed by accountCode: renaming/deleting each target an EXISTING account,
  // and a failed mutation on one account must not corrupt another's state.
  renaming: Keyed<ChartOfAccountView>;
  deleting: Keyed<true>;

  loadList: (params: GlPostingSearchParams) => Promise<void>;
  loadDetail: (journalEntryId: string) => Promise<void>;
  loadChartOfAccounts: () => Promise<void>;
  createAccount: (request: CreateAccountRequest) => Promise<void>;
  resetCreateAccount: () => void;
  renameAccount: (accountCode: string, request: RenameAccountRequest) => Promise<void>;
  resetRenameAccount: (accountCode: string) => void;
  deleteAccount: (accountCode: string) => Promise<void>;
  resetDeleteAccount: (accountCode: string) => void;
}

export const useFinaccountingStore = create<FinaccountingState>((set, getState) => ({
  list: idle(),
  detail: {},
  chartOfAccounts: idle(),
  creating: idle(),
  renaming: {},
  deleting: {},

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

  createAccount: (request) =>
    track(
      'finaccounting.createAccount',
      getState().creating,
      (next) => set({ creating: next }),
      async () => {
        const created = await createAccount(request);
        await getState().loadChartOfAccounts();
        return created;
      },
    ),

  resetCreateAccount: () => set({ creating: idle() }),

  renameAccount: (accountCode, request) =>
    track(
      `finaccounting.renameAccount.${accountCode}`,
      getState().renaming[accountCode] ?? idle<ChartOfAccountView>(),
      (next) => set((s) => ({ renaming: { ...s.renaming, [accountCode]: next } })),
      async () => {
        const renamed = await renameAccount(accountCode, request);
        await getState().loadChartOfAccounts();
        return renamed;
      },
    ),

  resetRenameAccount: (accountCode) =>
    set((s) => {
      if (!(accountCode in s.renaming)) return s;
      const { [accountCode]: _discard, ...rest } = s.renaming;
      return { renaming: rest };
    }),

  deleteAccount: (accountCode) =>
    track(
      `finaccounting.deleteAccount.${accountCode}`,
      getState().deleting[accountCode] ?? idle<true>(),
      (next) => set((s) => ({ deleting: { ...s.deleting, [accountCode]: next } })),
      // Explicit Promise<true>: see policyStore.saveBeneficiaries for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await deleteAccount(accountCode);
        await getState().loadChartOfAccounts();
        return true;
      },
    ),

  resetDeleteAccount: (accountCode) =>
    set((s) => {
      if (!(accountCode in s.deleting)) return s;
      const { [accountCode]: _discard, ...rest } = s.deleting;
      return { deleting: rest };
    }),
}));

export const selectJournalEntryDetail = (journalEntryId: string) => (s: FinaccountingState) =>
  s.detail[journalEntryId] ?? idle<JournalEntryView>();
export const selectRenamingAccount = (accountCode: string) => (s: FinaccountingState) =>
  s.renaming[accountCode] ?? idle<ChartOfAccountView>();
export const selectDeletingAccount = (accountCode: string) => (s: FinaccountingState) =>
  s.deleting[accountCode] ?? idle<true>();
