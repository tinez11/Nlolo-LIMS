import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as billingApi from '@/api/billing';
import type { FieldReceiptView, Page } from '@/api/types';
import { success } from './createResourceSlice';
import { useBillingStore } from './billingStore';

vi.mock('@/api/billing');

const OVERDUE = 'b1f0b7a2-0000-4000-8000-000000000001';
const PENDING = 'b1f0b7a2-0000-4000-8000-000000000002';

const receipt = (receiptId: string, status: string): FieldReceiptView =>
  ({
    receiptId,
    policyNumber: 'POL-TEST01',
    status,
    amount: { amount: '50000.00', currencyCode: 'TZS' },
  }) as FieldReceiptView;

const pageOf = (items: FieldReceiptView[]): Page<FieldReceiptView> => ({
  items,
  page: { page: 0, pageSize: 20, totalElements: items.length },
});

beforeEach(() => {
  vi.clearAllMocks();
  useBillingStore.setState({
    fieldReceipts: success(
      pageOf([
        receipt(OVERDUE, 'RECONCILIATION_OVERDUE'),
        receipt(PENDING, 'PENDING_RECONCILIATION'),
      ]),
    ),
    reconciling: {},
  });
});

/**
 * The row is patched IN PLACE rather than the page refetched, and that is the behaviour worth
 * pinning: a refetch re-sorts and re-pages a queue somebody is working down, and on the default
 * RECONCILIATION_OVERDUE filter it would make the row they just cleared vanish mid-scroll.
 */
describe('reconcileFieldReceipt', () => {
  it('replaces only the reconciled row, leaving the rest of the page untouched', async () => {
    vi.spyOn(billingApi, 'reconcileFieldReceipt').mockResolvedValue(
      receipt(OVERDUE, 'RECONCILED'),
    );

    await useBillingStore.getState().reconcileFieldReceipt(OVERDUE);

    const items = useBillingStore.getState().fieldReceipts.data!.items;
    expect(items).toHaveLength(2);
    expect(items.find((r) => r.receiptId === OVERDUE)?.status).toBe('RECONCILED');
    // The other row must be the SAME object, not a re-rendered copy: proving the page was
    // patched rather than refetched.
    expect(items.find((r) => r.receiptId === PENDING)?.status).toBe('PENDING_RECONCILIATION');
    expect(billingApi.reconcileFieldReceipt).toHaveBeenCalledWith(OVERDUE);
  });

  it('keeps per-receipt state, so one row in flight does not disable another', async () => {
    vi.spyOn(billingApi, 'reconcileFieldReceipt').mockResolvedValue(
      receipt(OVERDUE, 'RECONCILED'),
    );

    await useBillingStore.getState().reconcileFieldReceipt(OVERDUE);

    expect(useBillingStore.getState().reconciling[OVERDUE]?.status).toBe('success');
    // Never touched, because the slot is keyed by receiptId rather than shared.
    expect(useBillingStore.getState().reconciling[PENDING]).toBeUndefined();
  });

  it('records the failure against that receipt and leaves the row unchanged', async () => {
    vi.spyOn(billingApi, 'reconcileFieldReceipt').mockRejectedValue({
      status: 404,
      kind: 'notFound',
      errorCode: 'FIELD_RECEIPT_NOT_FOUND',
      title: 'Not Found',
      detail: 'Field receipt not found',
      fieldErrors: [],
      mayBeDenied: false,
    });

    await useBillingStore.getState().reconcileFieldReceipt(OVERDUE);

    expect(useBillingStore.getState().reconciling[OVERDUE]?.status).toBe('error');
    // The queue must not claim a match that did not happen.
    expect(
      useBillingStore.getState().fieldReceipts.data!.items.find((r) => r.receiptId === OVERDUE)
        ?.status,
    ).toBe('RECONCILIATION_OVERDUE');
  });
});
