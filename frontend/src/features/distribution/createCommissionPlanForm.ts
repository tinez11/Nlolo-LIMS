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

/**
 * The rate is typed as a PERCENT (2026-11-12, finance finding 187): "5" is 5%. It used to be a fraction ("0.1000"),
 * and "5" -- meant as 5% -- was saved as 500%, crediting an agent TZS 10M on a 2M deposit. Up to two decimals of a
 * percent is the server's four decimals of a fraction, converted exactly by {@link percentToFraction}.
 */
const PERCENT_PATTERN = /^\d+(\.\d{1,2})?$/;

/** The stored fraction as a percent to read: "0.0500" -> "5", "0.1250" -> "12.5", "5.0000" -> "500". */
export function fractionToPercent(fraction: string): string {
  const [whole = '0', part = ''] = fraction.trim().split('.');
  const tenThousandths = Number(whole) * 10000 + Number(part.padEnd(4, '0').slice(0, 4));
  const percent = `${Math.floor(tenThousandths / 100)}.${String(tenThousandths % 100).padStart(2, '0')}`;
  return percent.replace(/\.?0+$/, '');
}

/** "5" -> "0.0500", "12.5" -> "0.1250", "100" -> "1.0000": string arithmetic, never a float. */
export function percentToFraction(percent: string): string {
  const [whole = '0', part = ''] = percent.trim().split('.');
  const hundredths = Number(whole) * 100 + Number(part.padEnd(2, '0').slice(0, 2));
  return `${Math.floor(hundredths / 10000)}.${String(hundredths % 10000).padStart(4, '0')}`;
}

const TIER_TYPE_ENUM = z.enum(['FIRST_YEAR', 'RENEWAL', 'OVERRIDE', 'SUPERVISOR_OVERRIDE']);

const rateRowSchema = z.object({
  mode: z.literal('rate'),
  tierType: TIER_TYPE_ENUM,
  rate: z
    .string()
    .trim()
    .regex(PERCENT_PATTERN, 'Type a percent, like 5 or 12.5')
    .refine((v) => Number(v) > 0, 'Must be more than 0%')
    .refine((v) => Number(v) <= 100, 'Cannot be more than 100% of the premium'),
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
        ? { tierType: row.tierType, rate: percentToFraction(row.rate), flatAmount: null }
        : {
            tierType: row.tierType,
            rate: null,
            flatAmount: { amount: row.flatAmount, currencyCode: row.flatCurrency },
          },
    ),
  };
}
