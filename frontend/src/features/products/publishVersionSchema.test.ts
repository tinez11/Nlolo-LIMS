import { describe, expect, it } from 'vitest';
import {
  blankBaseRateBand,
  blankPublishVersionForm,
  publishVersionFormSchema,
  toApiRequest,
} from './publishVersionSchema';

// AGE rows carry a real range as of product V5: age is resolved by range, not by matching
// the band text, so an AGE row without one would match nobody and silently rate neutral.
const ageRow = { factorType: 'AGE' as const, band: '18-30', multiplier: 1, ageFrom: '18', ageTo: '30' };
// SUM_ASSURED_BAND rows carry a real amount range as of product V9, for the identical reason
// AGE does one line up: a sum assured was resolved by matching the band text against LOW,
// MEDIUM or HIGH -- three strings hardcoded in underwriting and shown on no screen -- so a
// product published with the band '5000000' rated nobody and this 1.1 reached no premium.
const sumRow = {
  factorType: 'SUM_ASSURED_BAND' as const,
  band: '0-5000000',
  multiplier: 1.1,
  sumAssuredFrom: '0',
  sumAssuredTo: '5000000',
};

const deathBenefit = {
  benefitType: 'DEATH' as const,
  calculationMethod: 'SUM_ASSURED' as const,
  percent: '',
  flatAmount: '',
};

// Built on the blank form so the fixture always carries every key a real submission has.
const valid = () => ({
  ...blankPublishVersionForm(),
  ifrsMeasurementModel: 'PAA' as const,
  effectiveDate: '2026-01-01',
  retirementDate: '',
  ratingTable: [ageRow, sumRow],
  // A version that covers nothing is refused as of V13, so every fixture carries a benefit
  // or nothing parses -- the same reason the TIRA filing below is here.
  benefitSchedule: [deathBenefit],
  fundDefinitions: [],
  // Required as of V12: a version may not exist without the filing that authorises it, so
  // every fixture carries one or nothing parses.
  tiraReference: 'TIRA/LIFE/2026/0001',
  tiraApprovalDate: '2026-01-15',
  // Required on an individual product as of product step 2, for the same reason the TIRA filing
  // above is here: PayoutPlanValidator refuses a version without it, so every fixture carries
  // one or nothing parses.
  freeLookDays: '15',
});

/** The one row an ENDOWMENT must carry: the whole sum assured at the end of the term. */
const maturityRow = {
  kind: 'MATURITY',
  fromPolicyYear: '',
  toPolicyYear: '',
  amountBasis: 'PERCENT_OF_SA',
  amountValue: '100',
  frequency: '',
};

