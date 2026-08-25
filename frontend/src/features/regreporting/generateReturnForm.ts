import { z } from 'zod';
import type { GenerateReturnRequest } from '@/api/types';

/**
 * Zod schema for `POST /regulatory-returns`, mirroring `GenerateReturnRequestDto`
 * exactly: both fields are required non-blank strings, deliberately not a closed
 * enum/pattern -- `returnType` is validated against seeded data server-side, and
 * `period`'s expected format (YYYY-Qn vs YYYY) depends on that return type's own
 * periodKind, which only the service layer knows.
 */
export const generateReturnFormSchema = z.object({
  returnType: z.string().trim().min(1, 'Return type is required'),
  period: z.string().trim().min(1, 'Period is required'),
});

export type GenerateReturnFormValues = z.infer<typeof generateReturnFormSchema>;

export function blankGenerateReturnForm(): GenerateReturnFormValues {
  return { returnType: '', period: '' };
}

export function toApiRequest(values: GenerateReturnFormValues): GenerateReturnRequest {
  return { returnType: values.returnType.trim(), period: values.period.trim() };
}
