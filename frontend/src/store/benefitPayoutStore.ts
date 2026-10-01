import { create } from 'zustand';
import {
  approveFreeLook,
  approvePaymentRun,
  approvePayout,
  findFreeLook,
  getPayout,
  listPaymentRuns,
  listPolicyPayouts,
  listRunInstalments,
  recordProofOfLife,
  requestFreeLook,
  retryPayout,
  reviewPayout,
  searchPayouts,
  type FreeLookBody,
  type PayoutSearchParams,
  type ProofOfLifeBody,
  type ReviewPayoutBody,
} from '@/api/benefitPayouts';
import type { FreeLookCancellationView, Page, PaymentRunView, PayoutInstalmentView } from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, success, track, type Resource } from './createResourceSlice';

/**
 * Benefit payouts: one policy's schedule, the finance queue, the daily payment runs and free-look.
 *
 * Keyed resources throughout, because this store backs four screens at once and a single `detail`
 * slot would make opening a payout from the queue blank the queue behind it. The `?? idle()`
 * fallbacks return the SHARED frozen empty resource, which is load-bearing -- see
 * createResourceSlice's own note on the render loop a fresh object causes.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface BenefitPayoutState {
  byPolicy: Keyed<PayoutInstalmentView[]>;
  instalment: Keyed<PayoutInstalmentView>;
  queue: Resource<Page<PayoutInstalmentView>>;
  runs: Resource<PaymentRunView[]>;
  runInstalments: Keyed<PayoutInstalmentView[]>;
  freeLook: Keyed<FreeLookCancellationView | null>;
  /** Per-id in-flight state for the actions, so one row's spinner is not every row's. */
  acting: Keyed<unknown>;

  loadForPolicy: (policyNumber: string) => Promise<void>;
  loadInstalment: (instalmentId: string) => Promise<void>;
  loadQueue: (params: PayoutSearchParams) => Promise<void>;
  loadRuns: () => Promise<void>;
  loadRunInstalments: (paymentRunId: string) => Promise<void>;
  loadFreeLook: (policyNumber: string) => Promise<void>;

  review: (instalmentId: string, body: ReviewPayoutBody, attempt: MutationAttempt) => Promise<void>;
  approve: (instalmentId: string, attempt: MutationAttempt) => Promise<void>;
  retry: (instalmentId: string, attempt: MutationAttempt) => Promise<void>;
  approveRun: (paymentRunId: string, attempt: MutationAttempt) => Promise<void>;
  proveLife: (streamId: string, policyNumber: string, body: ProofOfLifeBody, attempt: MutationAttempt) => Promise<void>;
  requestCancellation: (policyNumber: string, body: FreeLookBody, attempt: MutationAttempt) => Promise<void>;
  approveCancellation: (cancellationId: string, policyNumber: string, attempt: MutationAttempt) => Promise<void>;
}