describe('publishVersionFormSchema', () => {
  const termLife = publishVersionFormSchema('TERM_LIFE');

  it('accepts a well-formed request covering AGE and SUM_ASSURED_BAND', () => {
    expect(termLife.safeParse(valid()).success).toBe(true);
  });

  // ProductApiImpl.publishVersion: coveredFactorTypes.containsAll(AGE, SUM_ASSURED_BAND)
  // or 422 -- mirrored here so a staff user does not learn this from a round trip.
  it('rejects a rating table missing AGE', () => {
    expect(termLife.safeParse({ ...valid(), ratingTable: [sumRow] }).success).toBe(false);
  });

  it('rejects a rating table missing SUM_ASSURED_BAND', () => {
    expect(termLife.safeParse({ ...valid(), ratingTable: [ageRow] }).success).toBe(false);
  });

  it('rejects an empty rating table', () => {
    expect(termLife.safeParse({ ...valid(), ratingTable: [] }).success).toBe(false);
  });

  /**
   * The bound that was missing when it mattered. A product was published with its AGE band at
   * 0, so every policy in that band priced at nothing -- the premium formula multiplies by this
   * number. The nil premium was then refused by a CHECK inside an AFTER_COMMIT listener, which
   * left the underwriting case reading ACCEPT with no policy behind it and no error on screen.
   *
   * A negative one would have gone just as far, and priced a policy below nothing.
   */
  it('rejects a multiplier of zero or less, at the row', () => {
    expect(
      termLife.safeParse({ ...valid(), ratingTable: [{ ...ageRow, multiplier: 0 }, sumRow] })
        .success,
    ).toBe(false);
    expect(
      termLife.safeParse({ ...valid(), ratingTable: [ageRow, { ...sumRow, multiplier: -1 }] })
        .success,
    ).toBe(false);
  });

  it('says to use 1 for a band that does not load, since 0 is what an actuary reaches for', () => {
    const result = termLife.safeParse({
      ...valid(),
      ratingTable: [{ ...ageRow, multiplier: 0 }, sumRow],
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(JSON.stringify(result.error.issues)).toContain('use 1 for a band that does not load');
    }
  });

  it('accepts extra factor types alongside the two required ones', () => {
    const result = termLife.safeParse({
      ...valid(),
      ratingTable: [ageRow, sumRow, { factorType: 'SMOKER_STATUS', band: 'NON_SMOKER', multiplier: 0.9 }],
    });
    expect(result.success).toBe(true);
  });

  /**
   * Band used to be required on every row. It is required only where the band text is still
   * what the platform matches on, and the distinction is the point rather than a relaxation.
   *
   * On an OCCUPATION_CLASS or SMOKER_STATUS row the band IS the match, so a blank one rates
   * nothing. On an AGE row it is a pure label -- openapi-product.yaml: "remains as the
   * human-readable label but is not matched against", because age resolves on ageFrom/ageTo.
   * Requiring it there made an actuary type the same range twice with nothing keeping the two
   * in step, and the seeded data already showed them disagreeing. A bounded SUM_ASSURED_BAND
   * row joined that second group at product V9.
   */
  it('rejects a blank band on a row whose band is what gets matched', () => {
    expect(
      termLife.safeParse({
        ...valid(),
        ratingTable: [ageRow, sumRow, { factorType: 'SMOKER_STATUS' as const, band: '', multiplier: 0.9 }],
      }).success,
    ).toBe(false);
    expect(
      termLife.safeParse({
        ...valid(),
        ratingTable: [ageRow, sumRow, { factorType: 'OCCUPATION_CLASS' as const, band: '', multiplier: 1.4 }],
      }).success,
    ).toBe(false);
  });

  /**
   * ...and an UNBOUNDED sum assured row is still one of them. Such a row resolves for no
   * amount at all, so the band text is the only thing identifying it to a human reading the
   * product back.
   */
  it('rejects a blank band on a sum assured row with no range to label it from', () => {
    expect(
      termLife.safeParse({
        ...valid(),
        ratingTable: [
          ageRow,
          { factorType: 'SUM_ASSURED_BAND' as const, band: '', multiplier: 1 },
        ],
      }).success,
    ).toBe(false);
  });

  it('accepts a blank band on an AGE row, and labels it from the range on the wire', () => {
    const result = termLife.safeParse({
      ...valid(),
      ratingTable: [{ ...ageRow, band: '' }, sumRow],
    });
    expect(result.success).toBe(true);

    // Derived, not sent blank: the backend still receives a band on every row, and it cannot
    // contradict the bounds because nobody typed it.
    const wire = toApiRequest(result.data!);
    expect(wire.ratingTable![0]).toMatchObject({ factorType: 'AGE', band: '18-30', ageFrom: 18, ageTo: 30 });
  });

  it('keeps a band the actuary did type on an AGE row', () => {
    const result = termLife.safeParse({
      ...valid(),
      ratingTable: [{ ...ageRow, band: 'Young lives' }, sumRow],
    });
    expect(result.success).toBe(true);
    expect(toApiRequest(result.data!).ratingTable![0]).toMatchObject({ band: 'Young lives' });
  });

  /**
   * Base rates, and the priced/unpriced fork.
   *
   * This form sent no `baseRates` at all before, so every product published through
   * the console was permanently unquotable -- `quote-premium` refuses a version with
   * no rate table, and no endpoint adds rates after publishing. The client schema was
   * part of why: it demanded an AGE rating factor unconditionally, which is exactly
   * what `ProductApiImpl.publishVersion` REFUSES once base rates are present, because
   * age is a key of the rate table and a multiplier would count it twice.
   */
  describe('base rates', () => {
    type BandRow = {
      ageFrom: string;
      ageTo: string;
      sex: 'FEMALE' | 'MALE';
      nonSmoker: string;
      smoker: string;
      unknown: string;
      termFromMonths?: string;
      termToMonths?: string;
    };

    // A priced female 18-30 band, overridable per test.
    const band = (over: Partial<BandRow> = {}): BandRow => ({
      ageFrom: '18',
      ageTo: '30',
      sex: 'FEMALE',
      nonSmoker: '0.62',
      smoker: '1.488',
      unknown: '',
      ...over,
    });

    /**
     * A COMPLETE priced form: both sexes, and the entry-age range the version accepts.
     *
     * A priced version must now declare that range, and its table must be able to price every
     * life inside it -- so a one-sex fixture, which is what most of these tests used to build,
     * is no longer a publishable version. UNKNOWN is left unpriced on both rows, which is a
     * product demanding a smoker declaration and is deliberately still allowed.
     */
    const pricedValid = (over: Record<string, unknown> = {}) => ({
      ...valid(),
      ratingTable: [sumRow],
      minEntryAge: '18',
      maxEntryAge: '30',
      baseRates: [band(), band({ sex: 'MALE' })],
      ...over,
    });

    it('requires entry age bounds once any base rate is priced', () => {
      const result = termLife.safeParse(pricedValid({ minEntryAge: '', maxEntryAge: '' }));
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain('entry age');
    });

    it('rejects a priced table with a hole inside the ages it accepts', () => {
      // The shape of a real published version: no woman under 56 can be priced.
      const result = termLife.safeParse(
        pricedValid({
          minEntryAge: '18',
          maxEntryAge: '78',
          baseRates: [
            band({ ageFrom: '56', ageTo: '78', smoker: '' }),
            band({ sex: 'MALE', ageFrom: '18', ageTo: '78', smoker: '' }),
          ],
        }),
      );
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain('18-55');
    });

    it('accepts a priced table that covers its whole declared range', () => {
      expect(termLife.safeParse(pricedValid()).success).toBe(true);
    });

    it('expands one form row into one wire cell per priced smoker status, and drops blanks', () => {
      /*
        Two bands, because a blank can no longer be asymmetric INSIDE the declared range: if
        one sex prices UNKNOWN and the other does not, the version cannot price every life it
        accepts and is refused. So the 18-30 band prices all three statuses for both sexes --
        which is what proves a single form row expands into three wire cells -- and the 31-40
        band, deliberately outside the declared 18-30, carries the blanks that must be dropped.
      */
      const result = termLife.safeParse(
        pricedValid({
          baseRates: [
            band({ unknown: '1.2' }),
            band({ sex: 'MALE', nonSmoker: '0.837', smoker: '2.0088', unknown: '1.5' }),
            band({ ageFrom: '31', ageTo: '40', nonSmoker: '0.9', smoker: '2.1', unknown: '' }),
            band({ ageFrom: '31', ageTo: '40', sex: 'MALE', nonSmoker: '1.1', smoker: '2.6', unknown: '' }),
          ],
        }),
      );
      expect(result.success).toBe(true);

      const wire = toApiRequest(result.data!).baseRates!;
      // 3 per row on the 18-30 pair, 2 per row on the 31-40 pair. The blank UNKNOWN cells are
      // dropped rather than sent as 0, which the database CHECK would refuse anyway.
      expect(wire).toHaveLength(10);
      expect(wire).toEqual(
        expect.arrayContaining([
          { ageFrom: 18, ageTo: 30, sex: 'FEMALE', smokerStatus: 'NON_SMOKER', ratePerMille: 0.62 },
          { ageFrom: 18, ageTo: 30, sex: 'FEMALE', smokerStatus: 'SMOKER', ratePerMille: 1.488 },
          { ageFrom: 18, ageTo: 30, sex: 'MALE', smokerStatus: 'UNKNOWN', ratePerMille: 1.5 },
        ]),
      );
      expect(wire.some((c) => c.ageFrom === 31 && c.smokerStatus === 'UNKNOWN')).toBe(false);
    });

    it('omits baseRates entirely when nothing is priced, rather than sending an empty array', () => {
      const result = termLife.safeParse({ ...valid(), baseRates: blankBaseRateBand() });
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!)).not.toHaveProperty('baseRates');
    });

    it('refuses an AGE or SMOKER_STATUS multiplier once base rates are supplied', () => {
      // The backend calls this "the single most likely defect in this design": both
      // dimensions are keys of the rate table, so a multiplier applies them twice.
      for (const factorType of ['AGE', 'SMOKER_STATUS'] as const) {
        const result = termLife.safeParse({
          ...valid(),
          ratingTable: [sumRow, { factorType, band: 'X', multiplier: 1.1 }],
          baseRates: [band()],
        });
        expect(result.success).toBe(false);
      }
    });

    it('requires only SUM_ASSURED_BAND when priced -- AGE is the rate table key', () => {
      // Exactly the table that the OLD schema rejected and the server accepts.
      const result = termLife.safeParse(pricedValid());
      expect(result.success).toBe(true);
    });

    it('still requires AGE and SUM_ASSURED_BAND when the version is unpriced', () => {
      expect(termLife.safeParse({ ...valid(), ratingTable: [sumRow], baseRates: [] }).success).toBe(false);
      expect(termLife.safeParse({ ...valid(), ratingTable: [ageRow, sumRow], baseRates: [] }).success).toBe(true);
    });

    it('refuses a rate of zero or less, which the database CHECK also refuses', () => {
      for (const rate of ['0', '-1']) {
        const result = termLife.safeParse({
          ...valid(),
          ratingTable: [sumRow],
          baseRates: [band({ nonSmoker: rate })],
        });
        expect(result.success).toBe(false);
      }
    });

    it('refuses a band that ends before it begins, and one with no rate at all', () => {
      expect(
        termLife.safeParse({
          ...valid(),
          ratingTable: [sumRow],
          baseRates: [band({ ageFrom: '30', ageTo: '18' })],
        }).success,
      ).toBe(false);

      expect(
        termLife.safeParse({
          ...valid(),
          ratingTable: [sumRow],
          baseRates: [band({ nonSmoker: '', smoker: '', unknown: '' })],
        }).success,
      ).toBe(false);
    });

    /**
     * Mirrors `rejectOverlappingAgeBands`, and its reason: an age falling in two
     * bands "would price differently depending on row order".
     */
    it('refuses overlapping bands for the same sex and smoker status', () => {
      const result = termLife.safeParse({
        ...valid(),
        ratingTable: [sumRow],
        baseRates: [band({ ageFrom: '18', ageTo: '30' }), band({ ageFrom: '25', ageTo: '40' })],
      });
      expect(result.success).toBe(false);
    });

    it('allows the same band twice when the two rows price different smoker statuses', () => {
      // Splitting one band across two lines is legitimate: no age resolves to two
      // rates, because no (sex, smokerStatus) is priced twice.
      // The MALE half is present for the same reason it is everywhere else now: a priced
      // version must be able to price every life it accepts. The subject is still the split.
      const result = termLife.safeParse(
        pricedValid({
          baseRates: [
            band({ nonSmoker: '0.62', smoker: '', unknown: '' }),
            band({ nonSmoker: '', smoker: '1.488', unknown: '' }),
            band({ sex: 'MALE', nonSmoker: '0.837', smoker: '', unknown: '' }),
            band({ sex: 'MALE', nonSmoker: '', smoker: '2.0088', unknown: '' }),
          ],
        }),
      );
      expect(result.success).toBe(true);
    });

    /*
      Term bands (product step 1, D4): a rate may differ by policy term. Both bounds or
      neither, mirroring base_rate_term_range_shape, and two cells collide only when BOTH
      their ages and their terms overlap -- an unbanded row overlaps every term.
    */
    it('refuses a term band with only one bound, in the server wording', () => {
      const result = termLife.safeParse(pricedValid({
        baseRates: [band({ termFromMonths: '12' }), band({ sex: 'MALE' })],
      }));
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain(
        'A base rate term band needs both a from and a to month, or neither');
    });

    it('allows one age band priced twice for two different terms', () => {
      const result = termLife.safeParse(pricedValid({
        baseRates: [
          band({ termFromMonths: '1', termToMonths: '120' }),
          band({ termFromMonths: '121', termToMonths: '240', nonSmoker: '0.7' }),
          band({ sex: 'MALE' }),
        ],
      }));
      expect(result.success).toBe(true);
      const wire = toApiRequest(result.data!).baseRates!;
      expect(wire).toEqual(expect.arrayContaining([
        { ageFrom: 18, ageTo: 30, sex: 'FEMALE', smokerStatus: 'NON_SMOKER', ratePerMille: 0.7,
          termFromMonths: 121, termToMonths: 240 },
      ]));
      // An unbanded row sends no term keys at all, rather than nulls.
      expect(wire.find((c) => c.sex === 'MALE')).not.toHaveProperty('termFromMonths');
    });

    it('refuses an unbanded row beside a term-banded one for the same age, sex and status', () => {
      const result = termLife.safeParse(pricedValid({
        baseRates: [
          band({ termFromMonths: '1', termToMonths: '120' }),
          band(),
          band({ sex: 'MALE' }),
        ],
      }));
      expect(result.success).toBe(false);
    });

    it('allows the same band for both sexes -- which is what "add age band" produces', () => {
      // pricedValid()'s own shape is exactly this pair; it adds only the declared entry-age
      // range, which a priced version must now carry.
      const result = termLife.safeParse(pricedValid());
      expect(result.success).toBe(true);
    });
  });

  /*
    The cash-value table (product step 1). Every message is CashValuePlanValidator's own, so
    the form refuses exactly what a 422 would, in the same words.
  */
  describe('cash value', () => {
    const endowment = publishVersionFormSchema('ENDOWMENT');
    const row = (over: Record<string, string> = {}) => ({
      policyYear: '2', ageFrom: '', ageTo: '', cashValuePerMille: '200', paidUpPerMille: '', ...over,
    });
    // An endowment must carry exactly one MATURITY row as of product step 2 -- an endowment that
    // pays nothing at the end of its term is not an endowment -- so these cash-value fixtures
    // carry one or nothing parses.
    const endowmentValid = () => ({ ...valid(), payoutRows: [maturityRow] });
    const withTable = (over: Record<string, unknown> = {}) => ({
      ...endowmentValid(),
      cashValueBasisReference: 'ACT/2026/ENDOW-01',
      cashValueBasisDate: '2026-01-10',
      cashValuePaidUpBasis: 'PROPORTIONATE',
      cashValueMinYears: '2',
      cashValueRows: [row(), row({ policyYear: '3', cashValuePerMille: '300' })],
      ...over,
    });
    const messages = (r: { error?: { issues: { message: string }[] } }) =>
      (r.error?.issues ?? []).map((i) => i.message);

    it('refuses an endowment with no maturity row, in the server wording', () => {
      // PayoutPlanValidator.checkCategory: an endowment that pays nothing at the end of its term
      // is not an endowment, and the 422 says exactly this.
      expect(messages(endowment.safeParse(valid()))).toContain(
        'An ENDOWMENT product must carry exactly one MATURITY row',
      );
    });

    it('sends no cashValue block when none was authored', () => {
      const result = endowment.safeParse(endowmentValid());
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!)).not.toHaveProperty('cashValue');
    });

    it('sends a signed table, rows as numbers and blanks omitted', () => {
      const result = endowment.safeParse(withTable());
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).cashValue).toEqual({
        basisReference: 'ACT/2026/ENDOW-01',
        basisDate: '2026-01-10',
        paidUpBasis: 'PROPORTIONATE',
        minYearsForValue: 2,
        rows: [{ policyYear: 2, cashValuePerMille: 200 }, { policyYear: 3, cashValuePerMille: 300 }],
      });
    });

    it('refuses a table on pure protection', () => {
      expect(messages(termLife.safeParse(withTable()))).toContain(
        'A TERM_LIFE product cannot carry a cash-value table');
    });

    it('refuses a table without its actuarial sign-off', () => {
      expect(messages(endowment.safeParse(withTable({ cashValueBasisReference: '' })))).toContain(
        'A cash-value table needs the actuarial basis reference and date it was signed off under');
    });

    it('refuses minimum years other than 2 or 3, and a missing basis', () => {
      expect(messages(endowment.safeParse(withTable({ cashValueMinYears: '' })))).toContain(
        'A cash-value table needs the minimum years before any value exists, 2 or 3');
      expect(messages(endowment.safeParse(withTable({ cashValuePaidUpBasis: '' })))).toContain(
        'The paid-up basis must be PROPORTIONATE or TABLE');
    });

    it('needs a paid-up value on every row on a TABLE basis', () => {
      expect(messages(endowment.safeParse(withTable({ cashValuePaidUpBasis: 'TABLE' })))).toContain(
        'A TABLE paid-up basis needs a paid-up value on every row (policy year 2 has none)');
    });

    it('refuses overlapping rows for one policy year', () => {
      const result = endowment.safeParse(withTable({
        cashValueRows: [row({ ageFrom: '18', ageTo: '40' }), row({ ageFrom: '35', ageTo: '60' })],
      }));
      expect(messages(result)).toContain(
        'Cash-value rows for policy year 2, ages 18-40 and 35-60, overlap -- an entry age in both would be valued differently depending on row order');
    });

    it('refuses a signed-off table with no rows', () => {
      expect(messages(endowment.safeParse(withTable({ cashValueRows: [] })))).toContain(
        'A cash-value table needs at least one row');
    });
  });

  describe('frequency loading', () => {
    it('sends no frequencyLoading block when both loadings are blank', () => {
      const result = termLife.safeParse(valid());
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!)).not.toHaveProperty('frequencyLoading');
    });

    it('sends the loadings an actuary typed', () => {
      const result = termLife.safeParse({
        ...valid(),
        monthlyLoadingPercent: '8',
        quarterlyLoadingPercent: '3',
      });
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).frequencyLoading).toEqual({
        monthlyPercent: 8,
        quarterlyPercent: 3,
      });
    });

    it('refuses a loading outside 0 to 100', () => {
      expect(termLife.safeParse({ ...valid(), monthlyLoadingPercent: '-1' }).success).toBe(false);
      expect(termLife.safeParse({ ...valid(), monthlyLoadingPercent: '101' }).success).toBe(false);
      expect(termLife.safeParse({ ...valid(), quarterlyLoadingPercent: 'abc' }).success).toBe(false);
    });
  });

  describe('TIRA filing', () => {
    it('refuses a publish with no filing reference', () => {
      const result = termLife.safeParse({ ...valid(), tiraReference: '' });
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain('TIRA');
    });

    it('refuses an approval date in the future', () => {
      const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);
      expect(termLife.safeParse({ ...valid(), tiraApprovalDate: tomorrow }).success).toBe(false);
    });

    it('sends the filing an actuary recorded', () => {
      const result = termLife.safeParse(valid());
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).tiraFiling).toEqual({
        reference: 'TIRA/LIFE/2026/0001',
        approvalDate: '2026-01-15',
      });
    });
  });

  describe('benefit amounts', () => {
    // The benefit schedule is what a claim is valued against as of V13, so the rules
    // below mirror ProductApiImpl.publishVersion rather than merely tidying the form.
    it('requires at least one benefit', () => {
      const result = termLife.safeParse({ ...valid(), benefitSchedule: [] });
      expect(result.success).toBe(false);
      expect(JSON.stringify(result.error?.issues)).toContain('at least one benefit');
    });

    it('accepts a populated benefit schedule', () => {
      expect(termLife.safeParse(valid()).success).toBe(true);
    });

    it('rejects a benefit row with a blank calculation method', () => {
      expect(
        termLife.safeParse({
          ...valid(),
          benefitSchedule: [{ ...deathBenefit, calculationMethod: '' }],
        }).success,
      ).toBe(false);
    });

    it('requires a percentage on a PERCENTAGE_OF_SUM_ASSURED benefit', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [
          {
            benefitType: 'CRITICAL_ILLNESS',
            calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED',
            percent: '',
            flatAmount: '',
          },
        ],
      });
      expect(result.success).toBe(false);
    });

    it('requires an amount on a FLAT_AMOUNT benefit', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [
          { benefitType: 'DISABILITY', calculationMethod: 'FLAT_AMOUNT', percent: '', flatAmount: '' },
        ],
      });
      expect(result.success).toBe(false);
    });

    // The server's amount_shape CHECK refuses an amount the method does not use, so the
    // form has to refuse it too rather than let the staff user learn it from a 422.
    it('rejects an amount the method does not use', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [{ ...deathBenefit, flatAmount: '500000' }],
      });
      expect(result.success).toBe(false);
    });

    it('sends a percentage benefit as a number', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [
          {
            benefitType: 'CRITICAL_ILLNESS',
            calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED',
            percent: '25',
            flatAmount: '',
          },
        ],
      });
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).benefitSchedule[0]).toEqual({
        benefitType: 'CRITICAL_ILLNESS',
        calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED',
        percent: 25,
      });
    });

    it('sends a flat benefit as a number', () => {
      const result = termLife.safeParse({
        ...valid(),
        benefitSchedule: [
          {
            benefitType: 'DISABILITY',
            calculationMethod: 'FLAT_AMOUNT',
            percent: '',
            flatAmount: '500000',
          },
        ],
      });
      expect(result.success).toBe(true);
      expect(toApiRequest(result.data!).benefitSchedule[0]).toEqual({
        benefitType: 'DISABILITY',
        calculationMethod: 'FLAT_AMOUNT',
        flatAmount: 500000,
      });
    });
  });

  // ProductApiImpl.publishVersion: fund definitions are rejected outright for any
  // category other than UNIT_LINKED, even a well-formed one.
  it('rejects fund definitions for a non-UNIT_LINKED product', () => {
    const result = termLife.safeParse({
      ...valid(),
      fundDefinitions: [{ fundCode: 'FUND-A', currentNav: 100 }],
    });
    expect(result.success).toBe(false);
  });

  it('accepts fund definitions for a UNIT_LINKED product', () => {
    const unitLinked = publishVersionFormSchema('UNIT_LINKED');
    const result = unitLinked.safeParse({
      ...valid(),
      fundDefinitions: [{ fundCode: 'FUND-A', currentNav: 100 }],
    });
    expect(result.success).toBe(true);
  });

  it('accepts an empty fundDefinitions array regardless of category', () => {
    expect(termLife.safeParse({ ...valid(), fundDefinitions: [] }).success).toBe(true);
  });

  it('rejects a missing effectiveDate', () => {
    expect(termLife.safeParse({ ...valid(), effectiveDate: '' }).success).toBe(false);
  });

  it('accepts a blank retirementDate as "none"', () => {
    expect(termLife.safeParse({ ...valid(), retirementDate: '' }).success).toBe(true);
  });

  it('accepts a real retirementDate', () => {
    expect(termLife.safeParse({ ...valid(), retirementDate: '2030-01-01' }).success).toBe(true);
  });
});

