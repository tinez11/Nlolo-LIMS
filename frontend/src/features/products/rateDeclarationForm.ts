import { can } from '@/auth/access';
import { z } from 'zod';
import type { VersionValueTermsView } from '@/api/types';
import { canSeeFinance, type TokenIdentity } from '@/auth/claims';
import { creditsDeclaredRates } from './valueTermsText';

/** The column is NUMERIC(7,4): 0-100 with at most four decimals. */
export const rateDeclarationSchema = z.object({
  ratePercent: z
    .string()
    .trim()
    // One message, the first that applies (2026-11-07): an empty field was told about four decimals, which read as a
    // complaint about a value never typed.
    .superRefine((v, ctx) => {
      if (v === '') ctx.addIssue({ code: 'custom', message: 'Enter the rate (% a year)' });
      else if (!/^\d{1,3}(\.\d{1,4})?$/.test(v)) {
        ctx.addIssue({ code: 'custom', message: 'A rate with at most four decimals, for example 6.5' });
      } else if (Number(v) > 100) ctx.addIssue({ code: 'custom', message: 'A declared rate must be between 0% and 100%' });
    }),
  effectiveFrom: z.string().trim().min(1, 'When does it take effect?'),
});
export type RateDeclarationValues = z.infer<typeof rateDeclarationSchema>;

/**
 * Whether the rates panel belongs on this product page at all: only where a version is a savings account, the one kind
 * a declaration credits (2026-10-29). It went by category, so a cash-value endowment, a deposit and a term account
 * offered declarations that touch nothing, and a deferred annuity's account -- which does earn them -- offered none.
 */
export function showsRates(versions: readonly VersionValueTermsView[] | null, identity: TokenIdentity): boolean {
  return versions != null && creditsDeclaredRates(versions) && canSeeFinance(identity) && can('products.view');
}
