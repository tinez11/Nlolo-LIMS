import { z } from 'zod';
import type { ReopenClaimRequest } from '@/api/types';

/** Zod schema for `POST /claims/{claimId}/reopen`, mirroring `ReopenClaimRequestDto`
 *  exactly: `reason` is the only field, and it is required. */
export const reopenClaimFormSchema = z.object({
  reason: z.string().trim().min(1, 'A reason is required'),
});

export type ReopenClaimFormValues = z.infer<typeof reopenClaimFormSchema>;

export function blankReopenClaimForm(): ReopenClaimFormValues {
  return { reason: '' };
}

export function toApiRequest(values: ReopenClaimFormValues): ReopenClaimRequest {
  return { reason: values.reason.trim() };
}