describe('toApiRequest', () => {
  const termLife = publishVersionFormSchema('TERM_LIFE');

  it('sends retirementDate as null, not an empty string, when left blank', () => {
    const parsed = termLife.parse(valid());
    expect(toApiRequest(parsed).retirementDate).toBeNull();
  });

  it('sends a real retirementDate through untouched', () => {
    const parsed = termLife.parse({ ...valid(), retirementDate: '2030-01-01' });
    expect(toApiRequest(parsed).retirementDate).toBe('2030-01-01');
  });

  it('sends fundDefinitions as an empty array, not null, when none are added', () => {
    const parsed = termLife.parse(valid());
    expect(toApiRequest(parsed).fundDefinitions).toEqual([]);
  });

  it('sends age bounds as numbers on AGE rows, and omits them everywhere else', () => {
    // Not verbatim any more, and deliberately so. The form holds age bounds as strings (an
    // empty numeric input is '', and coercing that to 0 would be a real age rather than an
    // absence), while the wire wants integers. Non-AGE rows must carry no bounds at all --
    // the backend's rating_table_age_bounds_shape CHECK refuses them there.
    const parsed = termLife.parse(valid());
    const api = toApiRequest(parsed);

    expect(api.ratingTable).toEqual([
      { factorType: 'AGE', band: '18-30', multiplier: 1, ageFrom: 18, ageTo: 30 },
      {
        factorType: 'SUM_ASSURED_BAND',
        band: '0-5000000',
        multiplier: 1.1,
        sumAssuredFrom: 0,
        sumAssuredTo: 5000000,
      },
    ]);
    // Numbers on the wire, not the strings the form holds: the backend binds these to
    // BigDecimal and a quoted "5000000" is a different request body than 5000000.
    expect(typeof api.ratingTable[1].sumAssuredTo).toBe('number');
    // Each row carries only the bounds its own factor type has. The backend CHECK refuses
    // the others outright, so sending them would be a 422 rather than a tidiness question.
    expect(api.ratingTable[1]).not.toHaveProperty('ageFrom');
    expect(api.ratingTable[0]).not.toHaveProperty('sumAssuredFrom');
    // No percent or flatAmount key at all: SUM_ASSURED uses neither, and the server's
    // amount_shape CHECK refuses the one it does not use rather than ignoring it.
    expect(api.benefitSchedule).toEqual([
      { benefitType: 'DEATH', calculationMethod: 'SUM_ASSURED' },
    ]);
  });

  it('rejects an AGE row with no range', () => {
    // The row would match no applicant and contribute a silent neutral 1.0 -- which is
    // exactly how age went unrated on this platform until product V5.
    const noRange = { factorType: 'AGE' as const, band: '18-30', multiplier: 1 };
    expect(termLife.safeParse({ ...valid(), ratingTable: [noRange, sumRow] }).success).toBe(false);
  });

  it('rejects an AGE row whose range ends before it begins', () => {
    const backwards = { ...ageRow, ageFrom: '40', ageTo: '30' };
    expect(termLife.safeParse({ ...valid(), ratingTable: [backwards, sumRow] }).success).toBe(false);
  });

  /**
   * A sum assured band that RATES must say whom it rates.
   *
   * The asymmetry with the neutral case below is deliberate and mirrors
   * ProductApiImpl.rejectMalformedSumAssuredBands: a multiplier of exactly 1 changes no price
   * whether it resolves or not, while a real multiplier with no range is a number that can
   * never reach a premium -- which is the entire defect product V9 removes.
   */
  it('rejects a sum assured row that rates but carries no range', () => {
    const noRange = { factorType: 'SUM_ASSURED_BAND' as const, band: '5000000', multiplier: 1.5 };
    expect(termLife.safeParse({ ...valid(), ratingTable: [ageRow, noRange] }).success).toBe(false);
  });

  it('accepts a neutral sum assured row with no range', () => {
    // Around eighty fixtures carry exactly this row to satisfy the coverage rule, and
    // inventing an amount range for them would make none of them more correct.
    const neutral = { factorType: 'SUM_ASSURED_BAND' as const, band: 'LOW', multiplier: 1 };
    expect(termLife.safeParse({ ...valid(), ratingTable: [ageRow, neutral] }).success).toBe(true);
  });

  it('rejects a sum assured range that ends before it begins', () => {
    const backwards = { ...sumRow, sumAssuredFrom: '5000000', sumAssuredTo: '1000000' };
    expect(termLife.safeParse({ ...valid(), ratingTable: [ageRow, backwards] }).success).toBe(false);
  });

  it('rejects a negative sum assured bound', () => {
    const negative = { ...sumRow, sumAssuredFrom: '-1' };
    expect(termLife.safeParse({ ...valid(), ratingTable: [ageRow, negative] }).success).toBe(false);
  });

  it('labels a blank sum assured band from its range on the wire', () => {
    const result = termLife.safeParse({
      ...valid(),
      ratingTable: [ageRow, { ...sumRow, band: '' }],
    });
    expect(result.success).toBe(true);

    // Derived rather than sent blank, exactly as an AGE row's label is: the band cannot
    // contradict the bounds it is read beside, because nobody typed it.
    expect(toApiRequest(result.data!).ratingTable![1]).toMatchObject({
      band: '0-5000000',
      sumAssuredFrom: 0,
      sumAssuredTo: 5000000,
    });
  });
});

