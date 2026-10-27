import { describe, expect, it } from 'vitest';
import type { Receipt } from '@/api/receipts';
import { confirmReceiptGates } from './receiptGates';

const receipt = (over: Partial<Receipt> = {}): Receipt => ({
  receiptId: 'r-1',
  method: 'CHEQUE',
  reference: 'CHQ-1',
  payerRef: 'Amina Owner',
  amount: { amount: '120000.00', currencyCode: 'TZS' },
  invoiceId: 'inv-1',
  receivedOn: '2026-10-27',
  status: 'AWAITING_APPROVAL',
  recordedBy: 'finance-one',
  recordedAt: '2026-10-27T08:00:00Z',
  ...over,
});

describe('confirmReceiptGates', () => {
  it('lets a second person confirm a receipt that is waiting', () => {
    expect(confirmReceiptGates(receipt(), 'finance-two').every((g) => g.ok)).toBe(true);
  });

  it('refuses the person who recorded it, saying so to them', () => {
    const second = confirmReceiptGates(receipt(), 'finance-one')[1]!;
    expect(second.ok).toBe(false);
    expect(second.detail).toBe('You recorded this receipt, so someone else must confirm it.');
  });

  it('refuses one already decided', () => {
    expect(confirmReceiptGates(receipt({ status: 'CONFIRMED' }), 'finance-two')[0]!.ok).toBe(false);
  });
});
