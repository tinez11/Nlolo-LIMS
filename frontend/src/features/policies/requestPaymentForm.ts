import { z } from 'zod';
import type { PaymentRequest } from '@/api/types';

/** Zod schema for `POST /invoices/{invoiceId}/payment-request`, mirroring
 *  `PaymentRequestDto` exactly: `payerRef` (the payer's mobile-money MSISDN)
 *  is the only field. */
export const requestPaymentFormSchema = z.object({
  payerRef: z.string().trim().min(1, 'A payer reference is required'),
});

export type RequestPaymentFormValues = z.infer<typeof requestPaymentFormSchema>;

export function blankRequestPaymentForm(): RequestPaymentFormValues {
  return { payerRef: '' };
}

export function toApiRequest(values: RequestPaymentFormValues): PaymentRequest {
  return { payerRef: values.payerRef.trim() };
}