describe('eligibility bounds', () => {
  const termLife = publishVersionFormSchema('TERM_LIFE');

  const bounded = () => ({
    ...valid(),
    minEntryAge: '18',
    maxEntryAge: '65',
    minTermMonths: '60',
    maxTermMonths: '360',
    minSumAssured: '500000.00',
    maxSumAssured: '300000000.00',
  });

  it('sends every bound that was set, as numbers', () => {
    const request = toApiRequest(termLife.parse(bounded()));
    expect(request.eligibility).toEqual({
      minEntryAge: 18,
      maxEntryAge: 65,
      minTermMonths: 60,
      maxTermMonths: 360,
      minSumAssured: 500000,
      maxSumAssured: 300000000,
    });
  });

  // An unbounded version is a real product design. Omitting the block says so more
  // plainly than an object of six nulls, and means the same thing to the backend.
  it('omits the eligibility block entirely when nothing is bounded', () => {
    const request = toApiRequest(termLife.parse(valid()));
    expect('eligibility' in request).toBe(false);
  });

  it('sends only the bounds that were set', () => {
    const request = toApiRequest(termLife.parse({ ...valid(), maxEntryAge: '65' }));
    expect(request.eligibility).toEqual({ maxEntryAge: 65 });
  });

  // Blank must stay "no bound" rather than coercing to a bound of zero, which the
  // backend's CHECKs would reject and which would mean something quite different.
  it('treats a blank bound as absent, not as zero', () => {
    const request = toApiRequest(termLife.parse({ ...valid(), minSumAssured: '' }));
    expect('eligibility' in request).toBe(false);
  });

  it('rejects a maximum below its minimum, on each pair', () => {
    expect(termLife.safeParse({ ...bounded(), maxEntryAge: '17' }).success).toBe(false);
    expect(termLife.safeParse({ ...bounded(), maxTermMonths: '12' }).success).toBe(false);
    expect(termLife.safeParse({ ...bounded(), maxSumAssured: '1000.00' }).success).toBe(false);
  });

  it('accepts a maximum equal to its minimum', () => {
    expect(termLife.safeParse({ ...bounded(), maxEntryAge: '18' }).success).toBe(true);
  });

  it('accepts one half of a pair without the other', () => {
    expect(termLife.safeParse({ ...valid(), maxEntryAge: '65' }).success).toBe(true);
    expect(termLife.safeParse({ ...valid(), minEntryAge: '18' }).success).toBe(true);
  });

  it('rejects an entry age outside a human lifetime', () => {
    expect(termLife.safeParse({ ...valid(), maxEntryAge: '150' }).success).toBe(false);
  });

  it('rejects a zero or fractional term bound', () => {
    expect(termLife.safeParse({ ...valid(), minTermMonths: '0' }).success).toBe(false);
    expect(termLife.safeParse({ ...valid(), minTermMonths: '12.5' }).success).toBe(false);
  });
});