export const useBenefitPayoutStore = create<BenefitPayoutState>((set, getState) => {
  /** Run one mutation under its own `acting` slot, then fold the result back into the reads. */
  const act = (key: string, call: () => Promise<void>) =>
    track(
      `benefitpayout.act.${key}`,
      getState().acting[key] ?? idle<unknown>(),
      (next) => set((s) => ({ acting: { ...s.acting, [key]: next } })),
      async () => {
        await call();
      },
    );

  /** An action returned a fresh instalment: update the detail slot AND the policy's list in place,
   *  so a review made from the policy tab does not need the whole list refetched to show. */
  const absorb = (updated: PayoutInstalmentView) =>
    set((s) => {
      const list = s.byPolicy[updated.policyNumber];
      return {
        instalment: { ...s.instalment, [updated.instalmentId]: success(updated) },
        byPolicy: list?.data
          ? {
              ...s.byPolicy,
              [updated.policyNumber]: success(
                list.data.map((row) => (row.instalmentId === updated.instalmentId ? updated : row)),
              ),
            }
          : s.byPolicy,
      };
    });

  return {
    byPolicy: {},
    instalment: {},
    queue: idle(),
    runs: idle(),
    runInstalments: {},
    freeLook: {},
    acting: {},

    loadForPolicy: (policyNumber) =>
      track(
        `benefitpayout.policy.${policyNumber}`,
        getState().byPolicy[policyNumber] ?? idle<PayoutInstalmentView[]>(),
        (next) => set((s) => ({ byPolicy: { ...s.byPolicy, [policyNumber]: next } })),
        () => listPolicyPayouts(policyNumber),
      ),

    loadInstalment: (instalmentId) =>
      track(
        `benefitpayout.instalment.${instalmentId}`,
        getState().instalment[instalmentId] ?? idle<PayoutInstalmentView>(),
        (next) => set((s) => ({ instalment: { ...s.instalment, [instalmentId]: next } })),
        () => getPayout(instalmentId),
      ),

    loadQueue: (params) =>
      track('benefitpayout.queue', getState().queue, (next) => set({ queue: next }), () => searchPayouts(params)),

    loadRuns: () => track('benefitpayout.runs', getState().runs, (next) => set({ runs: next }), listPaymentRuns),

    loadRunInstalments: (paymentRunId) =>
      track(
        `benefitpayout.run.${paymentRunId}`,
        getState().runInstalments[paymentRunId] ?? idle<PayoutInstalmentView[]>(),
        (next) => set((s) => ({ runInstalments: { ...s.runInstalments, [paymentRunId]: next } })),
        () => listRunInstalments(paymentRunId),
      ),

    loadFreeLook: (policyNumber) =>
      track(
        `benefitpayout.freelook.${policyNumber}`,
        getState().freeLook[policyNumber] ?? idle<FreeLookCancellationView | null>(),
        (next) => set((s) => ({ freeLook: { ...s.freeLook, [policyNumber]: next } })),
        () => findFreeLook(policyNumber),
      ),

    review: (instalmentId, body, attempt) =>
      act(instalmentId, async () => absorb(await reviewPayout(instalmentId, body, attempt))),

    approve: (instalmentId, attempt) =>
      act(instalmentId, async () => absorb(await approvePayout(instalmentId, attempt))),

    retry: (instalmentId, attempt) =>
      act(instalmentId, async () => absorb(await retryPayout(instalmentId, attempt))),

    approveRun: (paymentRunId, attempt) =>
      act(paymentRunId, async () => {
        const updated = await approvePaymentRun(paymentRunId, attempt);
        set((s) => ({
          runs: s.runs.data
            ? success(s.runs.data.map((r) => (r.paymentRunId === updated.paymentRunId ? updated : r)))
            : s.runs,
        }));
        // Every instalment in the batch just changed status, and the figures a person is looking
        // at are now stale, so the list is refetched rather than patched.
        await getState().loadRunInstalments(paymentRunId);
      }),

    proveLife: (streamId, policyNumber, body, attempt) =>
      act(streamId, async () => {
        await recordProofOfLife(streamId, body, attempt);
        // Proof releases whatever the overdue proof held, which this response does not say -- so
        // the policy's schedule is reread rather than guessed at.
        await getState().loadForPolicy(policyNumber);
      }),

    requestCancellation: (policyNumber, body, attempt) =>
      act(`freelook.${policyNumber}`, async () => {
        const created = await requestFreeLook(policyNumber, body, attempt);
        set((s) => ({ freeLook: { ...s.freeLook, [policyNumber]: success(created) } }));
      }),

    approveCancellation: (cancellationId, policyNumber, attempt) =>
      act(`freelook.${policyNumber}`, async () => {
        const approved = await approveFreeLook(cancellationId, attempt);
        set((s) => ({ freeLook: { ...s.freeLook, [policyNumber]: success(approved) } }));
        // Approval voids the policy and withdraws its whole schedule.
        await getState().loadForPolicy(policyNumber);
      }),
  };
});
