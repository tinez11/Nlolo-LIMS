import { create } from 'zustand';
import {
  approveReceipt,
  getReceipt,
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
  record: (body: RecordReceipt) => Promise<Receipt | null>;
  load: (receiptId: string) => Promise<void>;
  approve: (receiptId: string) => Promise<void>;
  reject: (receiptId: string, reason: string) => Promise<void>;
}

export const useReceiptStore = create<ReceiptState>((set, getState) => ({
  recording: idle(),
  receipt: {},
  deciding: {},

  record: async (body) => {
    await track('receipt.record', getState().recording, (next) => set({ recording: next }), () => recordReceipt(body));
    const result = getState().recording;
    return result.status === 'success' ? result.data : null;
  },

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
