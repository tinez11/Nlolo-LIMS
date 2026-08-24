import { z } from 'zod';
import type { CreateCommissionPlanRequest } from '@/api/types';
import { CURRENCY_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for `POST /commission-plans`, mirroring `CreateCommissionPlanRequestDto`
 * and `DistributionApiImpl.validateRuleShape` exactly.
 *
 * Each rule is rate-XOR-flat on the wire (`CommissionRuleInput`'s own doc: "Exactly
 * one of rate or flatAmount must be present"), which has no `mode` field of its
 * own -- `mode` here is a FORM-ONLY discriminant (like beneficiaries' PARTY/
 * FREEFORM type), stripped out by `toApiRequest` rather than sent to the server.
 * A `z.discriminatedUnion` on it makes "exactly one of rate or flat" structural
 * instead of a `superRefine` duplicating a rule the domain already enforces.
 *
 * `rate`'s pattern (`^\d+(\.\d{1,4})?$`, up to 4 decimal places) is
 * `CreateCommissionPlanRequestDto.RuleInput`'s own `@Pattern`, transcribed
 * exactly -- a commission rate multiplies money, so it gets the same
 * never-a-float treatment as every other money-adjacent value on this platform.
 */

const RATE_PATTERN = /^\d+(\.\d{1,4})?$/;

const TIER_TYPE_ENUM = z.enum(['FIRST_YEAR', 'RENEWAL', 'OVERRIDE', 'SUPERVISOR_OVERRIDE']);

const rateRowSchema = z.object({
  mode: z.literal('rate'),
  tierType: TIER_TYPE_ENUM,
  rate: z
    .string()
    .regex(RATE_PATTERN, 'Must be a decimal like 0.1000 (max 4 decimal places)')
    .refine((v) => Number(v) > 0, 'Must be positive'),
});

const flatRowSchema = z.object({
  mode: z.literal('flat'),
  tierType: TIER_TYPE_ENUM,
  flatAmount: z
    .string()
    .regex(/^\d+(\.\d{1,2})?$/, 'Must be a decimal amount like 5000.00')
    .refine((v) => Number(v) > 0, 'Must be positive'),
  flatCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
});

const ruleRowSchema = z.discriminatedUnion('mode', [rateRowSchema, flatRowSchema]);

export const createCommissionPlanFormSchema = z.object({
  productId: z.string().trim().min(1, 'Select a product'),
  rules: z.array(ruleRowSchema).min(1, 'A commission plan must have at least one rule'),
});

export type CreateCommissionPlanFormValues = z.infer<typeof createCommissionPlanFormSchema>;
export type RuleRowFormValues = CreateCommissionPlanFormValues['rules'][number];
export type PlannableTierType = z.infer<typeof TIER_TYPE_ENUM>;
export const PLANNABLE_TIER_TYPES: readonly PlannableTierType[] = [
  'FIRST_YEAR',
  'RENEWAL',
  'OVERRIDE',
  'SUPERVISOR_OVERRIDE',
];

export function blankCreateCommissionPlanForm(productId = ''): CreateCommissionPlanFormValues {
  return { productId, rules: [blankRateRow('FIRST_YEAR')] };
}

export function blankRateRow(tierType: PlannableTierType): RuleRowFormValues {
  return { mode: 'rate', tierType, rate: '' };
}

export function blankFlatRow(tierType: PlannableTierType): RuleRowFormValues {
  return { mode: 'flat', tierType, flatAmount: '', flatCurrency: 'TZS' };
}

export function toApiRequest(values: CreateCommissionPlanFormValues): CreateCommissionPlanRequest {
  return {
    productId: values.productId,
    rules: values.rules.map((row) =>
      row.mode === 'rate'
        ? { tierType: row.tierType, rate: row.rate, flatAmount: null }
        : {
            tierType: row.tierType,
            rate: null,
            flatAmount: { amount: row.flatAmount, currencyCode: row.flatCurrency },
          },
    ),
  };
}
