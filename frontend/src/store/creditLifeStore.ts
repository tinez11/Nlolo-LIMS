import { create } from 'zustand';
import {
  acceptEnrolmentSubmission,
  acceptExitSubmission,
  listEnrolmentRows,
  listEnrolmentSubmissions,
  listExitRows,
  listExitSubmissions,
  uploadEnrolmentFile,
  uploadExitsFile,
  withdrawEnrolmentSubmission,
  withdrawExitSubmission,
} from '@/api/creditLife';
import type {
  EnrolmentRowView,
  EnrolmentSubmissionView,
  ExitRowView,
  ExitSubmissionView,
} from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

type Keyed<T> = Record<string, Resource<T>>;

/**
 * The two monthly files a credit-life scheme runs on.
 *
 * Its own store rather than more slices on `policyStore`, which is already the largest in the
 * app: nothing here is read by any other screen, and the scheme page is the only consumer.
 *
 * **Rows are keyed by submission, not by scheme.** A person comparing last month's rejections
 * with this month's opens two submissions, and a scheme-keyed slot would make the second read
 * evict the first.
 */
interface CreditLifeState {
  enrolments: Keyed<EnrolmentSubmissionView[]>;
  exits: Keyed<ExitSubmissionView[]>;
  enrolmentRows: Keyed<EnrolmentRowView[]>;
  exitRows: Keyed<ExitRowView[]>;

  /** One slot per scheme per kind: a scheme permits only one submission in flight, so there is
   * never a second upload to track. */
  uploading: Keyed<EnrolmentSubmissionView | ExitSubmissionView>;
  /** Keyed by submission: accepting one file while withdrawing another is legitimate. */
  deciding: Keyed<EnrolmentSubmissionView | ExitSubmissionView>;

  loadEnrolments: (policyNumber: string) => Promise<void>;
  loadExits: (policyNumber: string) => Promise<void>;
  loadEnrolmentRows: (policyNumber: string, submissionId: string) => Promise<void>;
  loadExitRows: (policyNumber: string, submissionId: string) => Promise<void>;
  uploadEnrolment: (policyNumber: string, file: File) => Promise<void>;
  uploadExits: (policyNumber: string, file: File) => Promise<void>;
  acceptEnrolment: (policyNumber: string, submissionId: string) => Promise<void>;
  withdrawEnrolment: (policyNumber: string, submissionId: string) => Promise<void>;
  acceptExits: (policyNumber: string, submissionId: string) => Promise<void>;
  withdrawExits: (policyNumber: string, submissionId: string) => Promise<void>;
  clearDecision: (submissionId: string) => void;
  clearUpload: (key: string) => void;
}

const uploadKey = (policyNumber: string, kind: 'enrolment' | 'exits') => `${kind}:${policyNumber}`;

