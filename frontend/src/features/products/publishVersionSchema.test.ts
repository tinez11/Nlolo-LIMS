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

// Built on the blank form so the fixture always carries every key a real submission has.
const valid = () => ({
  ...blankPublishVersionForm(),
  ifrsMeasurementModel: 'PAA' as const,
  effectiveDate: '2026-01-01',
  retirementDate: '',
  ratingTable: [ageRow, sumRow],
  benefitSchedule: [],
  fundDefinitions: [],
});

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

    it('allows the same band for both sexes -- which is what "add age band" produces', () => {
      // pricedValid()'s own shape is exactly this pair; it adds only the declared entry-age
      // range, which a priced version must now carry.
      const result = termLife.safeParse(pricedValid());
      expect(result.success).toBe(true);
    });
  });

  it('accepts an empty benefit schedule -- no minimum coverage required', () => {
    expect(termLife.safeParse({ ...valid(), benefitSchedule: [] }).success).toBe(true);
  });

  it('accepts a populated benefit schedule', () => {
    const result = termLife.safeParse({
      ...valid(),
      benefitSchedule: [{ benefitType: 'DEATH', calculationMethod: 'sum_assured' }],
    });
    expect(result.success).toBe(true);
  });

  it('rejects a benefit row with a blank calculation method', () => {
    expect(
      termLife.safeParse({
        ...valid(),
        benefitSchedule: [{ benefitType: 'DEATH', calculationMethod: '' }],
      }).success,
    ).toBe(false);
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
    const parsed = termLife.parse({
      ...valid(),
      benefitSchedule: [{ benefitType: 'DEATH', calculationMethod: 'sum_assured' }],
    });
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
    expect(api.benefitSchedule).toEqual([{ benefitType: 'DEATH', calculationMethod: 'sum_assured' }]);
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
