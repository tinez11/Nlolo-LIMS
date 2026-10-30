import { can } from '@/auth/access';
import { z } from 'zod';
import type { VersionValueTermsView } from '@/api/types';
import { canSeeFinance, type TokenIdentity } from '@/auth/claims';
import { sharesInProfits } from '@/features/products/valueTermsText';

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
 * Whether the bonus panel belongs on this product page: only where a version shares in profits (2026-10-29). It went
 * by category, so every endowment and whole life product -- with-profits or not -- offered a declaration.
 */
export function showsBonuses(versions: readonly VersionValueTermsView[] | null, identity: TokenIdentity): boolean {
  return versions != null && sharesInProfits(versions) && canSeeFinance(identity) && can('products.view');
}
