import { z } from 'zod';
import type { CreateTreatyRequest } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, ISO_DATE_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for `POST /treaties`, transcribed from `ReinsuranceApiImpl.createTreaty`
 * exactly (not guessed from the spec, which deliberately leaves the
 * type-XOR-cessionPercent rule unvalidated at the bean-validation layer so a
 * violation lands on one 422, not a 400 from one layer and a 422 from another).
 *
 * A `z.discriminatedUnion` on `treatyType` makes that rule structural: only the
 * `QUOTA_SHARE` branch has a `cessionPercent` field at all, so SURPLUS/XOL
 * simply cannot carry one, the same way claims' `ClaimDetails` union makes an
 * impossible field combination unrepresentable rather than separately checked.
 */

const amount = () =>
  z
    .string()
    .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 5000000.00')
    .refine((v) => Number(v) >= 0, 'Must be zero or positive');

const currency = () => z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS');

const isoDate = (message: string) =>
  z.string().trim().min(1, message).regex(ISO_DATE_PATTERN, message);

const optionalIsoDate = () =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date');

const common = {
  reinsurerName: z.string().trim().min(1, 'A reinsurer name is required').max(200, 'Cannot exceed 200 characters'),
  retentionLimitAmount: amount(),
  retentionLimitCurrency: currency(),
  effectiveFrom: isoDate('Effective-from date is required'),
  effectiveTo: optionalIsoDate(),
};

const quotaShareSchema = z.object({
  treatyType: z.literal('QUOTA_SHARE'),
  cessionPercent: z
    .string()
    .regex(/^\d+(\.\d{1,2})?$/, 'Must be a decimal percentage like 25.00')
    .refine((v) => Number(v) > 0 && Number(v) <= 100, 'Must be greater than 0 and at most 100'),
  ...common,
});

const surplusSchema = z.object({ treatyType: z.literal('SURPLUS'), ...common });
const xolSchema = z.object({ treatyType: z.literal('XOL'), ...common });

export const createTreatyFormSchema = z
  .discriminatedUnion('treatyType', [quotaShareSchema, surplusSchema, xolSchema])
  .superRefine((values, ctx) => {
    if (values.effectiveTo !== '' && values.effectiveTo < values.effectiveFrom) {
      ctx.addIssue({
        code: 'custom',
        message: 'Effective-to cannot be before effective-from',
        path: ['effectiveTo'],
      });
    }
  });

export type CreateTreatyFormValues = z.infer<typeof createTreatyFormSchema>;

export function blankQuotaShareTreatyForm(): CreateTreatyFormValues {
  return {
    treatyType: 'QUOTA_SHARE',
    reinsurerName: '',
    retentionLimitAmount: '',
    retentionLimitCurrency: 'TZS',
    effectiveFrom: '',
    effectiveTo: '',
    cessionPercent: '',
  };
}

export function blankNonQuotaShareTreatyForm(treatyType: 'SURPLUS' | 'XOL'): CreateTreatyFormValues {
  return {
    treatyType,
    reinsurerName: '',
    retentionLimitAmount: '',
    retentionLimitCurrency: 'TZS',
    effectiveFrom: '',
    effectiveTo: '',
  };
}

export function toApiRequest(values: CreateTreatyFormValues): CreateTreatyRequest {
  return {
    reinsurerName: values.reinsurerName.trim(),
    treatyType: values.treatyType,
    retentionLimit: { amount: values.retentionLimitAmount, currencyCode: values.retentionLimitCurrency },
    cessionPercent: values.treatyType === 'QUOTA_SHARE' ? values.cessionPercent : null,
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}
