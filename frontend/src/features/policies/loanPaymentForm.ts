import { z } from 'zod';
import type { LoanPaymentRequest } from '@/api/types';

/**
 * Zod schema for `POST /loans/{loanId}/payment-request` (money movement step 1, 2026-11-04): the payer's
 * mobile-money number and how much to ask for. The amount defaults to what the loan owes as the server stated it;
 * the server refuses more than that, so no ceiling is computed here (interest accrues daily and any browser figure
 * would be stale).
 */
export const loanPaymentFormSchema = z.object({
  payerRef: z.string().trim().min(1, 'A payer reference is required'),
  amount: z
    .string()
    .trim()
    .regex(/^\d+(\.\d{1,2})?$/, 'Enter an amount in shillings, up to 2 decimal places')
    .refine((value) => Number(value) > 0, 'More than zero'),
});

export type LoanPaymentFormValues = z.infer<typeof loanPaymentFormSchema>;

export function blankLoanPaymentForm(owed: string): LoanPaymentFormValues {
  return { payerRef: '', amount: owed };
}

export function toLoanPaymentRequest(values: LoanPaymentFormValues, currency: string): LoanPaymentRequest {
  return { payerRef: values.payerRef.trim(), amount: { amount: values.amount.trim(), currencyCode: currency } };
}