/*
  The account value basis (product step 3). Every message is AccumulationPlanValidator's or
  PayoutPlanValidator.checkAccountRules' own, so the form refuses exactly what a 422 would.
*/
describe('account value basis', () => {
  const endowment = publishVersionFormSchema('ENDOWMENT');
  const accountMaturity = { ...maturityRow, amountBasis: 'ACCOUNT_VALUE' };
  const charge = (over: Record<string, string> = {}) => ({
    fromPolicyYear: '1', toPolicyYear: '', contributionAllocationPercent: '5', transferAllocationPercent: '0',
    monthlyPolicyFee: '1000', ...over,
  });
  const account = (over: Record<string, unknown> = {}) => ({
    ...valid(),
    payoutRows: [accountMaturity],
    valueBasis: 'ACCOUNT',
    guaranteedRatePercent: '3',
    minimumBalance: '50000',
    accountCharges: [charge({ toPolicyYear: '1' }), charge({ fromPolicyYear: '2', contributionAllocationPercent: '1' })],
    ...over,
  });
  const messages = (r: { error?: { issues: { message: string }[] } }) => (r.error?.issues ?? []).map((i) => i.message);

  it('sends the accumulation block, numbers on the wire, the open-ended row without a to-year', () => {
    const result = endowment.safeParse(account());
    expect(result.success).toBe(true);
    expect(toApiRequest(result.data!).accumulation).toEqual({
      guaranteedRatePercent: 3,
      minimumBalance: 50000,
      charges: [
        { fromPolicyYear: 1, toPolicyYear: 1, contributionAllocationPercent: 5, transferAllocationPercent: 0, monthlyPolicyFee: 1000 },
        { fromPolicyYear: 2, contributionAllocationPercent: 1, transferAllocationPercent: 0, monthlyPolicyFee: 1000 },
      ],
    });
  });

  it('sends nothing for a scale version', () => {
    const result = endowment.safeParse({ ...valid(), payoutRows: [maturityRow] });
    expect(toApiRequest(result.data!)).not.toHaveProperty('accumulation');
  });

  it('refuses an account basis outside the three savings categories', () => {
    expect(messages(publishVersionFormSchema('ANNUITY').safeParse(account({ payoutRows: [] })))).toContain(
      'A ANNUITY product cannot use an account value basis',
    );
  });

  it('refuses a version valued both ways', () => {
    expect(messages(endowment.safeParse(account({ cashValueBasisReference: 'ACT/1' })))).toContain(
      'A version is valued either by a cash-value scale or by an account, not both',
    );
  });

  it('refuses a missing guarantee and a missing minimum balance', () => {
    const issues = messages(endowment.safeParse(account({ guaranteedRatePercent: '', minimumBalance: '' })));
    expect(issues).toContain('An account-based version needs a guaranteed interest rate between 0 and 100 percent');
    expect(issues).toContain('An account-based version needs a minimum balance for withdrawals, zero or more');
  });

  it('refuses charges that do not cover every policy year once', () => {
    expect(messages(endowment.safeParse(account({ accountCharges: [charge({ fromPolicyYear: '2' })] })))).toContain(
      'Account charges must start at policy year 1',
    );
    expect(messages(endowment.safeParse(account({ accountCharges: [charge({ toPolicyYear: '2' }), charge({ fromPolicyYear: '4' })] })))).toContain(
      'Account charges leave policy year 3 uncovered',
    );
    expect(messages(endowment.safeParse(account({ accountCharges: [charge({ toPolicyYear: '2' })] })))).toContain(
      'The last account charge row must be open-ended, so every policy year has a charge',
    );
  });

  it('refuses a maturity off the sum assured on an account version, and the account value on a scale one', () => {
    expect(messages(endowment.safeParse(account({ payoutRows: [maturityRow] })))).toContain(
      "An account-based version's maturity pays the account value",
    );
    expect(messages(endowment.safeParse({ ...valid(), payoutRows: [accountMaturity] }))).toContain(
      'Only an account-based version can pay the account value',
    );
  });

  it('refuses an account-value maturity at anything but 100', () => {
    expect(messages(endowment.safeParse(account({ payoutRows: [{ ...accountMaturity, amountValue: '50' }] })))).toContain(
      'An account-value maturity pays the whole account (100)',
    );
  });
});

