import { create } from 'zustand';
import {
  decide,
  getCase,
  listCases,
  listDisclosures,
  openCase,
  recordDisclosures,
  referCase,
  submitAssessment,
} from '@/api/underwriting';
import type { UnderwritingListParams } from '@/api/underwriting';
import type {
  DecideRequest,
  MedicalDisclosureView,
  OpenCaseRequest,
  Page,
  RecordDisclosuresRequest,
  SubmitAssessmentRequest,
  UnderwritingCaseView,
} from '@/api/types';
import { idle, success, track, type Resource } from './createResourceSlice';

/**
 * The `underwriting` domain store.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface UnderwritingState {
  list: Resource<Page<UnderwritingCaseView>>;
  // A single slot, not keyed: opening makes a NEW case, so there is no existing
  // id to key against yet -- same shape as products' `creating`.
  opening: Resource<UnderwritingCaseView>;
  // Keyed by caseId: the detail view, shared by the open-case redirect and any
  // later direct visit to a known case id.
  cases: Keyed<UnderwritingCaseView>;
  // Keyed by caseId: submitting an assessment targets an EXISTING case, and a
  // failed submission on one case must not corrupt another's state.
  submittingAssessment: Keyed<UnderwritingCaseView>;
  // Keyed by caseId, and kept apart from submittingAssessment: an assessment and a
  // decision are two different acts on the same case now, and a rejected decision must
  // not clear the evidence panel or vice versa.
  deciding: Keyed<UnderwritingCaseView>;
  referring: Keyed<true>;
  // Read and write kept in separate slots, keyed by caseId: a failed recording must not
  // discard the disclosures already on screen, which are the evidence a reader came for.
  disclosures: Keyed<MedicalDisclosureView[]>;
  recordingDisclosures: Keyed<true>;

  loadList: (params: UnderwritingListParams) => Promise<void>;
  openCase: (request: OpenCaseRequest) => Promise<void>;
  resetOpenCase: () => void;
  loadCase: (caseId: string) => Promise<void>;
  submitAssessment: (caseId: string, request: SubmitAssessmentRequest) => Promise<void>;
  resetSubmitAssessment: (caseId: string) => void;
  decide: (caseId: string, request: DecideRequest) => Promise<void>;
  resetDecide: (caseId: string) => void;
  referCase: (caseId: string) => Promise<void>;
  loadDisclosures: (caseId: string) => Promise<void>;
  recordDisclosures: (caseId: string, request: RecordDisclosuresRequest) => Promise<void>;
  resetRecordDisclosures: (caseId: string) => void;
  resetReferCase: (caseId: string) => void;
}

export const useUnderwritingStore = create<UnderwritingState>((set, getState) => ({
  list: idle(),
  opening: idle(),
  cases: {},
  submittingAssessment: {},
  deciding: {},
  referring: {},
  disclosures: {},
  recordingDisclosures: {},

  loadList: (params) =>
    track(
      'underwriting.list',
      getState().list,
      (next) => set({ list: next }),
      () => listCases(params),
    ),

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

  decide: (caseId, request) =>
    track(
      `underwriting.decide.${caseId}`,
      getState().deciding[caseId] ?? idle<UnderwritingCaseView>(),
      (next) => set((s) => ({ deciding: { ...s.deciding, [caseId]: next } })),
      async () => {
        const view = await decide(caseId, request);
        // The response IS the decided case, so write it straight into the detail slot
        // rather than firing a GET for data already in hand -- the same shape
        // submitAssessment uses above.
        //
        // The policy this may have just triggered is NOT reflected here. Issuance happens
        // in an AFTER_COMMIT listener on the backend, so it is not necessarily done by the
        // time this response lands, and inventing a policy number the console has not been
        // given would be worse than showing none.
        set((s) => ({ cases: { ...s.cases, [caseId]: success(view) } }));
        return view;
      },
    ),

  resetDecide: (caseId) =>
    set((s) => {
      if (!(caseId in s.deciding)) return s;
      const { [caseId]: _discard, ...rest } = s.deciding;
      return { deciding: rest };
    }),

  loadDisclosures: (caseId) =>
    track(
      `underwriting.disclosures.${caseId}`,
      getState().disclosures[caseId] ?? idle<MedicalDisclosureView[]>(),
      (next) => set((s) => ({ disclosures: { ...s.disclosures, [caseId]: next } })),
      () => listDisclosures(caseId),
    ),

  recordDisclosures: (caseId, request) =>
    track(
      `underwriting.recordDisclosures.${caseId}`,
      getState().recordingDisclosures[caseId] ?? idle<true>(),
      (next) => set((s) => ({ recordingDisclosures: { ...s.recordingDisclosures, [caseId]: next } })),
      // Explicit Promise<true>: see productStore.publishVersion for why the annotation is
      // required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await recordDisclosures(caseId, request);
        // The POST returns the one new set, not the whole list, and a second recording adds
        // to the case rather than replacing it -- so refetch to show the full record.
        await getState().loadDisclosures(caseId);
        return true;
      },
    ),

  resetRecordDisclosures: (caseId) =>
    set((s) => {
      if (!(caseId in s.recordingDisclosures)) return s;
      const { [caseId]: _discard, ...rest } = s.recordingDisclosures;
      return { recordingDisclosures: rest };
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
export const selectDeciding = (caseId: string) => (s: UnderwritingState) =>
  s.deciding[caseId] ?? idle<UnderwritingCaseView>();
export const selectReferring = (caseId: string) => (s: UnderwritingState) =>
  s.referring[caseId] ?? idle<true>();
export const selectDisclosures = (caseId: string) => (s: UnderwritingState) =>
  s.disclosures[caseId] ?? idle<MedicalDisclosureView[]>();
export const selectRecordingDisclosures = (caseId: string) => (s: UnderwritingState) =>
  s.recordingDisclosures[caseId] ?? idle<true>();