export const useCreditLifeStore = create<CreditLifeState>((set, getState) => ({
  enrolments: {},
  exits: {},
  enrolmentRows: {},
  exitRows: {},
  uploading: {},
  deciding: {},

  loadEnrolments: (policyNumber) =>
    track(
      `creditLife.enrolments.${policyNumber}`,
      getState().enrolments[policyNumber] ?? idle<EnrolmentSubmissionView[]>(),
      (next) => set((s) => ({ enrolments: { ...s.enrolments, [policyNumber]: next } })),
      () => listEnrolmentSubmissions(policyNumber),
    ),

  loadExits: (policyNumber) =>
    track(
      `creditLife.exits.${policyNumber}`,
      getState().exits[policyNumber] ?? idle<ExitSubmissionView[]>(),
      (next) => set((s) => ({ exits: { ...s.exits, [policyNumber]: next } })),
      () => listExitSubmissions(policyNumber),
    ),

  loadEnrolmentRows: (policyNumber, submissionId) =>
    track(
      `creditLife.enrolmentRows.${submissionId}`,
      getState().enrolmentRows[submissionId] ?? idle<EnrolmentRowView[]>(),
      (next) => set((s) => ({ enrolmentRows: { ...s.enrolmentRows, [submissionId]: next } })),
      () => listEnrolmentRows(policyNumber, submissionId),
    ),

  loadExitRows: (policyNumber, submissionId) =>
    track(
      `creditLife.exitRows.${submissionId}`,
      getState().exitRows[submissionId] ?? idle<ExitRowView[]>(),
      (next) => set((s) => ({ exitRows: { ...s.exitRows, [submissionId]: next } })),
      () => listExitRows(policyNumber, submissionId),
    ),

  uploadEnrolment: async (policyNumber, file) => {
    const key = uploadKey(policyNumber, 'enrolment');
    await track(
      `creditLife.uploadEnrolment.${policyNumber}`,
      getState().uploading[key] ?? idle<EnrolmentSubmissionView>(),
      (next) => set((s) => ({ uploading: { ...s.uploading, [key]: next } })),
      () => uploadEnrolmentFile(policyNumber, file),
    );
    await getState().loadEnrolments(policyNumber);
  },

  uploadExits: async (policyNumber, file) => {
    const key = uploadKey(policyNumber, 'exits');
    await track(
      `creditLife.uploadExits.${policyNumber}`,
      getState().uploading[key] ?? idle<ExitSubmissionView>(),
      (next) => set((s) => ({ uploading: { ...s.uploading, [key]: next } })),
      () => uploadExitsFile(policyNumber, file),
    );
    await getState().loadExits(policyNumber);
  },

  acceptEnrolment: async (policyNumber, submissionId) => {
    await track(
      `creditLife.acceptEnrolment.${submissionId}`,
      getState().deciding[submissionId] ?? idle<EnrolmentSubmissionView>(),
      (next) => set((s) => ({ deciding: { ...s.deciding, [submissionId]: next } })),
      () => acceptEnrolmentSubmission(policyNumber, submissionId),
    );
    // Acceptance is what creates cover, so the member roll is stale the moment it returns. The
    // page owns reloading that; this reloads what this store holds.
    await getState().loadEnrolments(policyNumber);
  },

  withdrawEnrolment: async (policyNumber, submissionId) => {
    await track(
      `creditLife.withdrawEnrolment.${submissionId}`,
      getState().deciding[submissionId] ?? idle<EnrolmentSubmissionView>(),
      (next) => set((s) => ({ deciding: { ...s.deciding, [submissionId]: next } })),
      () => withdrawEnrolmentSubmission(policyNumber, submissionId),
    );
    await getState().loadEnrolments(policyNumber);
  },

  acceptExits: async (policyNumber, submissionId) => {
    await track(
      `creditLife.acceptExits.${submissionId}`,
      getState().deciding[submissionId] ?? idle<ExitSubmissionView>(),
      (next) => set((s) => ({ deciding: { ...s.deciding, [submissionId]: next } })),
      () => acceptExitSubmission(policyNumber, submissionId),
    );
    await getState().loadExits(policyNumber);
  },

  withdrawExits: async (policyNumber, submissionId) => {
    await track(
      `creditLife.withdrawExits.${submissionId}`,
      getState().deciding[submissionId] ?? idle<ExitSubmissionView>(),
      (next) => set((s) => ({ deciding: { ...s.deciding, [submissionId]: next } })),
      () => withdrawExitSubmission(policyNumber, submissionId),
    );
    await getState().loadExits(policyNumber);
  },

  clearDecision: (submissionId) =>
    set((s) => {
      const { [submissionId]: _removed, ...rest } = s.deciding;
      return { deciding: rest };
    }),

  clearUpload: (key) =>
    set((s) => {
      const { [key]: _removed, ...rest } = s.uploading;
      return { uploading: rest };
    }),
}));

export const selectEnrolments = (policyNumber: string) => (s: CreditLifeState) =>
  s.enrolments[policyNumber] ?? idle<EnrolmentSubmissionView[]>();

export const selectExits = (policyNumber: string) => (s: CreditLifeState) =>
  s.exits[policyNumber] ?? idle<ExitSubmissionView[]>();

export const selectEnrolmentRows = (submissionId: string | null) => (s: CreditLifeState) =>
  (submissionId ? s.enrolmentRows[submissionId] : undefined) ?? idle<EnrolmentRowView[]>();

export const selectExitRows = (submissionId: string | null) => (s: CreditLifeState) =>
  (submissionId ? s.exitRows[submissionId] : undefined) ?? idle<ExitRowView[]>();

export const selectUploading =
  (policyNumber: string, kind: 'enrolment' | 'exits') => (s: CreditLifeState) =>
    s.uploading[uploadKey(policyNumber, kind)] ?? idle<EnrolmentSubmissionView | ExitSubmissionView>();

export const selectDeciding = (submissionId: string | null) => (s: CreditLifeState) =>
  (submissionId ? s.deciding[submissionId] : undefined) ??
  idle<EnrolmentSubmissionView | ExitSubmissionView>();
