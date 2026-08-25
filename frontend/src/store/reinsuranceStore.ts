import { create } from 'zustand';
import {
  confirmRecovery,
  createTreaty,
  getTreaty,
  listCessionsForPolicy,
  listRecoveriesForClaim,
  listTreaties,
} from '@/api/reinsurance';
import type {
  CessionView,
  ClaimRecoveryView,
  CreateTreatyRequest,
  TreatyStatus,
  TreatyView,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, success, track, type Resource } from './createResourceSlice';

/**
 * The `reinsurance` domain store.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface ReinsuranceState {
  list: Resource<TreatyView[]>;
  detail: Keyed<TreatyView>;
  // A single slot, not keyed: creation makes a NEW treaty, so there is no
  // existing id to key against yet -- same shape as products' `creating`.
  creating: Resource<TreatyView>;
  cessions: Keyed<CessionView[]>;
  recoveries: Keyed<ClaimRecoveryView[]>;
  confirmingRecovery: Keyed<true>;

  loadList: (status?: TreatyStatus) => Promise<void>;
  loadDetail: (treatyId: string) => Promise<void>;
  createTreaty: (request: CreateTreatyRequest, attempt: MutationAttempt) => Promise<void>;
  resetCreateTreaty: () => void;
  loadCessions: (policyNumber: string) => Promise<void>;
  loadRecoveries: (claimId: string) => Promise<void>;
  confirmRecovery: (claimId: string, recoveryId: string, attempt: MutationAttempt) => Promise<void>;
  resetConfirmRecovery: (recoveryId: string) => void;
}

export const useReinsuranceStore = create<ReinsuranceState>((set, getState) => ({
  list: idle(),
  detail: {},
  creating: idle(),
  cessions: {},
  recoveries: {},
  confirmingRecovery: {},

  loadList: (status) =>
    track(
      `reinsurance.list.${status ?? ''}`,
      getState().list,
      (next) => set({ list: next }),
      () => listTreaties(status),
    ),

  loadDetail: (treatyId) =>
    track(
      `reinsurance.detail.${treatyId}`,
      getState().detail[treatyId] ?? idle<TreatyView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [treatyId]: next } })),
      () => getTreaty(treatyId),
    ),

  createTreaty: (request, attempt) =>
    track(
      'reinsurance.create',
      getState().creating,
      (next) => set({ creating: next }),
      async () => {
        const view = await createTreaty(request, attempt);
        // The response IS the newly-created treaty -- write it straight into
        // the detail slot rather than firing a redundant GET right after.
        set((s) => ({ detail: { ...s.detail, [view.treatyId]: success(view) } }));
        return view;
      },
    ),

  resetCreateTreaty: () => set({ creating: idle() }),

  loadCessions: (policyNumber) =>
    track(
      `reinsurance.cessions.${policyNumber}`,
      getState().cessions[policyNumber] ?? idle<CessionView[]>(),
      (next) => set((s) => ({ cessions: { ...s.cessions, [policyNumber]: next } })),
      () => listCessionsForPolicy(policyNumber),
    ),

  loadRecoveries: (claimId) =>
    track(
      `reinsurance.recoveries.${claimId}`,
      getState().recoveries[claimId] ?? idle<ClaimRecoveryView[]>(),
      (next) => set((s) => ({ recoveries: { ...s.recoveries, [claimId]: next } })),
      () => listRecoveriesForClaim(claimId),
    ),

  confirmRecovery: (claimId, recoveryId, attempt) =>
    track(
      `reinsurance.confirm.${recoveryId}`,
      getState().confirmingRecovery[recoveryId] ?? idle<true>(),
      (next) => set((s) => ({ confirmingRecovery: { ...s.confirmingRecovery, [recoveryId]: next } })),
      // Explicit Promise<true>: see productStore.publishVersion for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await confirmRecovery(claimId, recoveryId, attempt);
        // The recoveries list is what the UI actually renders -- refetch it so
        // this recovery's confirmedAt shows without a manual reload.
        await getState().loadRecoveries(claimId);
        return true;
      },
    ),

  resetConfirmRecovery: (recoveryId) =>
    set((s) => {
      if (!(recoveryId in s.confirmingRecovery)) return s;
      const { [recoveryId]: _discard, ...rest } = s.confirmingRecovery;
      return { confirmingRecovery: rest };
    }),
}));

export const selectTreatyDetail = (treatyId: string) => (s: ReinsuranceState) =>
  s.detail[treatyId] ?? idle<TreatyView>();
export const selectCessions = (policyNumber: string) => (s: ReinsuranceState) =>
  s.cessions[policyNumber] ?? idle<CessionView[]>();
export const selectRecoveries = (claimId: string) => (s: ReinsuranceState) =>
  s.recoveries[claimId] ?? idle<ClaimRecoveryView[]>();
export const selectConfirmingRecovery = (recoveryId: string) => (s: ReinsuranceState) =>
  s.confirmingRecovery[recoveryId] ?? idle<true>();
