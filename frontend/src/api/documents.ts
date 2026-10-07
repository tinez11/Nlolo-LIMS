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

/** The file name a download is saved under. */
export function documentFileName(kind: 'payment-schedule' | 'savings-statement', policyNumber: string,
                                 format: DocumentFormat, period?: { from: string; to: string }): string {
  return `${kind}-${policyNumber}${period ? `-${period.from}-to-${period.to}` : ''}.${format}`;
}
