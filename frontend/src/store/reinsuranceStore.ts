import { create } from 'zustand';
import {
  confirmRecovery,
  createTreaty,
  getTreaty,
  getTreatyUtilisation,
  listCessionsForPolicy,
  listCessionsForTreaty,
  listRecoveriesForClaim,
  listTreaties,
} from '@/api/reinsurance';
import type {
  CessionView,
  Page,
  TreatyUtilisationView,
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
  /** Keyed by TREATY, not policy: "what has this reinsurer taken" is a different question. */
  treatyCessions: Keyed<Page<CessionView>>;
  treatyUtilisation: Keyed<TreatyUtilisationView>;
  recoveries: Keyed<ClaimRecoveryView[]>;
  confirmingRecovery: Keyed<true>;

  loadList: (status?: TreatyStatus) => Promise<void>;
  loadDetail: (treatyId: string) => Promise<void>;
  createTreaty: (request: CreateTreatyRequest, attempt: MutationAttempt) => Promise<void>;
  resetCreateTreaty: () => void;
  loadCessions: (policyNumber: string) => Promise<void>;
  loadTreatyCessions: (treatyId: string, page?: number) => Promise<void>;
  loadTreatyUtilisation: (treatyId: string) => Promise<void>;
  loadRecoveries: (claimId: string) => Promise<void>;
  confirmRecovery: (claimId: string, recoveryId: string, attempt: MutationAttempt) => Promise<void>;
  resetConfirmRecovery: (recoveryId: string) => void;
}

export const useReinsuranceStore = create<ReinsuranceState>((set, getState) => ({
  list: idle(),
  detail: {},
  creating: idle(),
  cessions: {},
  treatyCessions: {},
  treatyUtilisation: {},
  recoveries: {},
  confirmingRecovery: {},

  // The key is constant regardless of which status filter was requested --
  // see policyStore.loadList's identical comment: `list` is a single
  // non-keyed slot, so only the most recently REQUESTED filter may win.
  // Varying the key by status here would defeat track()'s anti-clobber
  // guard for exactly the "switch the filter quickly" race it exists to
  // prevent (found in the whole-portal review, 2026-08-25).
  loadList: (status) =>
    track(
      'reinsurance.list',
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

  // Keyed by treaty, separately from `cessions` (keyed by POLICY): the two answer different
  // questions -- "what was ceded on this contract" and "what has this reinsurer taken" -- and
  // one slot would let a policy's cessions render under a treaty's heading.
  loadTreatyCessions: (treatyId, page = 0) =>
    track(
      `reinsurance.treatyCessions.${treatyId}`,
      getState().treatyCessions[treatyId] ?? idle<Page<CessionView>>(),
      (next) => set((s) => ({ treatyCessions: { ...s.treatyCessions, [treatyId]: next } })),
      () => listCessionsForTreaty(treatyId, page),
    ),

  loadTreatyUtilisation: (treatyId) =>
    track(
      `reinsurance.treatyUtilisation.${treatyId}`,
      getState().treatyUtilisation[treatyId] ?? idle<TreatyUtilisationView>(),
      (next) => set((s) => ({ treatyUtilisation: { ...s.treatyUtilisation, [treatyId]: next } })),
      () => getTreatyUtilisation(treatyId),
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
export const selectTreatyCessions = (treatyId: string) => (s: ReinsuranceState) =>
  s.treatyCessions[treatyId] ?? idle<Page<CessionView>>();
export const selectTreatyUtilisation = (treatyId: string) => (s: ReinsuranceState) =>
  s.treatyUtilisation[treatyId] ?? idle<TreatyUtilisationView>();
export const selectRecoveries = (claimId: string) => (s: ReinsuranceState) =>
  s.recoveries[claimId] ?? idle<ClaimRecoveryView[]>();
export const selectConfirmingRecovery = (recoveryId: string) => (s: ReinsuranceState) =>
  s.confirmingRecovery[recoveryId] ?? idle<true>();
