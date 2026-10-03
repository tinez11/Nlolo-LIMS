import { z } from 'zod';
import { canSeeFinance, type TokenIdentity } from '@/auth/claims';

/**
 * Both columns are NUMERIC(7,4): at most four decimals. The ranges and their words are
 * BonusApiImpl.proposeDeclaration's.
 */
const rate = (max: number, message: string) =>
  z
    .string()
    .trim()
    .regex(/^\d{1,4}(\.\d{1,4})?$/, 'A rate with at most four decimals, for example 3.5')
    .refine((v) => Number(v) >= 0 && Number(v) <= max, message);

export const bonusDeclarationSchema = z.object({
  valuationDate: z.string().trim().min(1, 'As at which valuation date?'),
  reversionaryRatePercent: rate(100, 'A reversionary bonus rate must be between 0% and 100%'),
  terminalRatePercent: rate(1000, 'A terminal bonus rate must be between 0% and 1000% of attached bonuses'),
});
export type BonusDeclarationValues = z.infer<typeof bonusDeclarationSchema>;

/**
 * Whether the bonus panel belongs on this product page. BonusPlanValidator allows with-profits on
 * ENDOWMENT and WHOLE_LIFE only. No product read says whether a version IS with-profits, so a
 * product in those categories with none is answered by the server's own refusal, as showsRates does.
 */
export function showsBonuses(category: string | undefined, identity: TokenIdentity): boolean {
  return ['ENDOWMENT', 'WHOLE_LIFE'].includes(category ?? '') && canSeeFinance(identity);
}
