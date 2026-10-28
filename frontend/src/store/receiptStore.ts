import { create } from 'zustand';
import {
  approveReceipt,
  getReceipt,
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
  record: (body: RecordReceipt) => Promise<Receipt | null>;
  loadWaiting: (invoiceId: string) => Promise<void>;
  load: (receiptId: string) => Promise<void>;
  approve: (receiptId: string) => Promise<void>;
  reject: (receiptId: string, reason: string) => Promise<void>;
}

export const useReceiptStore = create<ReceiptState>((set, getState) => ({
  recording: idle(),
  receipt: {},
  deciding: {},
  waiting: {},

  record: async (body) => {
    await track('receipt.record', getState().recording, (next) => set({ recording: next }), () => recordReceipt(body));
    const result = getState().recording;
    // The invoice shows what is waiting on it: reread it, so the receipt just recorded is there.
    if (result.status === 'success') await getState().loadWaiting(body.invoiceId);
    return result.status === 'success' ? result.data : null;
  },

  loadWaiting: (invoiceId) =>
    track(
      `receipt.waiting.${invoiceId}`,
      getState().waiting[invoiceId] ?? idle<Receipt[]>(),
      (next) => set((s) => ({ waiting: { ...s.waiting, [invoiceId]: next } })),
      () => listWaitingReceipts(invoiceId),
    ),

  load: (receiptId) =>
    track(
      `receipt.load.${receiptId}`,
      getState().receipt[receiptId] ?? idle<Receipt>(),
      (next) => set((s) => ({ receipt: { ...s.receipt, [receiptId]: next } })),
      () => getReceipt(receiptId),
    ),

  approve: (receiptId) => decide(receiptId, () => approveReceipt(receiptId)),
  reject: (receiptId, reason) => decide(receiptId, () => rejectReceipt(receiptId, reason)),
}));

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
