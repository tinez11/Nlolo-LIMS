import { create } from 'zustand';
import {
  searchArrears,
  searchFieldReceipts,
  reconcileFieldReceipt,
  type ArrearsSearchParams,
  type FieldReceiptSearchParams,
} from '@/api/billing';
import type { ArrearsCaseView, FieldReceiptView, Page } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/** Local, like every other store here declares its own. One line beats a shared import
 *  that ties ten stores to one file for a type alias. */
type Keyed<T> = Record<string, Resource<T>>;

/**
 * The `billing` domain store: the two work queues this module gained a read surface for — who
 * owes us premium, and which collected cash has not matched a payment.
 *
 * Invoices are deliberately not here. They are read per policy and live in `policyStore`
 * alongside the record that displays them, keyed by policy number — the same reasoning
 * `clientRecord` gives for keying by the entity the answer is about. This store exists for
 * the one billing read that is about the tenant rather than about a policy.
 */

interface BillingState {
  /**
   * Single slot, not keyed. One collections queue is on screen at a time, and within it a
   * change of level filter or page is the same table — so the newest request should win.
   *
   * Unlike the client register, which had to be keyed by area once it became two separate
   * lists under two nav items, this genuinely is one list.
   */
  arrears: Resource<Page<ArrearsCaseView>>;
  /**
   * Its own slot, not shared with arrears. They are two screens answering two questions --
   * who owes us, and which collected cash has not matched a payment -- and one slot would let
   * one queue render under the other's heading while the next request was in flight. The client
   * register already taught this lesson the hard way.
   */
  fieldReceipts: Resource<Page<FieldReceiptView>>;
  /**
   * Keyed by receiptId, because several rows of one queue can be cleared in a row and each
   * needs its own busy state and its own error. A single slot would disable every button
   * while one was in flight, and put one row's failure under another row's action.
   */
  reconciling: Keyed<FieldReceiptView>;
  loadArrears: (params: ArrearsSearchParams) => Promise<void>;
  loadFieldReceipts: (params: FieldReceiptSearchParams) => Promise<void>;
  reconcileFieldReceipt: (receiptId: string) => Promise<void>;
}

export const useBillingStore = create<BillingState>((set, getState) => ({
  arrears: idle(),
  fieldReceipts: idle(),
  reconciling: {},

  loadArrears: (params) =>
    track(
      'billing.arrears',
      getState().arrears,
      (next) => set({ arrears: next }),
      () => searchArrears(params),
    ),

  loadFieldReceipts: (params) =>
    track(
      'billing.fieldReceipts',
      getState().fieldReceipts,
      (next) => set({ fieldReceipts: next }),
      () => searchFieldReceipts(params),
    ),

  reconcileFieldReceipt: (receiptId) =>
    track(
      `billing.reconcile.${receiptId}`,
      getState().reconciling[receiptId] ?? idle<FieldReceiptView>(),
      (next) => set((s) => ({ reconciling: { ...s.reconciling, [receiptId]: next } })),
      async () => {
        const updated = await reconcileFieldReceipt(receiptId);
        // Patch the row in place rather than refetching the page. A refetch would re-sort and
        // re-page a queue somebody is working down, and on the default RECONCILIATION_OVERDUE
        // filter it would make the row they just cleared vanish mid-scroll. The status badge
        // changing to RECONCILED where they clicked is the honest feedback.
        const current = getState().fieldReceipts;
        if (current.data) {
          set({
            fieldReceipts: {
              ...current,
              data: {
                ...current.data,
                items: current.data.items.map((r) => (r.receiptId === receiptId ? updated : r)),
              },
            },
          });
        }
        return updated;
      },
    ),
}));

export const selectArrears = (s: BillingState) => s.arrears;
export const selectFieldReceipts = (s: BillingState) => s.fieldReceipts;
export const selectReconciling = (receiptId: string) => (s: BillingState) =>
  s.reconciling[receiptId] ?? idle<FieldReceiptView>();
