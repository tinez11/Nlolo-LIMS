import { z } from 'zod';
import type { WaiverRequest } from '@/api/types';

/** Zod schema for `POST /invoices/{invoiceId}/waiver`, mirroring
 *  `WaiverRequestDto` exactly: `reason` is required and at least 10 characters
 *  (`@Size(min = 10)`), matching the audit-relevance the field exists for. */
export const waiveInvoiceFormSchema = z.object({
  reason: z.string().trim().min(10, 'Must be at least 10 characters'),
});

export type WaiveInvoiceFormValues = z.infer<typeof waiveInvoiceFormSchema>;

export function blankWaiveInvoiceForm(): WaiveInvoiceFormValues {
  return { reason: '' };
}

export function toApiRequest(values: WaiveInvoiceFormValues): WaiverRequest {
  return { reason: values.reason.trim() };
}
