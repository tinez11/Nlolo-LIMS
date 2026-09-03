import { z } from 'zod';
import type { ProductCategory, ProductVersionSpec } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
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

/**
 * `ageFrom`/`ageTo` are kept as strings in the form and coerced on submit, the same split
 * the file header already describes for `multiplier`: an empty numeric input is `''`, and
 * `z.coerce.number()` would silently turn that into 0 — which for an age bound is a real
 * value, not an absence.
 */
const ratingFactorRowSchema = z
  .object({
    factorType: z.enum(['AGE', 'OCCUPATION_CLASS', 'SMOKER_STATUS', 'SUM_ASSURED_BAND']),
    band: z.string().trim().min(1, 'Band is required'),
    multiplier: z.coerce.number(),
    // Optional on the row, required for AGE by the refinement below: a SUM_ASSURED_BAND or
    // OCCUPATION_CLASS row has no age bounds and the backend's CHECK refuses them there, so
    // demanding the keys on every row would be demanding fields that must stay empty.
    ageFrom: z.string().trim().optional(),
    ageTo: z.string().trim().optional(),
  })
  .superRefine((row, ctx) => {
    // Mirrors ProductApiImpl.rejectMalformedAgeBands and the rating_table_age_bounds_shape
    // CHECK. Age is rated by RANGE now -- an AGE row without one matches nobody and would
    // contribute a silent neutral 1.0, which is the defect this whole change removes.
    if (row.factorType !== 'AGE') return;
    const from = Number(row.ageFrom);
    const to = Number(row.ageTo);
    if (!row.ageFrom || !row.ageTo || Number.isNaN(from) || Number.isNaN(to)) {
      ctx.addIssue({ code: 'custom', path: ['ageFrom'], message: 'An AGE factor needs a from and to age' });
      return;
    }
    if (from < 0) {
      ctx.addIssue({ code: 'custom', path: ['ageFrom'], message: 'Age cannot be negative' });
    }
    if (to < from) {
      ctx.addIssue({ code: 'custom', path: ['ageTo'], message: 'To age must not be below from age' });
    }
  });

const benefitRowSchema = z.object({
  benefitType: z.enum(['DEATH', 'DISABILITY', 'CRITICAL_ILLNESS', 'MATURITY', 'SURRENDER']),
  calculationMethod: z.string().trim().min(1, 'Calculation method is required'),
});

const fundRowSchema = z.object({
  fundCode: z.string().trim().min(1, 'Fund code is required'),
  currentNav: z.coerce.number(),
});

/** An optional whole number, kept as a string so blank means "no bound", not zero. */
const wholeNumber = (label: string, min: number, max?: number) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || /^\d+$/.test(v), `${label} must be a whole number`)
    .refine((v) => v === '' || Number(v) >= min, `${label} must be at least ${min}`)
    .refine((v) => v === '' || max === undefined || Number(v) <= max, `${label} cannot exceed ${max}`);

/** An optional money bound, as a decimal string. */
const optionalAmount = (label: string) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || AMOUNT_PATTERN.test(v), `${label} must be a decimal amount`)
    .refine((v) => v === '' || Number(v) > 0, `${label} must be greater than zero`);

/** Both blank, or max at or above min. Mirrors EligibilityBounds and the DB CHECKs. */
function requireOrdered(
  ctx: z.RefinementCtx,
  min: string,
  max: string,
  path: string,
  message: string,
) {
  if (min !== '' && max !== '' && Number(max) < Number(min)) {
    ctx.addIssue({ code: 'custom', path: [path], message });
  }
}

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
    // What this version will accept. Every bound optional -- an unbounded dimension is a
    // real product design, not an omission. Kept as strings so a blank field stays blank
    // rather than coercing to 0, which would be a bound of zero rather than no bound.
    //
    // Entry age and term are HARD refusals at issue; sum assured is a SOFT flag recorded
    // in the audit reason. The form says so, because an actuary should know which bounds
    // will refuse business before they set one casually.
    minEntryAge: wholeNumber('Minimum entry age', 0, 120),
    maxEntryAge: wholeNumber('Maximum entry age', 0, 120),
    minTermMonths: wholeNumber('Minimum term', 1),
    maxTermMonths: wholeNumber('Maximum term', 1),
    minSumAssured: optionalAmount('Minimum sum assured'),
    maxSumAssured: optionalAmount('Maximum sum assured'),

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
  }).superRefine((values, ctx) => {
    requireOrdered(ctx, values.minEntryAge, values.maxEntryAge, 'maxEntryAge',
      'Maximum entry age cannot be below the minimum');
    requireOrdered(ctx, values.minTermMonths, values.maxTermMonths, 'maxTermMonths',
      'Maximum term cannot be below the minimum');
    requireOrdered(ctx, values.minSumAssured, values.maxSumAssured, 'maxSumAssured',
      'Maximum sum assured cannot be below the minimum');
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
    minEntryAge: '',
    maxEntryAge: '',
    minTermMonths: '',
    maxTermMonths: '',
    minSumAssured: '',
    maxSumAssured: '',
  };
}

export function blankRatingFactorRow(): PublishVersionFormInput['ratingTable'][number] {
  return { factorType: 'AGE', band: '', multiplier: 1, ageFrom: '', ageTo: '' };
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
    // Age bounds go on the wire as numbers for AGE rows and are OMITTED for every other
    // factor type -- the backend's CHECK refuses bounds on a non-AGE row, and sending 0 or
    // null-shaped strings would either fail that or record a value nothing reads.
    ratingTable: values.ratingTable.map((row) =>
      row.factorType === 'AGE'
        ? {
            factorType: row.factorType,
            band: row.band,
            multiplier: row.multiplier,
            ageFrom: Number(row.ageFrom),
            ageTo: Number(row.ageTo),
          }
        : { factorType: row.factorType, band: row.band, multiplier: row.multiplier },
    ),
    benefitSchedule: values.benefitSchedule,
    fundDefinitions: values.fundDefinitions,
    // Omitted entirely when nothing is bounded, rather than sent as an object of nulls.
    // An absent block and a block of nulls mean the same thing to the backend, but the
    // absent one says "unbounded" without asking a reader to check six fields.
    ...(hasAnyBound(values) && {
      eligibility: {
        ...(values.minEntryAge && { minEntryAge: Number(values.minEntryAge) }),
        ...(values.maxEntryAge && { maxEntryAge: Number(values.maxEntryAge) }),
        ...(values.minTermMonths && { minTermMonths: Number(values.minTermMonths) }),
        ...(values.maxTermMonths && { maxTermMonths: Number(values.maxTermMonths) }),
        ...(values.minSumAssured && { minSumAssured: Number(values.minSumAssured) }),
        ...(values.maxSumAssured && { maxSumAssured: Number(values.maxSumAssured) }),
      },
    }),
  };
}

function hasAnyBound(values: PublishVersionFormValues): boolean {
  return Boolean(
    values.minEntryAge ||
      values.maxEntryAge ||
      values.minTermMonths ||
      values.maxTermMonths ||
      values.minSumAssured ||
      values.maxSumAssured,
  );
}