describe('fixed-term deposit', () => {
  const endowment = publishVersionFormSchema('ENDOWMENT');
  const messages = (r: { error?: { issues: { message: string }[] } }) => (r.error?.issues ?? []).map((i) => i.message);
  // The user's grid: from each band start, the rate for 3, 6 and 12 months -- each for the TERM. An
  // amount above a band's top goes to the next band, so each starts a cent above the one before.
  const deposit = (over: Record<string, unknown> = {}) => ({
    ...valid(),
    valueBasis: 'DEPOSIT',
    depositTerms: [{ months: '3' }, { months: '6' }, { months: '12' }],
    depositBands: [
      { minAmount: '500000', rates: ['3', '4', '5'] },
      { minAmount: '5000000.01', rates: ['4', '5', '6'] },
      { minAmount: '10000000.01', rates: ['5', '6', '7'] },
      { minAmount: '20000000.01', rates: ['6', '7', '8'] },
    ],
    payoutRows: [],
    ...over,
  });

  it("accepts the user's grid with no payout schedule, and sends one rate per cell and no account block", () => {
    const result = endowment.safeParse(deposit());
    expect(result.success).toBe(true);
    const body = toApiRequest(result.data!);
    expect(body.deposit?.rates).toHaveLength(12);
    expect(body.deposit?.rates).toContainEqual({ minAmount: 5000000.01, termMonths: 6, ratePercent: 5 });
    expect(body).not.toHaveProperty('accumulation');
  });

  it('names an empty cell by its band and term', () => {
    const bands = deposit().depositBands.map((b, i) => (i === 1 ? { ...b, rates: ['4', '', '6'] } : b));
    expect(messages(endowment.safeParse(deposit({ depositBands: bands })))).toContain(
      'The band from 5000000.01 does not offer a 6-month term',
    );
  });

  it('refuses a payout schedule and a frequency loading', () => {
    expect(messages(endowment.safeParse(deposit({ payoutRows: [maturityRow] })))).toContain(
      'A fixed-term deposit matures through its account; it carries no payout schedule',
    );
    expect(messages(endowment.safeParse(deposit({ monthlyLoadingPercent: '5' })))).toContain(
      'A fixed-term deposit is paid once; it takes no frequency loading',
    );
  });

  it('refuses a deposit on a protection product, and a lowest band off the minimum sum assured', () => {
    expect(messages(publishVersionFormSchema('TERM_LIFE').safeParse(deposit()))).toContain(
      'A TERM_LIFE product cannot be a fixed-term deposit',
    );
    expect(messages(endowment.safeParse(deposit({ minSumAssured: '1000000' })))).toContain(
      "The lowest deposit band must start at the version's minimum sum assured (1000000)",
    );
  });
});

