import { z } from 'zod';
import type { SuspendPolicyRequest } from '@/api/types';

/** Zod schema for `POST /policies/{n}/suspend`, mirroring `SuspendPolicyRequestDto`
 *  exactly: `reason` is required (`@NotBlank`), no minimum length. */
export const suspendPolicyFormSchema = z.object({
  reason: z.string().trim().min(1, 'Reason is required'),
});

export type SuspendPolicyFormValues = z.infer<typeof suspendPolicyFormSchema>;

export function blankSuspendPolicyForm(): SuspendPolicyFormValues {
  return { reason: '' };
}

export function toApiRequest(values: SuspendPolicyFormValues): SuspendPolicyRequest {
  return { reason: values.reason.trim() };
}
