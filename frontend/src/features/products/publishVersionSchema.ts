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
    /**
     * Required for every factor type EXCEPT age, where it is derived.
     *
     * On an AGE row the band is a pure label -- openapi-product.yaml says it "remains as the
     * human-readable label but is not matched against", because age resolves on ageFrom/ageTo.
     * Demanding it there made an actuary type the same range twice ("30-39", then 30 and 39)
     * with nothing keeping the two in step, and the seeded dev data already showed the drift:
     * a band reading "30-39" beside bounds that said something else.
     *
     * So it is optional on an AGE row and `toApiRequest` fills it from the bounds when blank.
     * Still editable, for a label the bounds cannot express.
     */
    band: z.string().trim().optional(),
    /**
     * GREATER THAN ZERO, and this bound is the one that was missing when it mattered.
     *
     * A product went out with its AGE band at 0.0000. The premium formula multiplies by this
     * number, so every policy in that band priced at nothing; the insert was refused by a CHECK
     * inside an AFTER_COMMIT listener, which meant the underwriting case stayed ACCEPTed with no
     * policy behind it and no error anywhere a person would look.
     *
     * `z.coerce.number()` alone accepted it, and would have accepted a negative one too.
     * ProductApiImpl.rejectNonPositiveMultipliers and the rating_table_multiplier_positive CHECK
     * now refuse it server-side; this stops it here, where the actuary can still see which row.
     *
     * A band that does not load is 1, not 0 -- the message says so, because an actuary typing 0
     * means "no loading" and has no way to know that is not what it does.
     */
    multiplier: z.coerce
      .number()
      .positive('A multiplier must be greater than zero -- use 1 for a band that does not load'),
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
    if (row.factorType !== 'AGE') {
      // Band carries the whole meaning of a non-AGE row -- SUM_ASSURED_BAND resolves by
      // matching this text -- so it stays required there, and says so on the row itself
      // rather than only failing on submit.
      if (!row.band) {
        ctx.addIssue({ code: 'custom', path: ['band'], message: 'Band is required' });
      }
      return;
    }
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

/**
 * One row of the base rate editor: an age band for ONE sex, carrying a rate per
 * smoker status.
 *
 * ## Why the form is shaped differently from the wire
 *
 * The wire wants one object per (age band, sex, smoker status) cell -- so a
 * five-band table for both sexes across all three smoker statuses is thirty
 * objects. Asking an actuary to hand-enter thirty rows is how a rate table stops
 * getting entered at all, which is the state this console was already in: it sent
 * no `baseRates` whatsoever, so every product published here was permanently
 * unquotable and there is no endpoint to add rates afterwards.
 *
 * So one form row is one (band, sex) and the three smoker statuses are columns,
 * and `toApiRequest` expands each row into up to three wire cells. That is ten
 * rows for the same table instead of thirty, with a single-level header that
 * fits the panel -- six rate inputs on one row (band-per-row) needs a two-level
 * Female/Male header and about 750px, which the detail panel does not always
 * have.
 *
 * A BLANK rate means "this combination is not priced", not zero: the column
 * exists because `SmokerStatus.UNKNOWN` is, in the backend's own words, "a real,
 * ratable value rather than a null stand-in", and the quote lookup is exact --
 * `findApplicable(versionId, age, sex, smokerStatus)` throws rather than falling
 * back, so an unpriced combination cannot be quoted at all.
 */
const baseRateRowSchema = z.object({
  ageFrom: z.string().trim(),
  ageTo: z.string().trim(),
  sex: z.enum(['FEMALE', 'MALE']),
  // Kept as strings and coerced in toApiRequest, for the reason the file header
  // gives for every other numeric here: `z.coerce.number()` turns '' into 0, and
  // 0 is a rate the backend's own CHECK refuses -- a very different thing from
  // "left blank on purpose".
  nonSmoker: z.string().trim(),
  smoker: z.string().trim(),
  unknown: z.string().trim(),
});

/** The three rate columns, in the order they are rendered and expanded. */
export const BASE_RATE_COLUMNS = [
  { key: 'nonSmoker', smokerStatus: 'NON_SMOKER', label: 'Non-smoker' },
  { key: 'smoker', smokerStatus: 'SMOKER', label: 'Smoker' },
  { key: 'unknown', smokerStatus: 'UNKNOWN', label: 'Unknown' },
] as const;