describe('with profits', () => {
  const endowment = publishVersionFormSchema('ENDOWMENT');
  const withProfits = (over: Record<string, unknown> = {}) => ({
    ...valid(),
    payoutRows: [maturityRow],
    withProfits: true,
    bonusMethod: 'COMPOUND',
    bonusPaidUpParticipates: false,
    bonusSurrenderBasis: 'OWN_SCALE',
    bonusSurrenderRows: [{ fromCompletedYears: '0', perMille: '400' }],
    ...over,
  });
  const issues = (r: { error?: { issues: { message: string; path: PropertyKey[] }[] } }) => r.error?.issues ?? [];
  const messages = (r: { error?: { issues: { message: string; path: PropertyKey[] }[] } }) => issues(r).map((i) => i.message);

  /**
   * Every path the form renders an error under (plan §12, L10): a message landing anywhere else
   * would make Publish silently do nothing.
   */
  const RENDERED = /^(withProfits|bonusMethod|bonusSurrenderBasis|bonusSurrenderRows|bonusSurrenderRows\.\d+\.(fromCompletedYears|perMille))$/;
  const expectRefusal = (result: ReturnType<typeof endowment.safeParse>, message: string) => {
    const issue = issues(result).find((i) => i.message === message);
    expect(issue, message).toBeDefined();
    expect(issue!.path.join('.')).toMatch(RENDERED);
  };

  it('sends the bonus block, rows only for OWN_SCALE', () => {
    const result = endowment.safeParse(withProfits());
    expect(result.success).toBe(true);
    expect(toApiRequest(result.data!).bonus).toEqual({
      method: 'COMPOUND',
      paidUpParticipates: false,
      surrenderBasis: 'OWN_SCALE',
      surrenderRows: [{ fromCompletedYears: 0, perMille: 400 }],
    });
  });

  it('sends nothing for a version that is not with-profits', () => {
    const result = endowment.safeParse({ ...valid(), payoutRows: [maturityRow] });
    expect(toApiRequest(result.data!)).not.toHaveProperty('bonus');
  });

  it('refuses a category that cannot be with-profits', () => {
    expectRefusal(publishVersionFormSchema('EDUCATION_SAVINGS').safeParse(withProfits()), 'A EDUCATION_SAVINGS product cannot be with-profits');
  });

  it('refuses a deposit and an account version', () => {
    expectRefusal(endowment.safeParse(withProfits({ valueBasis: 'DEPOSIT' })), 'A fixed-term deposit cannot be with-profits');
    expectRefusal(
      endowment.safeParse(withProfits({ valueBasis: 'ACCOUNT' })),
      'A version is valued either by an account or with profits, not both',
    );
  });

  it('needs a bonus method', () => {
    expectRefusal(
      endowment.safeParse(withProfits({ bonusMethod: '' })),
      'A with-profits version must state its bonus method (SIMPLE or COMPOUND)',
    );
  });

  it('has no default surrender basis -- an empty one is refused', () => {
    expectRefusal(
      endowment.safeParse(withProfits({ bonusSurrenderBasis: '', bonusSurrenderRows: [] })),
      'A with-profits version must state how attached bonuses count toward surrender (NONE, SUM_ASSURED_SCALE or OWN_SCALE)',
    );
  });

  it('refuses rows on a basis that is not OWN_SCALE', () => {
    expectRefusal(endowment.safeParse(withProfits({ bonusSurrenderBasis: 'NONE' })), 'Bonus surrender rows are only for OWN_SCALE');
  });

  it("refuses SUM_ASSURED_SCALE without the version's own cash-value scale", () => {
    expectRefusal(
      endowment.safeParse(withProfits({ bonusSurrenderBasis: 'SUM_ASSURED_SCALE', bonusSurrenderRows: [] })),
      "SUM_ASSURED_SCALE needs the version's own cash-value scale",
    );
  });

  it('needs at least one OWN_SCALE row', () => {
    expectRefusal(
      endowment.safeParse(withProfits({ bonusSurrenderRows: [] })),
      'OWN_SCALE needs at least one row of bonus surrender values',
    );
  });

  it('refuses a row before year 0, a repeated start, and a value outside 0-1000', () => {
    expectRefusal(
      endowment.safeParse(withProfits({ bonusSurrenderRows: [{ fromCompletedYears: '-1', perMille: '400' }] })),
      'A bonus surrender row cannot start before year 0',
    );
    expectRefusal(
      endowment.safeParse(withProfits({
        bonusSurrenderRows: [{ fromCompletedYears: '0', perMille: '400' }, { fromCompletedYears: '0', perMille: '500' }],
      })),
      'Bonus surrender rows must each start at a different completed year',
    );
    expectRefusal(
      endowment.safeParse(withProfits({ bonusSurrenderRows: [{ fromCompletedYears: '0', perMille: '1001' }] })),
      'A bonus surrender value must be between 0 and 1000 per mille',
    );
  });

  it('refuses a with-profits endowment with no maturity payout', () => {
    expect(messages(endowment.safeParse(withProfits({ payoutRows: [] })))).toContain(
      'A with-profits ENDOWMENT needs a MATURITY payout, or its bonuses could never be paid at term end',
    );
  });
});
