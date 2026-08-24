import { z } from 'zod';
import type { ProductCategory, ProductVersionSpec } from '@/api/types';
import { ISO_DATE_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for publishing a product version, mirroring `ProductApiImpl.publishVersion`
 * exactly -- this is the ONLY way a product ever becomes visible through
 * `GET /products` (it flips status DRAFT -> ACTIVE), so getting these rules right
 * matters more than it might look for "just a version record".
 *
 * A FACTORY, not a static schema: whether `fundDefinitions` is allowed depends on
 * the PRODUCT being published to (`category`), which is not itself a field on
 * this form -- it is external context the caller already knows (the product was
 * already selected before reaching this form).
 */

const ratingFactorRowSchema = z.object({
  factorType: z.enum(['AGE', 'OCCUPATION_CLASS', 'SMOKER_STATUS', 'SUM_ASSURED_BAND']),
  band: z.string().trim().min(1, 'Band is required'),
  multiplier: z.coerce.number(),
});

const benefitRowSchema = z.object({
  benefitType: z.enum(['DEATH', 'DISABILITY', 'CRITICAL_ILLNESS', 'MATURITY', 'SURRENDER']),
  calculationMethod: z.string().trim().min(1, 'Calculation method is required'),
});

const fundRowSchema = z.object({
  fundCode: z.string().trim().min(1, 'Fund code is required'),
  currentNav: z.coerce.number(),
});

export function publishVersionFormSchema(category: ProductCategory) {
  return z.object({
    ifrsMeasurementModel: z.enum(['GMM', 'PAA']),
    effectiveDate: z.string().trim().min(1, 'Effective date is required').regex(ISO_DATE_PATTERN),
    // Blank means "no retirement date" -- toApiRequest converts that to null.
    retirementDate: z
      .string()
      .trim()
      .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
    ratingTable: z.array(ratingFactorRowSchema).superRefine((rows, ctx) => {
      // ProductApiImpl.publishVersion: coveredFactorTypes.containsAll(AGE,
      // SUM_ASSURED_BAND) or 422 -- "every declared FactorType must have at
      // least one band defined so the rules engine never silently falls back
      // to a neutral 1.0 for a factor type this product intended to rate on."
      const covered = new Set(rows.map((r) => r.factorType));
      if (!covered.has('AGE') || !covered.has('SUM_ASSURED_BAND')) {
        ctx.addIssue({
          code: 'custom',
          message: 'Rating table must cover at least AGE and SUM_ASSURED_BAND',
        });
      }
    }),
    benefitSchedule: z.array(benefitRowSchema), // no minimum coverage required
    fundDefinitions: z.array(fundRowSchema).superRefine((rows, ctx) => {
      // ProductApiImpl.publishVersion: rejected outright for any category other
      // than UNIT_LINKED, even a well-formed one.
      if (rows.length > 0 && category !== 'UNIT_LINKED') {
        ctx.addIssue({
          code: 'custom',
          message: 'Fund definitions are only valid for UNIT_LINKED products',
        });
      }
    }),
  });
}

/**
 * Input/Output split, same reason as elsewhere: multiplier/currentNav use
 * `z.coerce.number()`, so the raw form value (what register()/watch() see) and
 * the validated value (what handleSubmit's callback receives) genuinely differ.
 */
export type PublishVersionFormValues = z.output<ReturnType<typeof publishVersionFormSchema>>;
export type PublishVersionFormInput = z.input<ReturnType<typeof publishVersionFormSchema>>;

export function blankPublishVersionForm(): PublishVersionFormInput {
  return {
    ifrsMeasurementModel: 'PAA',
    effectiveDate: '',
    retirementDate: '',
    ratingTable: [],
    benefitSchedule: [],
    fundDefinitions: [],
  };
}

export function blankRatingFactorRow(): PublishVersionFormInput['ratingTable'][number] {
  return { factorType: 'AGE', band: '', multiplier: 1 };
}

export function blankBenefitRow(): PublishVersionFormInput['benefitSchedule'][number] {
  return { benefitType: 'DEATH', calculationMethod: '' };
}

export function blankFundRow(): PublishVersionFormInput['fundDefinitions'][number] {
  return { fundCode: '', currentNav: 0 };
}

export function toApiRequest(values: PublishVersionFormValues): ProductVersionSpec {
  return {
    ifrsMeasurementModel: values.ifrsMeasurementModel,
    effectiveDate: values.effectiveDate,
    retirementDate: values.retirementDate === '' ? null : values.retirementDate,
    ratingTable: values.ratingTable,
    benefitSchedule: values.benefitSchedule,
    fundDefinitions: values.fundDefinitions,
  };
}