type BaseRateRow = z.infer<typeof baseRateRowSchema>;

/** Every (rate, smokerStatus) a row actually prices, blanks skipped. */
function pricedCells(row: BaseRateRow) {
  return BASE_RATE_COLUMNS.map((c) => ({ ...c, raw: row[c.key] })).filter((c) => c.raw !== '');
}

/**
 * Whether a row prices anything at all -- the switch between the priced and unpriced modes.
 *
 * Exported because the form has to make the same call on live, still-untrimmed values to
 * warn about a double count while it is being created, rather than only on submit.
 */
export function isBaseRateRowPriced(row: Partial<Record<string, unknown>> | undefined): boolean {
  return BASE_RATE_COLUMNS.some((c) => String(row?.[c.key] ?? '').trim() !== '');
}

/**
 * The one wording for a factor that the base rate table already keys on.
 *
 * Shared so the schema and the form cannot drift: the object-level refinement below is
 * what refuses the submit, but zod skips it entirely once any field-level check has
 * failed, so this message only ever reaches the screen through the form.
 */
export function doubleCountedFactorMessage(factorType: 'AGE' | 'SMOKER_STATUS'): string {
  return (
    `${factorType === 'AGE' ? 'Age' : 'Smoker status'} is a key of the base rate table below — ` +
    'a multiplier here would be counted twice. Remove this row.'
  );
}

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
    // Coverage is checked across BOTH lists in the object-level refinement below,
    // because which factor types are required depends on whether base rates were
    // supplied -- a question this array cannot see on its own.
    ratingTable: z.array(ratingFactorRowSchema),
    baseRates: z.array(baseRateRowSchema).superRefine((rows, ctx) => {
      rows.forEach((row, i) => {
        const cells = pricedCells(row);
        const from = Number(row.ageFrom);
        const to = Number(row.ageTo);

        // A row with no rate at all is an empty row, not an error -- it is what a
        // freshly added band looks like before anything is typed. It contributes
        // no cells and is dropped on submit.
        if (cells.length === 0 && row.ageFrom === '' && row.ageTo === '') return;

        if (row.ageFrom === '' || row.ageTo === '' || !Number.isInteger(from) || !Number.isInteger(to)) {
          ctx.addIssue({ code: 'custom', path: [i, 'ageFrom'], message: 'A rate band needs a whole from and to age' });
          return;
        }
        if (from < 0) {
          ctx.addIssue({ code: 'custom', path: [i, 'ageFrom'], message: 'Age cannot be negative' });
        }
        // Mirrors rejectOverlappingAgeBands' own first check, and its wording.
        if (to < from) {
          ctx.addIssue({ code: 'custom', path: [i, 'ageTo'], message: 'The band ends before it begins' });
        }
        if (cells.length === 0) {
          ctx.addIssue({ code: 'custom', path: [i, 'nonSmoker'], message: 'Give this band at least one rate' });
        }

        // base_rate_table_rate_per_mille_check: strictly greater than zero. A rate
        // of 0 would price a real contract at nothing, so the database refuses it
        // and so does this.
        for (const cell of cells) {
          const rate = Number(cell.raw);
          if (Number.isNaN(rate) || rate <= 0) {
            ctx.addIssue({ code: 'custom', path: [i, cell.key], message: 'A rate must be greater than zero' });
          }
        }
      });

      /*
        rejectOverlappingAgeBands, per (sex, smokerStatus) -- exactly the backend's
        pairing. Two bands overlapping for the same sex and smoker status means an
        age that falls in both would "price differently depending on row order",
        which is the backend's own reason for refusing it.

        Checked per PRICED cell rather than per row: two rows may share an age band
        for the same sex as long as they do not price the same smoker status, which
        is how somebody splits a table across two lines.
      */
      const seen: { i: number; key: string; from: number; to: number; label: string }[] = [];
      rows.forEach((row, i) => {
        const from = Number(row.ageFrom);
        const to = Number(row.ageTo);
        if (!Number.isInteger(from) || !Number.isInteger(to) || to < from) return;

        for (const cell of pricedCells(row)) {
          const key = `${row.sex}/${cell.smokerStatus}`;
          const clash = seen.find((s) => s.key === key && from <= s.to && s.from <= to);
          if (clash) {
            ctx.addIssue({
              code: 'custom',
              path: [i, cell.key],
              message: `Overlaps ${clash.from}-${clash.to} for ${key} — an age in both would price differently depending on row order`,
            });
          } else {
            seen.push({ i, key, from, to, label: cell.label });
          }
        }
      });
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
    /*
      The two modes, mirroring ProductApiImpl.publishVersion exactly.

      PRICED (base rates supplied): age and smoker status are KEYS of the rate
      table, so a multiplier for either would be applied a second time on top of
      the rate it already selected -- the backend calls this "the single most
      likely defect in this design" and refuses it with a 422. Only
      SUM_ASSURED_BAND stays required.

      UNPRICED: age is rated by multiplier alone, so AGE and SUM_ASSURED_BAND are
      both required. This is what the form enforced unconditionally before, and it
      is why supplying base rates was impossible even once the fields existed --
      the client demanded exactly the factor the server would have rejected.
    */
    const priced = values.baseRates.some((r) => pricedCells(r).length > 0);
    const covered = new Set(values.ratingTable.map((r) => r.factorType));

    if (priced) {
      if (!covered.has('SUM_ASSURED_BAND')) {
        ctx.addIssue({
          code: 'custom',
          path: ['ratingTable'],
          message: 'Rating table must cover at least the SUM_ASSURED_BAND factor type',
        });
      }
      values.ratingTable.forEach((row, i) => {
        if (row.factorType === 'AGE' || row.factorType === 'SMOKER_STATUS') {
          ctx.addIssue({
            code: 'custom',
            path: ['ratingTable', i, 'factorType'],
            message: doubleCountedFactorMessage(row.factorType),
          });
        }
      });
    } else if (!covered.has('AGE') || !covered.has('SUM_ASSURED_BAND')) {
      ctx.addIssue({
        code: 'custom',
        path: ['ratingTable'],
        message: 'Rating table must cover at least AGE and SUM_ASSURED_BAND',
      });
    }

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
    baseRates: [],
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

/**
 * A band is added for BOTH sexes at once.
 *
 * A rate table is written per age band, not per (band, sex): an actuary thinks
 * "18-30", then fills the rates across. Appending one row per sex would make
 * every band a two-step job and invite a table priced for women and not men --
 * which the exact quote lookup turns into a 422 for every male applicant rather
 * than anything visible on this screen.
 */
export function blankBaseRateBand(ageFrom = '', ageTo = ''): PublishVersionFormInput['baseRates'] {
  const blank = { ageFrom, ageTo, nonSmoker: '', smoker: '', unknown: '' };
  return [
    { ...blank, sex: 'FEMALE' },
    { ...blank, sex: 'MALE' },
  ];
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
            // Derived when the actuary left it blank, so the label and the bounds cannot
            // disagree. The backend still receives a band on every row.
            band: row.band || `${Number(row.ageFrom)}-${Number(row.ageTo)}`,
            multiplier: row.multiplier,
            ageFrom: Number(row.ageFrom),
            ageTo: Number(row.ageTo),
          }
        : { factorType: row.factorType, band: row.band ?? '', multiplier: row.multiplier },
    ),
    /*
      One form row expands into one wire cell per PRICED smoker status, so a
      (band, sex) row with two rates supplied becomes two `BaseRate` objects and a
      row with none disappears. Blanks are dropped rather than sent as zero: the
      database's own CHECK refuses a rate of zero, and "not priced for this
      combination" is a real product decision that has to survive the round trip.

      Omitted entirely when the table is empty, rather than sent as `[]`: the
      backend keys its priced/unpriced fork on `baseRates != null && !isEmpty()`,
      so either would work, but an absent key is what says "this version is
      deliberately unpriced" without asking a reader to check the length.
    */
    ...(() => {
      const cells = values.baseRates.flatMap((row) =>
        pricedCells(row).map((cell) => ({
          ageFrom: Number(row.ageFrom),
          ageTo: Number(row.ageTo),
          sex: row.sex,
          smokerStatus: cell.smokerStatus,
          ratePerMille: Number(cell.raw),
        })),
      );
      return cells.length > 0 ? { baseRates: cells } : {};
    })(),
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
