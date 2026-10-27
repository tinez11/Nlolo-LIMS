import { get, post } from '@/lib/http';
import type { components } from '@/types/api/payment';

/**
 * Receipts for premium the gateway did not pull (2026-10-27): cash, a bank deposit, a cheque, a Lipa-number payment or
 * a TANQR scan. Finance records one against an invoice; a second finance person confirms or refuses it, and only a
 * confirmed receipt pays anything.
 */
export type Receipt = components['schemas']['Receipt'];
export type RecordReceipt = components['schemas']['RecordReceipt'];
export type ReceiptMethod = components['schemas']['ReceiptMethod'];

/** `POST /receipts` -- collections.manage. 409 RECEIPT_REFUSED says why in words. */
export function recordReceipt(body: RecordReceipt): Promise<Receipt> {
  return post<Receipt>('/receipts', body);
}

export function getReceipt(receiptId: string): Promise<Receipt> {
  return get<Receipt>(`/receipts/${encodeURIComponent(receiptId)}`);
}

/** `POST /receipts/{id}/approval` -- collections.approve, and never the person who recorded it. */
export function approveReceipt(receiptId: string): Promise<Receipt> {
  return post<Receipt>(`/receipts/${encodeURIComponent(receiptId)}/approval`);
}

export function rejectReceipt(receiptId: string, reason: string): Promise<Receipt> {
  return post<Receipt>(`/receipts/${encodeURIComponent(receiptId)}/rejection`, { reason });
}
