import { z } from 'zod';
import type { LoanRepaymentRequest } from '@/api/types';

/**
 * Zod schema for `POST /loans/{loanId}/repayments`, mirroring
 * `RepaymentRequestDto`: a `Money` amount plus the `paymentReference` that ties
 * this repayment to the money actually received.
 *
 * Same string-not-number and 2-decimal reasoning as `originateLoanForm` --
 * `loan_transaction.amount` is `NUMERIC(19,2)` and the wire contract is a
 * decimal string.
 *
 * Overpayment is NOT rejected here. The outstanding balance moves on its own
 * between render and submit (interest accrues daily), so any client-side
 * ceiling would be stale, and the platform accepts an overpayment by design:
 * the balance folds to zero or below and the loan settles. Rejecting it locally
 * would invent a rule the backend does not have.
 */
export const loanRepaymentFormSchema = z.object({
  amount: z
    .string()
    .trim()
    .min(1, 'A repayment amount is required')
    .regex(/^\d+(\.\d{1,2})?$/, 'Enter an amount in shillings, up to 2 decimal places')
    .refine((value) => Number(value) > 0, 'A repayment must be for more than zero'),
  paymentReference: z.string().trim().min(1, 'A payment reference is required'),
});

export type LoanRepaymentFormValues = z.infer<typeof loanRepaymentFormSchema>;

export function blankLoanRepaymentForm(): LoanRepaymentFormValues {
  return { amount: '', paymentReference: '' };
}

export function toApiRequest(values: LoanRepaymentFormValues): LoanRepaymentRequest {
  return {
    amount: { amount: values.amount.trim(), currencyCode: 'TZS' },
    paymentReference: values.paymentReference.trim(),
  };
}
