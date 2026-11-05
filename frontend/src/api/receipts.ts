import { get, post } from '@/lib/http';
import type { components } from '@/types/api/payment';
import type { components as BillingComponents } from '@/types/api/billing';

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

/** `GET /receipts?invoiceId=` -- the receipts still waiting to be confirmed on one invoice (2026-10-28). */
export function listWaitingReceipts(invoiceId: string): Promise<Receipt[]> {
  return get<Receipt[]>(`/receipts?invoiceId=${encodeURIComponent(invoiceId)}`);
}

/** `GET /receipts?policyNumber=` -- the deposits still waiting to be confirmed on one policy's balance (money movement). */
export function listWaitingDeposits(policyNumber: string): Promise<Receipt[]> {
  return get<Receipt[]>(`/receipts?policyNumber=${encodeURIComponent(policyNumber)}`);
}

export function getReceipt(receiptId: string): Promise<Receipt> {
  return get<Receipt>(`/receipts/${encodeURIComponent(receiptId)}`);
}

/** `POST /receipts/{id}/approval` -- collections.approve, and never the person who recorded it. */
export function approveReceipt(receiptId: string): Promise<Receipt> {
  return post<Receipt>(`/receipts/${encodeURIComponent(receiptId)}/approval`);
}

/** A confirmed deposit whose money never really arrived, taken back off the balance (money movement step 3b). */
export type DepositReversal = BillingComponents['schemas']['DepositReversal'];

/** `GET /deposits/{id}/reversals` -- the reversals asked of one deposit, newest first. */
export function listDepositReversals(paymentId: string): Promise<DepositReversal[]> {
  return get<DepositReversal[]>(`/deposits/${encodeURIComponent(paymentId)}/reversals`);
}

/** `POST /deposits/{id}/reversal` -- collections.manage asks, saying why; a second person confirms. */
export function requestDepositReversal(paymentId: string, reason: string): Promise<DepositReversal> {
  return post<DepositReversal>(`/deposits/${encodeURIComponent(paymentId)}/reversal`, { reason });
}

/** Confirm (reason null) or reject (with the reason) a reversal -- collections.approve. */
export function decideDepositReversal(reversalId: string, rejectReason: string | null): Promise<DepositReversal> {
  const id = encodeURIComponent(reversalId);
  return rejectReason === null
    ? post<DepositReversal>(`/deposit-reversals/${id}/confirm`, {})
    : post<DepositReversal>(`/deposit-reversals/${id}/reject`, { reason: rejectReason });
}

export function rejectReceipt(receiptId: string, reason: string): Promise<Receipt> {
  return post<Receipt>(`/receipts/${encodeURIComponent(receiptId)}/rejection`, { reason });
}
