import { z } from 'zod';

/**
 * A withholding rule as finance enters it (product step 5). The checks and their words mirror
 * WithholdingRules.propose, so the form refuses what the server would, in the same words.
 */
export const withholdingRuleSchema = z
  .object({
    annuity: z.boolean(),
    ratePercent: z
      .string()
      .trim()
      .regex(/^\d{1,2}(\.\d{1,4})?$/, 'A rate with at most four decimals, for example 10')
      .refine((v) => Number(v) > 0 && Number(v) < 100, 'A withholding rate must be greater than 0% and less than 100%'),
    effectiveFrom: z.string().trim().min(1, 'A withholding rule needs the date it takes effect'),
    effectiveTo: z.string().trim(),
    legalReference: z.string().trim().min(1, 'A withholding rule names the law or ruling it applies').max(200),
  })
  .superRefine((v, ctx) => {
    if (!v.annuity) {
      ctx.addIssue({ code: 'custom', path: ['annuity'], message: 'Choose the payouts this rule withholds from' });
    }
    if (v.effectiveTo !== '' && v.effectiveFrom !== '' && v.effectiveTo < v.effectiveFrom) {
      ctx.addIssue({ code: 'custom', path: ['effectiveTo'], message: 'A withholding rule cannot end before it begins' });
    }
  });
export type WithholdingRuleValues = z.infer<typeof withholdingRuleSchema>;

/**
 * Ending an approved rule (product step 5): one finance officer, alone. A factory because the checks
 * read the rule and today. Mirrors WithholdingRule.end, in its words.
 */
export function endRuleSchema(rule: { effectiveFrom: string; effectiveTo?: string | null }, today: string) {
  return z.object({ effectiveTo: z.string().trim() }).superRefine((v, ctx) => {
    const issue = (message: string) => ctx.addIssue({ code: 'custom', path: ['effectiveTo'], message });
    if (v.effectiveTo === '') return issue('Ending a withholding rule needs its last day');
    if (v.effectiveTo < rule.effectiveFrom) return issue('A withholding rule cannot end before it begins');
    if (v.effectiveTo < today) {
      return issue(
        'A withholding rule cannot be ended in the past -- payouts due before today and not yet approved would lose the tax it withholds',
      );
    }
    if (rule.effectiveTo && v.effectiveTo > rule.effectiveTo) {
      issue(
        `This rule already ends on ${rule.effectiveTo}; it can be ended earlier, never extended -- propose a new rule for the later period`,
      );
    }
  });
}
export type EndRuleValues = { effectiveTo: string };
