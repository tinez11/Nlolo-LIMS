import { create } from 'zustand';
import { getCase, openCase, referCase, submitAssessment } from '@/api/underwriting';
import type { OpenCaseRequest, SubmitAssessmentRequest, UnderwritingCaseView } from '@/api/types';
import { idle, success, track, type Resource } from './createResourceSlice';

/**
 * The `underwriting` domain store.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface UnderwritingState {
  // A single slot, not keyed: opening makes a NEW case, so there is no existing
  // id to key against yet -- same shape as products' `creating`.
  opening: Resource<UnderwritingCaseView>;
  // Keyed by caseId: the detail view, shared by the open-case redirect and any
  // later direct visit to a known case id.
  cases: Keyed<UnderwritingCaseView>;
  // Keyed by caseId: submitting an assessment targets an EXISTING case, and a
  // failed submission on one case must not corrupt another's state.
  submittingAssessment: Keyed<UnderwritingCaseView>;
  referring: Keyed<true>;

  openCase: (request: OpenCaseRequest) => Promise<void>;
  resetOpenCase: () => void;
  loadCase: (caseId: string) => Promise<void>;
  submitAssessment: (caseId: string, request: SubmitAssessmentRequest) => Promise<void>;
  resetSubmitAssessment: (caseId: string) => void;
  referCase: (caseId: string) => Promise<void>;
  resetReferCase: (caseId: string) => void;
}

export const useUnderwritingStore = create<UnderwritingState>((set, getState) => ({
  opening: idle(),
  cases: {},
  submittingAssessment: {},
  referring: {},

  openCase: (request) =>
    track(
      'underwriting.open',
      getState().opening,
      (next) => set({ opening: next }),
      () => openCase(request),
    ),

  resetOpenCase: () => set({ opening: idle() }),

  loadCase: (caseId) =>
    track(
      `underwriting.case.${caseId}`,
      getState().cases[caseId] ?? idle<UnderwritingCaseView>(),
      (next) => set((s) => ({ cases: { ...s.cases, [caseId]: next } })),
      () => getCase(caseId),
    ),

  submitAssessment: (caseId, request) =>
    track(
      `underwriting.assessment.${caseId}`,
      getState().submittingAssessment[caseId] ?? idle<UnderwritingCaseView>(),
      (next) => set((s) => ({ submittingAssessment: { ...s.submittingAssessment, [caseId]: next } })),
      async () => {
        const view = await submitAssessment(caseId, request);
        // The response IS the freshly-decided case (assessmentType/findings in,
        // status/decisionOutcome out) -- write it straight into the detail slot
        // rather than firing a redundant GET for data already in hand.
        set((s) => ({ cases: { ...s.cases, [caseId]: success(view) } }));
        return view;
      },
    ),

  resetSubmitAssessment: (caseId) =>
    set((s) => {
      if (!(caseId in s.submittingAssessment)) return s;
      const { [caseId]: _discard, ...rest } = s.submittingAssessment;
      return { submittingAssessment: rest };
    }),

  referCase: (caseId) =>
    track(
      `underwriting.refer.${caseId}`,
      getState().referring[caseId] ?? idle<true>(),
      (next) => set((s) => ({ referring: { ...s.referring, [caseId]: next } })),
      // Explicit Promise<true>: see productStore.publishVersion for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await referCase(caseId);
        // The referral POST returns no body -- refetch to see referralStatus flip.
        await getState().loadCase(caseId);
        return true;
      },
    ),

  resetReferCase: (caseId) =>
    set((s) => {
      if (!(caseId in s.referring)) return s;
      const { [caseId]: _discard, ...rest } = s.referring;
      return { referring: rest };
    }),
}));

export const selectCase = (caseId: string) => (s: UnderwritingState) =>
  s.cases[caseId] ?? idle<UnderwritingCaseView>();
export const selectSubmittingAssessment = (caseId: string) => (s: UnderwritingState) =>
  s.submittingAssessment[caseId] ?? idle<UnderwritingCaseView>();
export const selectReferring = (caseId: string) => (s: UnderwritingState) =>
  s.referring[caseId] ?? idle<true>();
