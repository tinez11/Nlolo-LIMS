import { z } from 'zod';
import type { RequestPayoutRequest } from '@/api/types';

/** Zod schema for `POST .../payout`, mirroring `RequestPayoutRequestDto`
 *  exactly: `payeeRef` (the agent's mobile-money MSISDN) is the only field. */
export const requestPayoutFormSchema = z.object({
  payeeRef: z.string().trim().min(1, 'A payee reference is required'),
});

export type RequestPayoutFormValues = z.infer<typeof requestPayoutFormSchema>;

export function blankRequestPayoutForm(): RequestPayoutFormValues {
  return { payeeRef: '' };
}

export function toApiRequest(values: RequestPayoutFormValues): RequestPayoutRequest {
  return { payeeRef: values.payeeRef.trim() };
}
