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
