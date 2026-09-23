import { create } from 'zustand';
import { listAwaitingEftExecution, markEftExecuted } from '@/api/payments';
import type { AwaitingEftView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/** Local, like every other store here declares its own. */
type Keyed<T> = Record<string, Resource<T>>;

/**
 * The `payment` domain store, which exists for exactly one queue: the bank transfers waiting on
 * a person.
 *
 * Nothing else in payment needs a store. Every other rail on this platform completes without
 * anybody looking at it, and a payment's status is read on the record that owns it — an invoice,
 * a claim — rather than as a list of its own. This is the one payment read that is about the
 * tenant's outstanding work.
 */
interface PaymentState {
  /** Single slot: one work queue, on screen at a time, newest request wins. */
  awaitingEft: Resource<AwaitingEftView[]>;
  /**
   * Keyed by disbursementId. A finance officer clears several transfers in a sitting, and a
   * single slot would disable every row's button while one was in flight and put one row's 409
   * under another row's action.
   */
  executing: Keyed<void>;
  loadAwaitingEft: () => Promise<void>;
  markExecuted: (disbursementId: string, bankReference: string) => Promise<void>;
}

export const usePaymentStore = create<PaymentState>((set, getState) => ({
  awaitingEft: idle(),
  executing: {},

  loadAwaitingEft: () =>
    track(
      'payment.awaitingEft',
      getState().awaitingEft,
      (next) => set({ awaitingEft: next }),
      () => listAwaitingEftExecution(),
    ),

  markExecuted: (disbursementId, bankReference) =>
    track(
      `payment.markExecuted.${disbursementId}`,
      getState().executing[disbursementId] ?? idle<void>(),
      (next) => set((s) => ({ executing: { ...s.executing, [disbursementId]: next } })),
      async () => {
        await markEftExecuted(disbursementId, bankReference);
        /*
         * The row is DROPPED rather than patched, which is the opposite of what the field-receipt
         * queue does with its own confirmations, and the difference is real. A reconciled receipt
         * stays a receipt and its badge changes; a confirmed transfer stops being awaiting
         * execution at all -- it is the whole membership test of this list. Leaving it on screen
         * with a tick would invite a second confirmation of money that has already moved once.
         *
         * Dropped locally rather than by refetching, so a queue somebody is working down does not
         * re-sort under them.
         */
        const current = getState().awaitingEft;
        if (current.data) {
          set({
            awaitingEft: {
              ...current,
              data: current.data.filter((d) => d.disbursementId !== disbursementId),
            },
          });
        }
      },
    ),
}));

export const selectAwaitingEft = (s: PaymentState) => s.awaitingEft;
export const selectExecuting = (disbursementId: string) => (s: PaymentState) =>
  s.executing[disbursementId] ?? idle<void>();
