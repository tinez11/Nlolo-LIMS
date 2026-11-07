import { create } from 'zustand';
import {
  approveReceipt,
  decideDepositReversal,
  listDepositReversals,
  requestDepositReversal,
  type DepositReversal,
  getReceipt,
  listWaitingDeposits,
  listWaitingReceipts,
  recordReceipt,
  rejectReceipt,
  type Receipt,
  type RecordReceipt,
} from '@/api/receipts';
import { idle, track, type Resource } from './createResourceSlice';

/** Recorded receipts (2026-10-27): the one being recorded, the ones open on screen, and the decision in flight. */
interface ReceiptState {
  recording: Resource<Receipt>;
  receipt: Record<string, Resource<Receipt>>;
  deciding: Record<string, Resource<Receipt>>;
  /** By invoice: the receipts recorded against it and still waiting for their second signature. */
  waiting: Record<string, Resource<Receipt[]>>;
  /** By policy: the deposits recorded on its balance and still waiting for their second signature. */
  waitingOnPolicy: Record<string, Resource<Receipt[]>>;
  /** Money movement step 5: the reversals of a deposit, keyed by its receipt id, and the act in flight on each. */
  reversals: Record<string, Resource<DepositReversal[]>>;
  reversing: Record<string, Resource<true>>;
  loadReversals: (receiptId: string) => Promise<void>;
  /** Resolves true when the reversal was asked; the error is on reversing[receiptId] otherwise. */
  requestReversal: (receiptId: string, reason: string) => Promise<boolean>;
  decideReversal: (receiptId: string, reversalId: string, rejectReason: string | null) => Promise<void>;
  record: (body: RecordReceipt) => Promise<Receipt | null>;
  /** The Deposit page's other roads (2026-11-07): a mobile-money request, or a savings deposit received by hand. */
  sending: Resource<true>;
  /** Resolves true when it went; the error is on `sending` otherwise. */
  send: (act: () => Promise<unknown>) => Promise<boolean>;
  resetSending: () => void;
  loadWaiting: (invoiceId: string) => Promise<void>;
  loadWaitingOnPolicy: (policyNumber: string) => Promise<void>;
  load: (receiptId: string) => Promise<void>;
  approve: (receiptId: string) => Promise<void>;
  reject: (receiptId: string, reason: string) => Promise<void>;
}

export const useReceiptStore = create<ReceiptState>((set, getState) => ({
  recording: idle(),
  sending: idle(),
  receipt: {},
  deciding: {},
  waiting: {},
  waitingOnPolicy: {},
  reversals: {},
  reversing: {},

  record: async (body) => {
    await track('receipt.record', getState().recording, (next) => set({ recording: next }), () => recordReceipt(body));
    const result = getState().recording;
    // The invoice shows what is waiting on it: reread it, so the receipt just recorded is there. A loan deposit names no
    // invoice (money movement step 1), so there is nothing to reread.
    if (result.status === 'success' && body.invoiceId) await getState().loadWaiting(body.invoiceId);
    if (result.status === 'success' && body.policyNumber) await getState().loadWaitingOnPolicy(body.policyNumber);
    return result.status === 'success' ? result.data : null;
  },

  send: async (act) => {
    await track('receipt.send', getState().sending, (next) => set({ sending: next }), async (): Promise<true> => {
      await act();
      return true;
    });
    return getState().sending.status === 'success';
  },

  resetSending: () => set({ sending: idle() }),

  loadWaiting: (invoiceId) =>
    track(
      `receipt.waiting.${invoiceId}`,
      getState().waiting[invoiceId] ?? idle<Receipt[]>(),
      (next) => set((s) => ({ waiting: { ...s.waiting, [invoiceId]: next } })),
      () => listWaitingReceipts(invoiceId),
    ),

  loadWaitingOnPolicy: (policyNumber) =>
    track(
      `receipt.waitingOnPolicy.${policyNumber}`,
      getState().waitingOnPolicy[policyNumber] ?? idle<Receipt[]>(),
      (next) => set((s) => ({ waitingOnPolicy: { ...s.waitingOnPolicy, [policyNumber]: next } })),
      () => listWaitingDeposits(policyNumber),
    ),

  load: (receiptId) =>
    track(
      `receipt.load.${receiptId}`,
      getState().receipt[receiptId] ?? idle<Receipt>(),
      (next) => set((s) => ({ receipt: { ...s.receipt, [receiptId]: next } })),
      () => getReceipt(receiptId),
    ),

  loadReversals: (receiptId) =>
    track(
      `receipt.reversals.${receiptId}`,
      getState().reversals[receiptId] ?? idle<DepositReversal[]>(),
      (next) => set((s) => ({ reversals: { ...s.reversals, [receiptId]: next } })),
      () => listDepositReversals(receiptId),
    ),

  requestReversal: async (receiptId, reason) => {
    await reverseAct(receiptId, () => requestDepositReversal(receiptId, reason));
    return getState().reversing[receiptId]?.status === 'success';
  },

  decideReversal: (receiptId, reversalId, rejectReason) =>
    reverseAct(receiptId, () => decideDepositReversal(reversalId, rejectReason)),

  approve: (receiptId) => decide(receiptId, () => approveReceipt(receiptId)),
  reject: (receiptId, reason) => decide(receiptId, () => rejectReceipt(receiptId, reason)),
}));

/** One reversal act at a time per deposit; the deposit's reversals are read again after it. */
async function reverseAct(receiptId: string, act: () => Promise<unknown>) {
  const { getState, setState } = useReceiptStore;
  await track(
    `receipt.reverse.${receiptId}`,
    getState().reversing[receiptId] ?? idle<true>(),
    (next) => setState((s) => ({ reversing: { ...s.reversing, [receiptId]: next } })),
    async (): Promise<true> => {
      await act();
      await getState().loadReversals(receiptId);
      return true;
    },
  );
}

/** One decision at a time per receipt; its answer replaces the receipt on screen, so the page reads the outcome. */
async function decide(receiptId: string, act: () => Promise<Receipt>) {
  const { getState, setState } = useReceiptStore;
  await track(
    `receipt.decide.${receiptId}`,
    getState().deciding[receiptId] ?? idle<Receipt>(),
    (next) =>
      setState((s) => ({
        deciding: { ...s.deciding, [receiptId]: next },
        ...(next.status === 'success' && next.data
          ? { receipt: { ...s.receipt, [receiptId]: { ...next } } }
          : {}),
      })),
    act,
  );
}
