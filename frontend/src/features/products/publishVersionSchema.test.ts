import { describe, expect, it } from 'vitest';
import { publishVersionFormSchema, toApiRequest } from './publishVersionSchema';

// AGE rows carry a real range as of product V5: age is resolved by range, not by matching
// the band text, so an AGE row without one would match nobody and silently rate neutral.
const ageRow = { factorType: 'AGE' as const, band: '18-30', multiplier: 1, ageFrom: '18', ageTo: '30' };
const sumRow = { factorType: 'SUM_ASSURED_BAND' as const, band: '0-5000000', multiplier: 1.1 };

const valid = () => ({
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

  it('accepts extra factor types alongside the two required ones', () => {
    const result = termLife.safeParse({
      ...valid(),
      ratingTable: [ageRow, sumRow, { factorType: 'SMOKER_STATUS', band: 'NON_SMOKER', multiplier: 0.9 }],
    });
    expect(result.success).toBe(true);
  });

  it('rejects a rating row with a blank band', () => {
    expect(
      termLife.safeParse({ ...valid(), ratingTable: [{ ...ageRow, band: '' }, sumRow] }).success,
    ).toBe(false);
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
      { factorType: 'SUM_ASSURED_BAND', band: '0-5000000', multiplier: 1.1 },
    ]);
    expect(api.ratingTable[1]).not.toHaveProperty('ageFrom');
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
});
