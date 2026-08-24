import { create } from 'zustand';
import { getClaim, registerClaim, searchClaims, type ClaimSearchParams } from '@/api/claims';
import type { ClaimView, Page, RegisterClaimRequest } from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `claims` domain store. One store per backend module, mirroring the
 * Modulith boundaries -- see policyStore.ts for the fuller rationale.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface ClaimState {
  list: Resource<Page<ClaimView>>;
  detail: Keyed<ClaimView>;
  // A single slot, not keyed by id: registration creates a NEW claim, so there is
  // no existing entity to key against yet. Deliberately separate from `detail`
  // for the same reason saveBeneficiaries stays separate from policy `detail` --
  // a failed registration must not corrupt any already-loaded claim.
  registering: Resource<ClaimView>;

  loadList: (params: ClaimSearchParams) => Promise<void>;
  loadDetail: (claimId: string) => Promise<void>;
  registerClaim: (request: RegisterClaimRequest, attempt: MutationAttempt) => Promise<void>;
  /** Clears a stale registration error before a fresh attempt -- see the call site. */
  resetRegisterClaim: () => void;
}

const REGISTER_KEY = 'claim.register';

export const useClaimStore = create<ClaimState>((set, getState) => ({
  list: idle(),
  detail: {},
  registering: idle(),

  loadList: (params) =>
    track(
      'claim.list',
      getState().list,
      (next) => set({ list: next }),
      () => searchClaims(params),
    ),

  loadDetail: (claimId) =>
    track(
      `claim.detail.${claimId}`,
      getState().detail[claimId] ?? idle<ClaimView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [claimId]: next } })),
      () => getClaim(claimId),
    ),

  registerClaim: (request, attempt) =>
    track(
      REGISTER_KEY,
      getState().registering,
      (next) => set({ registering: next }),
      () => registerClaim(request, attempt),
    ),

  // See policyStore.resetSaveBeneficiaries -- an e2e test on that feature caught a
  // real bug where a stale error resurfaced on reopening the form, so this store
  // is built with the reset from the start rather than discovering the same class
  // of bug a second time.
  resetRegisterClaim: () => set({ registering: idle() }),
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectClaimDetail = (claimId: string) => (s: ClaimState) =>
  s.detail[claimId] ?? idle<ClaimView>();
