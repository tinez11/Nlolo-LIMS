import { create } from 'zustand';
import {
  searchArrears,
  searchFieldReceipts,
  type ArrearsSearchParams,
  type FieldReceiptSearchParams,
} from '@/api/billing';
import type { ArrearsCaseView, FieldReceiptView, Page } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

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
  loadArrears: (params: ArrearsSearchParams) => Promise<void>;
  loadFieldReceipts: (params: FieldReceiptSearchParams) => Promise<void>;
}

export const useBillingStore = create<BillingState>((set, getState) => ({
  arrears: idle(),
  fieldReceipts: idle(),

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
}));

export const selectArrears = (s: BillingState) => s.arrears;
export const selectFieldReceipts = (s: BillingState) => s.fieldReceipts;
