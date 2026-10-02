import { z } from 'zod';
import { canSeeFinance, type TokenIdentity } from '@/auth/claims';

/** The column is NUMERIC(7,4): 0-100 with at most four decimals. */
export const rateDeclarationSchema = z.object({
  ratePercent: z
    .string()
    .trim()
    .regex(/^\d{1,3}(\.\d{1,4})?$/, 'A rate with at most four decimals, for example 6.5')
    .refine((v) => Number(v) >= 0 && Number(v) <= 100, 'A declared rate must be between 0% and 100%'),
  effectiveFrom: z.string().trim().min(1, 'When does it take effect?'),
});
export type RateDeclarationValues = z.infer<typeof rateDeclarationSchema>;

/** Whether the rates panel belongs on this product page at all. */
export function showsRates(category: string | undefined, identity: TokenIdentity): boolean {
  return ['ENDOWMENT', 'WHOLE_LIFE', 'EDUCATION_SAVINGS'].includes(category ?? '') && canSeeFinance(identity);
}
