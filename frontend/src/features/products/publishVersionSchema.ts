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
    /**
     * Inclusive amount bounds for a SUM_ASSURED_BAND row, and what the platform actually matches
     * a sum assured against.
     *
     * Before these existed, underwriting produced one of three band strings hardcoded in Java --
     * LOW, MEDIUM, HIGH, at two and ten million -- and asked for a row whose band text equalled
     * it. A real product was published with the band "5000000", matched none of them, and priced
     * every policy as though it had no sum assured factor. Nothing on this screen could have
     * told an actuary that the only three spellings that worked were words nobody showed them.
     */
    sumAssuredFrom: z.string().trim().optional(),
    sumAssuredTo: z.string().trim().optional(),
  })
  .superRefine((row, ctx) => {
    // Mirrors ProductApiImpl.rejectMalformedSumAssuredBands. A range is demanded of a band that
    // RATES and not of a neutral one: a multiplier of exactly 1 changes no price whether it
    // resolves or not, while a real multiplier with no range is a number that can never reach a
    // premium -- which is the whole defect.
    if (row.factorType === 'SUM_ASSURED_BAND' && row.multiplier !== 1) {
      const from = Number(row.sumAssuredFrom);
      const to = Number(row.sumAssuredTo);
      if (!row.sumAssuredFrom || !row.sumAssuredTo || Number.isNaN(from) || Number.isNaN(to)) {
        ctx.addIssue({
          code: 'custom',
          path: ['sumAssuredFrom'],
          message: 'A sum assured band that rates needs a from and to amount — otherwise it rates nobody',
        });
      } else if (from < 0) {
        ctx.addIssue({ code: 'custom', path: ['sumAssuredFrom'], message: 'Amount cannot be negative' });
      } else if (to < from) {
        ctx.addIssue({ code: 'custom', path: ['sumAssuredTo'], message: 'The band ends before it begins' });
      }
    }
    // Mirrors ProductApiImpl.rejectMalformedAgeBands and the rating_table_age_bounds_shape
    // CHECK. Age is rated by RANGE now -- an AGE row without one matches nobody and would
    // contribute a silent neutral 1.0, which is the defect this whole change removes.
    if (row.factorType !== 'AGE') {
      // Band still carries the whole meaning of an OCCUPATION_CLASS or SMOKER_STATUS row --
      // those genuinely resolve by matching this text, both sides of the match being a code
      // the same organisation chose -- so it stays required there, and says so on the row
      // itself rather than only failing on submit.
      //
      // A SUM_ASSURED_BAND row with bounds is the exception, and the same exception AGE is: the
      // amounts beside it are what the platform resolves on, so the band is a label, and a
      // label the actuary left blank is derived from the bounds in `toApiRequest` rather than
      // typed a second time with nothing keeping the two in step. Without bounds it is all the
      // row has, so it is still required.
      const boundedSumAssured =
        row.factorType === 'SUM_ASSURED_BAND' && !!row.sumAssuredFrom && !!row.sumAssuredTo;
      if (!row.band && !boundedSumAssured) {
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
  /*
    An optional policy-term band in months (product step 1, D4): a rate may differ by term. Both
    or neither, mirroring base_rate_term_range_shape; blank on both means "every term". Optional
    in the schema so a row built before term bands existed still parses as unbanded.
  */
  termFromMonths: z.string().trim().optional(),
  termToMonths: z.string().trim().optional(),
});

/** "" or undefined both mean no term band. */
function termBand(row: { termFromMonths?: string | undefined; termToMonths?: string | undefined }) {
  const from = row.termFromMonths ?? '';
  const to = row.termToMonths ?? '';
  return from === '' && to === '' ? null : { from, to };
}

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

/**
 * An optional percentage, 0-100, kept as a string. Mirrors product_version_frequency_loading_sane.
 *
 * Blank stays blank rather than coercing to 0, for the reason the file header gives about
 * `z.coerce.number()` -- except here 0 is itself a real answer ("charge the same whatever the
 * frequency"), so conflating the two would erase a deliberate pricing decision.
 */
const percentZeroToHundred = (label: string) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || /^\d+(\.\d{1,2})?$/.test(v), `${label} must be a percentage`)
    .refine((v) => v === '' || Number(v) >= 0, `${label} cannot be negative`)
    .refine((v) => v === '' || Number(v) <= 100, `${label} cannot exceed 100`);

/**
 * A benefit percentage. Unlike the loading percentages above, zero is NOT a real answer:
 * `benefit_schedule_amount_shape` and `BenefitDefinition`'s compact constructor both refuse it,
 * because a benefit that pays nothing is not a benefit.
 */
const benefitPercent = z
  .string()
  .trim()
  .refine((v) => v === '' || /^\d+(\.\d{1,2})?$/.test(v), 'Benefit percentage must be a percentage')
  .refine((v) => v === '' || Number(v) > 0, 'Benefit percentage must be greater than zero')
  .refine((v) => v === '' || Number(v) <= 100, 'Benefit percentage cannot exceed 100');

/**
 * One benefit a product version covers, and what it pays.
 *
 * This is the number a claim is settled at -- `claimableCover` resolves the coverage row this
 * produces -- so the shape rule below is not tidiness. It mirrors `BenefitDefinition`'s compact
 * constructor and the `benefit_schedule_amount_shape` CHECK exactly: each method carries the one
 * amount it uses and no other, so an amount that would be silently ignored is refused on the
 * field rather than learned from a 422.
 *
 * `percent` and `flatAmount` stay strings for the reason the file header gives about
 * `z.coerce.number()`: an empty numeric input is `''`, and coercing that to 0 would be a real
 * amount rather than an absence -- and zero is refused on both.
 */
const benefitRowSchema = z
  .object({
    benefitType: z.enum(['DEATH', 'DISABILITY', 'CRITICAL_ILLNESS', 'MATURITY', 'SURRENDER']),
    calculationMethod: z.enum(['SUM_ASSURED', 'PERCENTAGE_OF_SUM_ASSURED', 'FLAT_AMOUNT'], {
      error: 'Calculation method is required',
    }),
    percent: benefitPercent,
    flatAmount: optionalAmount('Flat benefit amount'),
  })
  .superRefine((row, ctx) => {
    const needs = (field: 'percent' | 'flatAmount', message: string) => {
      if (row[field] === '') ctx.addIssue({ code: 'custom', message, path: [field] });
    };
    const carriesNo = (field: 'percent' | 'flatAmount', message: string) => {
      if (row[field] !== '') ctx.addIssue({ code: 'custom', message, path: [field] });
    };
    switch (row.calculationMethod) {
      case 'SUM_ASSURED':
        carriesNo('percent', 'A benefit paying the whole sum assured carries no percentage');
        carriesNo('flatAmount', 'A benefit paying the whole sum assured carries no flat amount');
        break;
      case 'PERCENTAGE_OF_SUM_ASSURED':
        needs('percent', 'A percentage benefit needs a percentage');
        carriesNo('flatAmount', 'A percentage benefit carries no flat amount');
        break;
      case 'FLAT_AMOUNT':
        needs('flatAmount', 'A flat benefit needs an amount');
        carriesNo('percent', 'A flat benefit carries no percentage');
        break;
    }
  });

/** One cell of a cash-value scale: per 1,000 of sum assured at a policy year, optionally by entry age. */
const cashValueRowSchema = z.object({
  policyYear: z.string().trim(),
  ageFrom: z.string().trim(),
  ageTo: z.string().trim(),
  cashValuePerMille: z.string().trim(),
  paidUpPerMille: z.string().trim(),
});

/** The savings products that carry a cash value -- CashValuePlanValidator.CASH_VALUE_CATEGORIES. */
export const CASH_VALUE_CATEGORIES: readonly ProductCategory[] = ['ENDOWMENT', 'WHOLE_LIFE', 'EDUCATION_SAVINGS'];

interface CashValueFields {
  cashValueBasisReference: string;
  cashValueBasisDate: string;
  cashValuePaidUpBasis: string;
  cashValueMinYears: string;
  cashValueRows: z.infer<typeof cashValueRowSchema>[];
}

function hasCashValue(v: CashValueFields): boolean {
  return v.cashValueRows.length > 0 || v.cashValueBasisReference !== '';
}

/**
 * CashValuePlanValidator, rule for rule and message for message, so the console refuses exactly
 * what the server would and in the same words. Order matters only for which message shows first.
 */
function validateCashValue(category: ProductCategory, v: CashValueFields, ctx: z.RefinementCtx) {
  if (!hasCashValue(v)) return;
  const issue = (path: (string | number)[], message: string) => ctx.addIssue({ code: 'custom', path, message });

  if (!CASH_VALUE_CATEGORIES.includes(category)) {
    issue(['cashValueRows'], `A ${category} product cannot carry a cash-value table`);
    return;
  }
  if (v.cashValueBasisReference === '' || v.cashValueBasisDate === '') {
    issue([v.cashValueBasisReference === '' ? 'cashValueBasisReference' : 'cashValueBasisDate'],
      'A cash-value table needs the actuarial basis reference and date it was signed off under');
  }
  if (v.cashValuePaidUpBasis !== 'PROPORTIONATE' && v.cashValuePaidUpBasis !== 'TABLE') {
    issue(['cashValuePaidUpBasis'], 'The paid-up basis must be PROPORTIONATE or TABLE');
  }
  if (v.cashValueMinYears !== '2' && v.cashValueMinYears !== '3') {
    issue(['cashValueMinYears'], 'A cash-value table needs the minimum years before any value exists, 2 or 3');
  }
  if (v.cashValueRows.length === 0) {
    issue(['cashValueRows'], 'A cash-value table needs at least one row');
    return;
  }
  const isWhole = (s: string) => /^\d+$/.test(s);
  v.cashValueRows.forEach((row, i) => {
    if (!isWhole(row.policyYear) || Number(row.policyYear) < 1) {
      issue(['cashValueRows', i, 'policyYear'], "A cash-value row's policy year must be 1 or more");
    }
    if ((row.ageFrom === '') !== (row.ageTo === '')) {
      issue(['cashValueRows', i, 'ageFrom'], 'A cash-value age band needs both a from and a to age, or neither');
    } else if (row.ageFrom !== '' && (Number(row.ageTo) < Number(row.ageFrom) || Number(row.ageFrom) < 0)) {
      issue(['cashValueRows', i, 'ageTo'], `Cash-value age band ${row.ageFrom}-${row.ageTo} is not a range`);
    }
    if (row.cashValuePerMille === '' || Number.isNaN(Number(row.cashValuePerMille)) || Number(row.cashValuePerMille) < 0) {
      issue(['cashValueRows', i, 'cashValuePerMille'], 'A cash value per 1,000 cannot be negative');
    }
    if (row.paidUpPerMille !== '' && Number(row.paidUpPerMille) < 0) {
      issue(['cashValueRows', i, 'paidUpPerMille'], 'A paid-up value per 1,000 cannot be negative');
    }
    if (v.cashValuePaidUpBasis === 'TABLE' && row.paidUpPerMille === '') {
      issue(['cashValueRows', i, 'paidUpPerMille'],
        `A TABLE paid-up basis needs a paid-up value on every row (policy year ${row.policyYear} has none)`);
    }
  });
  const band = (r: { ageFrom: string; ageTo: string }) => (r.ageFrom === '' ? 'all ages' : `${r.ageFrom}-${r.ageTo}`);
  for (let i = 0; i < v.cashValueRows.length; i++) {
    for (let j = i + 1; j < v.cashValueRows.length; j++) {
      const a = v.cashValueRows[i]!;
      const b = v.cashValueRows[j]!;
      if (a.policyYear !== b.policyYear) continue;
      const overlap = a.ageFrom === '' || b.ageFrom === '' ||
        (Number(a.ageFrom) <= Number(b.ageTo) && Number(b.ageFrom) <= Number(a.ageTo));
      if (overlap) {
        issue(['cashValueRows', j, 'ageFrom'],
          `Cash-value rows for policy year ${a.policyYear}, ${a.ageFrom === '' ? 'all ages' : `ages ${band(a)}`} and ${band(b)}, overlap -- an entry age in both would be valued differently depending on row order`);
      }
    }
  }
}

export function blankCashValueRow(): z.infer<typeof cashValueRowSchema> {
  return { policyYear: '', ageFrom: '', ageTo: '', cashValuePerMille: '', paidUpPerMille: '' };
}

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

        // Mirrors rejectOverlappingAgeBands' term checks, and their wording.
        const term = termBand(row);
        if (term) {
          if (term.from === '' || term.to === '') {
            ctx.addIssue({
              code: 'custom',
              path: [i, term.from === '' ? 'termFromMonths' : 'termToMonths'],
              message: 'A base rate term band needs both a from and a to month, or neither',
            });
          } else if (
            !Number.isInteger(Number(term.from)) || !Number.isInteger(Number(term.to)) ||
            Number(term.from) < 1 || Number(term.to) < Number(term.from)
          ) {
            ctx.addIssue({
              code: 'custom',
              path: [i, 'termToMonths'],
              message: `Base rate term band ${term.from}-${term.to} months is not a range`,
            });
          }
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
      // Two cells collide only when BOTH their ages and their terms overlap; a row with no term
      // band overlaps every term -- termRangesOverlap's rule, so an unbanded row cannot sit beside
      // a banded one for the same age, sex and smoker status.
      type Seen = { key: string; from: number; to: number; term: { from: number; to: number } | null };
      const termsOverlap = (a: Seen['term'], b: Seen['term']) =>
        a === null || b === null || (a.from <= b.to && b.from <= a.to);
      const seen: Seen[] = [];
      rows.forEach((row, i) => {
        const from = Number(row.ageFrom);
        const to = Number(row.ageTo);
        if (!Number.isInteger(from) || !Number.isInteger(to) || to < from) return;
        const band = termBand(row);
        if (band && (band.from === '' || band.to === '')) return; // already reported above
        const term = band ? { from: Number(band.from), to: Number(band.to) } : null;

        for (const cell of pricedCells(row)) {
          const key = `${row.sex}/${cell.smokerStatus}`;
          const clash = seen.find(
            (s) => s.key === key && from <= s.to && s.from <= to && termsOverlap(s.term, term),
          );
          if (clash) {
            const clashTerm = clash.term ? `, term ${clash.term.from}-${clash.term.to} months` : '';
            ctx.addIssue({
              code: 'custom',
              path: [i, cell.key],
              message: `Overlaps ${clash.from}-${clash.to}${clashTerm} for ${key} — an age and term in both would price differently depending on row order`,
            });
          } else {
            seen.push({ key, from, to, term });
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

    // What this version charges for paying in instalments. A monthly payer costs an insurer more
    // -- the annual payer's premium is investable on day one, twelve collections cost more than
    // one, and monthly business lapses part-paid -- so charging both the same total is a decision,
    // and so is charging more. Blank means no loading.
    monthlyLoadingPercent: percentZeroToHundred('Monthly loading'),
    quarterlyLoadingPercent: percentZeroToHundred('Quarterly loading'),

    // The TIRA filing that authorises this version. REQUIRED, mirroring the server: in Tanzania
    // a product and its rates must be filed with and approved by TIRA before sale, and an
    // optional compliance field is one nobody fills in.
    tiraReference: z.string().trim().min(1, 'A TIRA filing reference is required'),
    tiraApprovalDate: z
      .string()
      .trim()
      .min(1, 'A TIRA approval date is required')
      .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date')
      .refine(
        (v) => v === '' || v <= new Date().toISOString().slice(0, 10),
        'An approval that has not happened cannot authorise a product',
      ),

    // ProductApiImpl.publishVersion refuses a version that covers nothing, the first check it
    // runs. A contract has to say what it insures before it can be priced or claimed against.
    benefitSchedule: z.array(benefitRowSchema).min(1, 'A product must cover at least one benefit'),
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

    // The cash-value (surrender value) table and its actuarial sign-off (product step 1). All
    // strings, blank meaning absent, for the coercion reason the file header gives. Validated as
    // a whole below, by CashValuePlanValidator's rules and in its words.
    cashValueBasisReference: z.string().trim(),
    cashValueBasisDate: z
      .string()
      .trim()
      .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
    cashValuePaidUpBasis: z.string().trim(),
    cashValueMinYears: z.string().trim(),
    cashValueRows: z.array(cashValueRowSchema),
  }).superRefine((values, ctx) => {
    validateCashValue(category, values, ctx);

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

      // Mirrors ProductApiImpl.rejectPricedVersionWithoutEntryAgeBounds. Without a declared
      // range the rate table's own span silently becomes the product's selling range, and
      // nothing can tell a deliberate range from an incomplete one.
      if (!values.minEntryAge || !values.maxEntryAge) {
        ctx.addIssue({
          code: 'custom',
          path: [values.minEntryAge ? 'maxEntryAge' : 'minEntryAge'],
          message:
            'A priced product must say what entry age it sells to — otherwise the rate table’s own span becomes the answer by accident',
        });
      } else {
        /*
          Mirrors ProductApiImpl.rejectUncoveredEntryAges. Checked per (sex, smoker status)
          rather than over age alone, because a table can span the whole declared range in
          aggregate and still price nobody in half of it -- a real published version covers
          18-78 between its rows while pricing no woman under 56.

          Both sexes are required; the smoker statuses required are exactly those the table
          prices somewhere, so a product may decline to price UNKNOWN and demand a declaration.
        */
        const min = Number(values.minEntryAge);
        const max = Number(values.maxEntryAge);
        const cells = values.baseRates.flatMap((row) =>
          pricedCells(row).map((c) => ({
            sex: row.sex,
            smokerStatus: c.smokerStatus,
            from: Number(row.ageFrom),
            to: Number(row.ageTo),
          })),
        );
        const statuses = [...new Set(cells.map((c) => c.smokerStatus))];

        for (const sex of ['FEMALE', 'MALE'] as const) {
          for (const status of statuses) {
            const series = cells
              .filter((c) => c.sex === sex && c.smokerStatus === status)
              .sort((a, b) => a.from - b.from);

            let coveredTo = min - 1;
            for (const c of series) {
              if (c.from > coveredTo + 1) break;
              coveredTo = Math.max(coveredTo, c.to);
            }
            if (coveredTo >= max) continue;

            const gapFrom = coveredTo + 1;
            const laterStarts = series.filter((c) => c.from > gapFrom).map((c) => c.from - 1);
            const gapTo = Math.min(laterStarts.length ? Math.min(...laterStarts) : max, max);
            ctx.addIssue({
              code: 'custom',
              path: ['baseRates'],
              message: `Base rates do not price ${sex}/${status} for ages ${gapFrom}-${gapTo}, but this version accepts entry ages ${min}-${max}`,
            });
          }
        }
      }
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
    monthlyLoadingPercent: '',
    quarterlyLoadingPercent: '',
    tiraReference: '',
    tiraApprovalDate: '',
    cashValueBasisReference: '',
    cashValueBasisDate: '',
    cashValuePaidUpBasis: '',
    cashValueMinYears: '',
    cashValueRows: [],
  };
}

export function blankRatingFactorRow(): PublishVersionFormInput['ratingTable'][number] {
  // Every bound starts as a controlled empty string, both kinds. A row can be switched to any
  // factor type after it is added, so the keys the other types need have to exist from the
  // start -- an input that begins undefined and later receives a value is the uncontrolled-to-
  // controlled warning, and worse, loses what was typed into it.
  return { factorType: 'AGE', band: '', multiplier: 1, ageFrom: '', ageTo: '', sumAssuredFrom: '', sumAssuredTo: '' };
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
  const blank = { ageFrom, ageTo, nonSmoker: '', smoker: '', unknown: '', termFromMonths: '', termToMonths: '' };
  return [
    { ...blank, sex: 'FEMALE' },
    { ...blank, sex: 'MALE' },
  ];
}

export function blankBenefitRow(): PublishVersionFormInput['benefitSchedule'][number] {
  // Both amount keys exist from the start for the reason blankRatingFactorRow gives: the method
  // can be switched after the row is added, and an input that begins undefined and later
  // receives a value is the uncontrolled-to-controlled warning, and loses what was typed in it.
  return { benefitType: 'DEATH', calculationMethod: 'SUM_ASSURED', percent: '', flatAmount: '' };
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
        : row.factorType === 'SUM_ASSURED_BAND' && row.sumAssuredFrom && row.sumAssuredTo
          ? {
              factorType: row.factorType,
              // Derived when the actuary left it blank, so the label and the bounds cannot
              // disagree -- the same rule the AGE branch above follows.
              band: row.band || `${Number(row.sumAssuredFrom)}-${Number(row.sumAssuredTo)}`,
              multiplier: row.multiplier,
              sumAssuredFrom: Number(row.sumAssuredFrom),
              sumAssuredTo: Number(row.sumAssuredTo),
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
        pricedCells(row).map((cell) => {
          const term = termBand(row);
          return {
            ageFrom: Number(row.ageFrom),
            ageTo: Number(row.ageTo),
            sex: row.sex,
            smokerStatus: cell.smokerStatus,
            ratePerMille: Number(cell.raw),
            // Omitted on an unbanded row rather than sent as nulls: absent means "every term".
            ...(term && { termFromMonths: Number(term.from), termToMonths: Number(term.to) }),
          };
        }),
      );
      return cells.length > 0 ? { baseRates: cells } : {};
    })(),
    // Omitted entirely on a version with no cash value -- pure protection has none.
    ...(hasCashValue(values) && {
      cashValue: {
        basisReference: values.cashValueBasisReference,
        basisDate: values.cashValueBasisDate,
        paidUpBasis: values.cashValuePaidUpBasis as 'PROPORTIONATE' | 'TABLE',
        minYearsForValue: Number(values.cashValueMinYears),
        rows: values.cashValueRows.map((r) => ({
          policyYear: Number(r.policyYear),
          ...(r.ageFrom !== '' && { ageFrom: Number(r.ageFrom), ageTo: Number(r.ageTo) }),
          cashValuePerMille: Number(r.cashValuePerMille),
          ...(r.paidUpPerMille !== '' && { paidUpPerMille: Number(r.paidUpPerMille) }),
        })),
      },
    }),
    // Required, so no omit-when-blank branch: a version may not exist without the filing that
    // authorises it, and the schema above refuses a blank one before this runs.
    tiraFiling: {
      reference: values.tiraReference,
      approvalDate: values.tiraApprovalDate,
    },
    // Each row carries only the amount its own method uses -- benefit_schedule_amount_shape
    // refuses the others outright, so sending them would be a 422 rather than a tidiness
    // question. The same arrangement as the rating table's per-factor bounds above.
    benefitSchedule: values.benefitSchedule.map((benefit) => ({
      benefitType: benefit.benefitType,
      calculationMethod: benefit.calculationMethod,
      ...(benefit.calculationMethod === 'PERCENTAGE_OF_SUM_ASSURED' && {
        percent: Number(benefit.percent),
      }),
      ...(benefit.calculationMethod === 'FLAT_AMOUNT' && {
        flatAmount: Number(benefit.flatAmount),
      }),
    })),
    fundDefinitions: values.fundDefinitions,
    // Omitted entirely when nothing is bounded, rather than sent as an object of nulls.
    // An absent block and a block of nulls mean the same thing to the backend, but the
    // absent one says "unbounded" without asking a reader to check six fields.
    // Omitted entirely when nothing is loaded, rather than sent as a block of zeroes. An absent
    // block and a block of zeroes mean the same thing to the backend; the absent one says "this
    // version does not load instalment payment" without asking a reader to check two fields.
    ...((values.monthlyLoadingPercent || values.quarterlyLoadingPercent) && {
      frequencyLoading: {
        ...(values.monthlyLoadingPercent && { monthlyPercent: Number(values.monthlyLoadingPercent) }),
        ...(values.quarterlyLoadingPercent && { quarterlyPercent: Number(values.quarterlyLoadingPercent) }),
      },
    }),
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
