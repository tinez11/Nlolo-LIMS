import { create } from 'zustand';
import {
  createTreaty,
  getBordereau,
  getTreaty,
  getTreatyUtilisation,
  listBordereaux,
  listCessionsForPolicy,
  listCessionsForTreaty,
  listRecoveriesForClaim,
  listTreaties,
  previewBordereau,
} from '@/api/reinsurance';
import type {
  BordereauView,
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
  /** IFRS 17 I3c -- keyed by treaty: its written months, and the current month's preview. */
  bordereaux: Keyed<BordereauView[]>;
  bordereauPreview: Keyed<BordereauView>;
  /** Keyed by bordereau id: one month with its lines. */
  bordereau: Keyed<BordereauView>;

  loadList: (status?: TreatyStatus) => Promise<void>;
  loadDetail: (treatyId: string) => Promise<void>;
  createTreaty: (request: CreateTreatyRequest, attempt: MutationAttempt) => Promise<void>;
  resetCreateTreaty: () => void;
  loadCessions: (policyNumber: string) => Promise<void>;
  loadTreatyCessions: (treatyId: string, page?: number) => Promise<void>;
  loadTreatyUtilisation: (treatyId: string) => Promise<void>;
  loadRecoveries: (claimId: string) => Promise<void>;
  loadBordereaux: (treatyId: string) => Promise<void>;
  loadBordereauPreview: (treatyId: string) => Promise<void>;
  loadBordereau: (bordereauId: string) => Promise<void>;
}

export const useReinsuranceStore = create<ReinsuranceState>((set, getState) => ({
  list: idle(),
  detail: {},
  creating: idle(),
  cessions: {},
  treatyCessions: {},
  treatyUtilisation: {},
  recoveries: {},
  bordereaux: {},
  bordereauPreview: {},
  bordereau: {},

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

  loadBordereaux: (treatyId) =>
    track(
      `reinsurance.bordereaux.${treatyId}`,
      getState().bordereaux[treatyId] ?? idle<BordereauView[]>(),
      (next) => set((s) => ({ bordereaux: { ...s.bordereaux, [treatyId]: next } })),
      () => listBordereaux(treatyId),
    ),

  loadBordereauPreview: (treatyId) =>
    track(
      `reinsurance.bordereauPreview.${treatyId}`,
      getState().bordereauPreview[treatyId] ?? idle<BordereauView>(),
      (next) => set((s) => ({ bordereauPreview: { ...s.bordereauPreview, [treatyId]: next } })),
      () => previewBordereau(treatyId),
    ),

  loadBordereau: (bordereauId) =>
    track(
      `reinsurance.bordereau.${bordereauId}`,
      getState().bordereau[bordereauId] ?? idle<BordereauView>(),
      (next) => set((s) => ({ bordereau: { ...s.bordereau, [bordereauId]: next } })),
      () => getBordereau(bordereauId),
    ),
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
export const selectBordereaux = (treatyId: string) => (s: ReinsuranceState) =>
  s.bordereaux[treatyId] ?? idle<BordereauView[]>();
export const selectBordereauPreview = (treatyId: string) => (s: ReinsuranceState) =>
  s.bordereauPreview[treatyId] ?? idle<BordereauView>();
export const selectBordereau = (bordereauId: string) => (s: ReinsuranceState) =>
  s.bordereau[bordereauId] ?? idle<BordereauView>();
