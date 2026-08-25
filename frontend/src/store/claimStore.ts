import { create } from 'zustand';
import {
  attachClaimEvidence,
  decideSettlement,
  getClaim,
  listClaimEvidence,
  registerClaim,
  reopenClaim,
  searchClaims,
  submitClaimAssessment,
  type ClaimSearchParams,
} from '@/api/claims';
import type {
  ClaimAssessmentView,
  ClaimEvidenceView,
  ClaimView,
  Page,
  RegisterClaimRequest,
  ReopenClaimRequest,
  SettlementDecisionRequest,
  SubmitClaimAssessmentRequest,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, success, track, type Resource } from './createResourceSlice';

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
  // All three below are keyed by claimId: each targets an EXISTING claim, and a
  // failed action on one claim must not corrupt another's state.
  submittingAssessment: Keyed<ClaimAssessmentView>;
  decidingSettlement: Keyed<ClaimView>;
  reopening: Keyed<ClaimView>;
  evidence: Keyed<ClaimEvidenceView[]>;
  // Keyed by claimId, not by any per-file id: only one upload is ever in flight
  // for a given claim's panel at a time (the form disables itself while
  // submitting), so there is nothing a second key would disambiguate.
  attachingEvidence: Keyed<true>;

  loadList: (params: ClaimSearchParams) => Promise<void>;
  loadDetail: (claimId: string) => Promise<void>;
  loadEvidence: (claimId: string) => Promise<void>;
  registerClaim: (request: RegisterClaimRequest, attempt: MutationAttempt) => Promise<void>;
  /** Clears a stale registration error before a fresh attempt -- see the call site. */
  resetRegisterClaim: () => void;
  submitAssessment: (claimId: string, request: SubmitClaimAssessmentRequest) => Promise<void>;
  resetSubmitAssessment: (claimId: string) => void;
  decideSettlement: (
    claimId: string,
    request: SettlementDecisionRequest,
    attempt: MutationAttempt,
  ) => Promise<void>;
  resetDecideSettlement: (claimId: string) => void;
  reopenClaim: (claimId: string, request: ReopenClaimRequest) => Promise<void>;
  resetReopenClaim: (claimId: string) => void;
  attachEvidence: (claimId: string, file: File, description?: string) => Promise<void>;
  resetAttachEvidence: (claimId: string) => void;
}

const REGISTER_KEY = 'claim.register';

export const useClaimStore = create<ClaimState>((set, getState) => ({
  list: idle(),
  detail: {},
  registering: idle(),
  submittingAssessment: {},
  decidingSettlement: {},
  reopening: {},
  evidence: {},
  attachingEvidence: {},

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

  loadEvidence: (claimId) =>
    track(
      `claim.evidence.${claimId}`,
      getState().evidence[claimId] ?? idle<ClaimEvidenceView[]>(),
      (next) => set((s) => ({ evidence: { ...s.evidence, [claimId]: next } })),
      () => listClaimEvidence(claimId),
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

  submitAssessment: (claimId, request) =>
    track(
      `claim.submitAssessment.${claimId}`,
      getState().submittingAssessment[claimId] ?? idle<ClaimAssessmentView>(),
      (next) => set((s) => ({ submittingAssessment: { ...s.submittingAssessment, [claimId]: next } })),
      async () => {
        const assessment = await submitClaimAssessment(claimId, request);
        // The response is the assessment record, not the claim -- refetch so the
        // status flip (REGISTERED/REOPENED -> UNDER_ASSESSMENT) shows in `detail`.
        await getState().loadDetail(claimId);
        return assessment;
      },
    ),

  resetSubmitAssessment: (claimId) =>
    set((s) => {
      if (!(claimId in s.submittingAssessment)) return s;
      const { [claimId]: _discard, ...rest } = s.submittingAssessment;
      return { submittingAssessment: rest };
    }),

  decideSettlement: (claimId, request, attempt) =>
    track(
      `claim.decideSettlement.${claimId}`,
      getState().decidingSettlement[claimId] ?? idle<ClaimView>(),
      (next) => set((s) => ({ decidingSettlement: { ...s.decidingSettlement, [claimId]: next } })),
      async () => {
        const view = await decideSettlement(claimId, request, attempt);
        // The response IS the freshly-decided claim -- write it straight into the
        // detail slot rather than firing a redundant GET for data already in hand.
        set((s) => ({ detail: { ...s.detail, [claimId]: success(view) } }));
        return view;
      },
    ),

  resetDecideSettlement: (claimId) =>
    set((s) => {
      if (!(claimId in s.decidingSettlement)) return s;
      const { [claimId]: _discard, ...rest } = s.decidingSettlement;
      return { decidingSettlement: rest };
    }),

  reopenClaim: (claimId, request) =>
    track(
      `claim.reopen.${claimId}`,
      getState().reopening[claimId] ?? idle<ClaimView>(),
      (next) => set((s) => ({ reopening: { ...s.reopening, [claimId]: next } })),
      async () => {
        const view = await reopenClaim(claimId, request);
        set((s) => ({ detail: { ...s.detail, [claimId]: success(view) } }));
        return view;
      },
    ),

  resetReopenClaim: (claimId) =>
    set((s) => {
      if (!(claimId in s.reopening)) return s;
      const { [claimId]: _discard, ...rest } = s.reopening;
      return { reopening: rest };
    }),

  attachEvidence: (claimId, file, description) =>
    track(
      `claim.attachEvidence.${claimId}`,
      getState().attachingEvidence[claimId] ?? idle<true>(),
      (next) => set((s) => ({ attachingEvidence: { ...s.attachingEvidence, [claimId]: next } })),
      // Explicit Promise<true>: see policyStore.saveBeneficiaries for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await attachClaimEvidence(claimId, file, description);
        await getState().loadEvidence(claimId);
        return true;
      },
    ),

  resetAttachEvidence: (claimId) =>
    set((s) => {
      if (!(claimId in s.attachingEvidence)) return s;
      const { [claimId]: _discard, ...rest } = s.attachingEvidence;
      return { attachingEvidence: rest };
    }),
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectClaimDetail = (claimId: string) => (s: ClaimState) =>
  s.detail[claimId] ?? idle<ClaimView>();
export const selectSubmittingAssessment = (claimId: string) => (s: ClaimState) =>
  s.submittingAssessment[claimId] ?? idle<ClaimAssessmentView>();
export const selectDecidingSettlement = (claimId: string) => (s: ClaimState) =>
  s.decidingSettlement[claimId] ?? idle<ClaimView>();
export const selectReopening = (claimId: string) => (s: ClaimState) =>
  s.reopening[claimId] ?? idle<ClaimView>();
export const selectEvidence = (claimId: string) => (s: ClaimState) =>
  s.evidence[claimId] ?? idle<ClaimEvidenceView[]>();
export const selectAttachingEvidence = (claimId: string) => (s: ClaimState) =>
  s.attachingEvidence[claimId] ?? idle<true>();
