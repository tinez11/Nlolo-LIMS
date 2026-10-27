import { z } from 'zod';
import type { ReceiptMethod, RecordReceipt } from '@/api/receipts';
import { todayIso } from '@/lib/dates';

/**
 * Each way a premium can come in by hand (2026-10-27), with the reference its own paperwork carries. The reference is
 * what stops one slip being recorded twice, so its label says exactly which number to copy.
 */
export const RECEIPT_METHODS: { value: ReceiptMethod; label: string; reference: string; hint: string }[] = [
  { value: 'BANK_DEPOSIT', label: 'Bank deposit', reference: 'Bank slip or transfer reference',
    hint: 'Paid into the insurer’s bank account at a branch or by transfer.' },
  { value: 'CASH', label: 'Cash', reference: 'Cash receipt number',
    hint: 'Notes and coins, at a branch or to an agent. Posted to the premium bank account it is banked into.' },
  { value: 'CHEQUE', label: 'Cheque', reference: 'Cheque number',
    hint: 'Record it once the cheque has cleared.' },
  { value: 'LIPA_NUMBER', label: 'Lipa number', reference: 'Transaction ID from the confirmation message',
    hint: 'Paid to the insurer’s mobile-money merchant till.' },
  { value: 'TANQR', label: 'TANQR', reference: 'TANQR reference from the payer’s app',
    hint: 'Paid by scanning the insurer’s TANQR code.' },
];

export function methodOf(value: string) {
  return RECEIPT_METHODS.find((m) => m.value === value);
}

/** Mirrors Receipts.record: an invoice, a method and its reference, an amount above zero, a day no later than today. */
export const receiptFormSchema = z.object({
  policyNumber: z.string().trim().min(1, 'Enter the policy number'),
  invoiceId: z.string().min(1, 'Choose the invoice this pays'),
  method: z.enum(['CASH', 'BANK_DEPOSIT', 'CHEQUE', 'LIPA_NUMBER', 'TANQR'], { message: 'Choose how the money came in' }),
  reference: z.string().trim().min(1, 'Enter the reference, so the same money cannot be recorded twice').max(100),
  amount: z
    .string()
    .trim()
    .regex(/^\d+(\.\d{1,2})?$/, 'An amount like 120000 or 120000.50')
    .refine((v) => Number(v) > 0, 'More than zero'),
  receivedOn: z
    .string()
    .min(1, 'The day the money was received')
    .refine((v) => v <= todayIso(), 'It cannot be later than today'),
  payerRef: z.string().trim().max(255),
  note: z.string().trim().max(500),
  fieldReceiptId: z.string(),
});

export type ReceiptFormValues = z.infer<typeof receiptFormSchema>;

export function blankReceiptForm(prefill: Partial<ReceiptFormValues> = {}): ReceiptFormValues {
  return {
    policyNumber: '',
    invoiceId: '',
    method: 'BANK_DEPOSIT',
    reference: '',
    amount: '',
    receivedOn: todayIso(),
    payerRef: '',
    note: '',
    fieldReceiptId: '',
    ...prefill,
  };
}

export function toApiRequest(values: ReceiptFormValues, currency: string): RecordReceipt {
  return {
    invoiceId: values.invoiceId,
    method: values.method,
    reference: values.reference.trim(),
    amount: { amount: Number(values.amount).toFixed(2), currencyCode: currency },
    receivedOn: values.receivedOn,
    payerRef: values.payerRef.trim() || null,
    note: values.note.trim() || null,
    fieldReceiptId: values.fieldReceiptId || null,
  };
}
