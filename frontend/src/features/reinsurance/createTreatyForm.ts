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
  // IFRS 17 I3c (K-02): every treaty states its commission not contingent on claims; 0 is a real answer and blank
  // is not -- the platform never assumes the guide's illustrative 20%.
  commissionPercent: z
    .string()
    .trim()
    .min(1, "State the reinsurer's commission -- 0 when it pays none")
    .regex(/^\d+(\.\d{1,2})?$/, 'Must be a decimal percentage like 20.00')
    .refine((v) => Number(v) <= 100, 'Cannot exceed 100'),
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
const xolSchema = z.object({
  treatyType: z.literal('XOL'),
  // Optional: an XOL treaty's flat yearly premium, charged one twelfth on each monthly bordereau.
  xolAnnualPremium: z
    .string()
    .trim()
    .refine((v) => v === '' || (AMOUNT_PATTERN.test(v) && Number(v) > 0), 'Must be an amount greater than 0'),
  ...common,
});

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
    commissionPercent: '',
    cessionPercent: '',
  };
}

export function blankNonQuotaShareTreatyForm(treatyType: 'SURPLUS' | 'XOL'): CreateTreatyFormValues {
  const common = {
    reinsurerName: '',
    retentionLimitAmount: '',
    retentionLimitCurrency: 'TZS',
    effectiveFrom: '',
    effectiveTo: '',
    commissionPercent: '',
  };
  return treatyType === 'XOL' ? { treatyType, xolAnnualPremium: '', ...common } : { treatyType, ...common };
}

export function toApiRequest(values: CreateTreatyFormValues): CreateTreatyRequest {
  return {
    reinsurerName: values.reinsurerName.trim(),
    treatyType: values.treatyType,
    retentionLimit: { amount: values.retentionLimitAmount, currencyCode: values.retentionLimitCurrency },
    cessionPercent: values.treatyType === 'QUOTA_SHARE' ? values.cessionPercent : null,
    commissionPercent: values.commissionPercent.trim(),
    xolAnnualPremium:
      values.treatyType === 'XOL' && values.xolAnnualPremium !== '' ? values.xolAnnualPremium : null,
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}
