import { get } from '@/lib/http';
import type { components } from '@/types/api/omnichannel';

/**
 * The documents a customer can ask for (2026-10-07): a policy's payment schedule and a savings plan's account
 * statement, as JSON for the table and as a PDF or Excel file to hand over. Staff read any policy; a customer
 * only their own.
 */

export type PaymentScheduleView = components['schemas']['PaymentSchedule'];
export type PaymentScheduleLine = PaymentScheduleView['lines'][number];
export type SavingsStatementView = components['schemas']['SavingsStatement'];
export type SavingsStatementLine = SavingsStatementView['lines'][number];
export type DocumentFormat = 'pdf' | 'xlsx';

const enc = encodeURIComponent;
const ACCEPT: Record<DocumentFormat, string> = {
  pdf: 'application/pdf',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
};

export function getPaymentSchedule(policyNumber: string): Promise<PaymentScheduleView> {
  return get<PaymentScheduleView>(`/policies/${enc(policyNumber)}/payment-schedule`);
}

export function downloadPaymentSchedule(policyNumber: string, format: DocumentFormat): Promise<Blob> {
  return get<Blob>(`/policies/${enc(policyNumber)}/payment-schedule/${format}`,
    { responseType: 'blob', headers: { Accept: ACCEPT[format] } });
}

export function getSavingsStatement(policyNumber: string, from: string, to: string): Promise<SavingsStatementView> {
  return get<SavingsStatementView>(`/policies/${enc(policyNumber)}/savings-statement`, { params: { from, to } });
}

export function downloadSavingsStatement(policyNumber: string, format: DocumentFormat, from: string,
                                         to: string): Promise<Blob> {
  return get<Blob>(`/policies/${enc(policyNumber)}/savings-statement/${format}`,
    { params: { from, to }, responseType: 'blob', headers: { Accept: ACCEPT[format] } });
}

export type ReceiptLine = components['schemas']['ReceiptLine'];

/** The policy schedule (2026-10-08, customer portal step 3): what the policy is, on one page. PDF only. */
export function downloadPolicySchedule(policyNumber: string): Promise<Blob> {
  return get<Blob>(`/policies/${enc(policyNumber)}/policy-schedule/pdf`,
    { responseType: 'blob', headers: { Accept: ACCEPT.pdf } });
}

/** A fixed-term deposit's schedule (2026-10-09): maturity figures and the value if closed at each month. PDF only. */
export function downloadDepositSchedule(policyNumber: string): Promise<Blob> {
  return get<Blob>(`/policies/${enc(policyNumber)}/deposit-schedule/pdf`,
    { responseType: 'blob', headers: { Accept: ACCEPT.pdf } });
}

/** Every premium received on the policy, newest first. */
export function listReceipts(policyNumber: string): Promise<ReceiptLine[]> {
  return get<ReceiptLine[]>(`/policies/${enc(policyNumber)}/receipts`);
}

export function downloadReceipt(policyNumber: string, receiptId: string): Promise<Blob> {
  return get<Blob>(`/policies/${enc(policyNumber)}/receipts/${enc(receiptId)}/pdf`,
    { responseType: 'blob', headers: { Accept: ACCEPT.pdf } });
}

/** The file name a download is saved under. */
export function documentFileName(kind: 'payment-schedule' | 'savings-statement', policyNumber: string,
                                 format: DocumentFormat, period?: { from: string; to: string }): string {
  return `${kind}-${policyNumber}${period ? `-${period.from}-to-${period.to}` : ''}.${format}`;
}
