import { describe, expect, it } from 'vitest';
import { blankAnnuityForm, parseRates, rateColumns } from './annuitySchema';
import { blankPublishVersionForm, publishVersionFormSchema, toApiRequest } from './publishVersionSchema';

/** Ages 65-66, unisex, single life: the smallest version that publishes. */
const valid = () => ({
  ...blankPublishVersionForm(),
  effectiveDate: '2026-10-01',
  tiraReference: 'TIRA/ANN/1',
  tiraApprovalDate: '2026-09-01',
  ratingTable: [
    { factorType: 'AGE' as const, band: '', multiplier: 1, ageFrom: '0', ageTo: '120', sumAssuredFrom: '', sumAssuredTo: '' },
    { factorType: 'SUM_ASSURED_BAND' as const, band: 'ALL', multiplier: 1, ageFrom: '', ageTo: '', sumAssuredFrom: '', sumAssuredTo: '' },
  ],
  benefitSchedule: [{ benefitType: 'DEATH' as const, calculationMethod: 'SUM_ASSURED' as const, percent: '', flatAmount: '' }],
  freeLookDays: '30',
  minEntryAge: '65',
  maxEntryAge: '66',
  annuityTiming: 'ARREARS',
  annuityBasisReference: 'a(90) 4%',
  annuityBasisDate: '2026-09-01',
  annuityForms: [{ ...blankAnnuityForm(), formCode: 'L10', guaranteeYears: '10', ratesText: '65, 60\n66, 62' }],
});

const messages = (input: unknown, category: 'ANNUITY' | 'TERM_LIFE' = 'ANNUITY') => {
  const r = publishVersionFormSchema(category).safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => i.message);
};

describe('annuity terms on the publish form (AnnuityPlanValidator)', () => {
  it('accepts a complete single-life version', () => {
    expect(messages(valid())).toEqual([]);
  });

  it('refuses terms on any other category', () => {
    expect(messages(valid(), 'TERM_LIFE')).toContain('Annuity terms are only for an ANNUITY product');
  });

  it('refuses the missing pieces in the server words', () => {
    const m = messages({ ...valid(), annuityForms: [], annuityTiming: '', annuityBasisReference: '', freeLookDays: '' });
    expect(m).toEqual(expect.arrayContaining([
      'An annuity version needs at least one annuity form',
      'An annuity version must state whether income is paid in ARREARS or in ADVANCE',
      'An annuity rate table needs the actuarial basis it was issued under',
      'A free-look period in days is required on an individual product',
    ]));
  });

  it('needs entry ages and names the first age with no rate', () => {
    expect(messages({ ...valid(), minEntryAge: '' })).toContain(
      'An annuity version needs minimum and maximum entry ages, so its grids can be checked for gaps',
    );
    expect(messages({ ...valid(), maxEntryAge: '67' })).toContain('Form L10 has no rate for age 67');
  });

  it('checks a BY_SEX form for both sexes', () => {
    const form = { ...blankAnnuityForm(), formCode: 'S0', rateBasis: 'BY_SEX' as const, ratesText: 'F 65 60\nF 66 61\nM 65 62' };
    expect(messages({ ...valid(), annuityForms: [form] })).toContain('Form S0 has no rate for a MALE aged 66');
  });

  it('needs a survivor percentage and a difference range on a joint form, then covers every difference', () => {
    const joint = { ...blankAnnuityForm(), formCode: 'J50', joint: true, ratesText: '65 -2 0 55\n66 -2 2 56' };
    expect(messages({ ...valid(), annuityForms: [joint] })).toEqual(expect.arrayContaining([
      'Form J50: a joint-life form needs a survivor percentage between 1 and 100',
      "A joint-life form needs the version's range of age differences",
    ]));
    const ranged = { ...valid(), annuityForms: [{ ...joint, survivorPercent: '50' }], annuityJointDiffMin: '-2', annuityJointDiffMax: '2' };
    expect(messages(ranged)).toContain('Form J50 has no rate for age 65 with an age difference of 1');
  });

  it('refuses a form setting out of range, a duplicate code and two forms with the same settings', () => {
    const base = valid().annuityForms[0]!;
    expect(messages({ ...valid(), annuityForms: [{ ...base, escalationPercent: '11' }] })).toContain(
      'Form L10: escalation must be between 0% and 10% a year',
    );
    expect(messages({ ...valid(), annuityForms: [base, base] })).toEqual(expect.arrayContaining([
      'Form code L10 appears more than once',
      'Forms L10 and L10 have the same settings',
    ]));
  });

  it('refuses a bad frequency factor and an ANNUAL factor other than 1', () => {
    const factors = valid().annuityFactors.map((f) =>
      f.frequency === 'MONTHLY' ? { ...f, factor: '1.2' } : f.frequency === 'ANNUAL' ? { ...f, factor: '0.9' } : f,
    );
    expect(messages({ ...valid(), annuityFactors: factors })).toEqual(expect.arrayContaining([
      'A frequency factor must be greater than 0 and at most 1',
      'The ANNUAL frequency factor is 1',
    ]));
  });

  it('names a line that does not parse', () => {
    expect(messages({ ...valid(), annuityForms: [{ ...valid().annuityForms[0]!, ratesText: '65, 60\nsixty-six, 62' }] }))
      .toContain('Form L10, line 2: expected age, rate per 1,000');
  });

  it('sends the block only on an ANNUITY product, with the grid parsed and unoffered frequencies dropped', () => {
    const values = publishVersionFormSchema('ANNUITY').parse({
      ...valid(),
      annuityFactors: valid().annuityFactors.map((f) => (f.frequency === 'MONTHLY' ? { ...f, factor: '0.96' } : f)),
    });
    const annuity = toApiRequest(values, 'ANNUITY').annuity!;
    expect(annuity.timing).toBe('ARREARS');
    expect(annuity.jointAgeDifferenceMin).toBeNull();
    expect(annuity.forms![0]!.rates).toEqual([
      { sex: null, age: 65, ageDifferenceFrom: null, ageDifferenceTo: null, annualRatePerMille: 60 },
      { sex: null, age: 66, ageDifferenceFrom: null, ageDifferenceTo: null, annualRatePerMille: 62 },
    ]);
    expect(annuity.frequencies).toEqual([{ frequency: 'MONTHLY', factor: 0.96 }, { frequency: 'ANNUAL', factor: 1 }]);
    expect(toApiRequest(values, 'WHOLE_LIFE')).not.toHaveProperty('annuity');
  });
});

describe('parseRates', () => {
  it('reads the columns each form carries', () => {
    expect(rateColumns({ joint: true, rateBasis: 'BY_SEX' })).toEqual(['sex', 'age', 'difference from', 'difference to', 'rate per 1,000']);
    expect(parseRates({ ...blankAnnuityForm(), joint: true, rateBasis: 'BY_SEX', ratesText: 'male\t70\t-5\t5\t71.25' }).rates).toEqual([
      { sex: 'MALE', age: 70, ageDifferenceFrom: -5, ageDifferenceTo: 5, annualRatePerMille: 71.25 },
    ]);
  });
});
