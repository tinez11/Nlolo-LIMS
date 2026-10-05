import { describe, expect, it } from 'vitest';
import { blankPublishVersionForm, publishVersionFormSchema, toApiRequest } from './publishVersionSchema';
import { parseAllocation, parseMortality } from './unitLinkedSchema';

const valid = () => ({
  ...blankPublishVersionForm(),
  effectiveDate: '2026-10-01',
  tiraReference: 'TIRA/UL/1',
  tiraApprovalDate: '2026-09-01',
  ratingTable: [],
  benefitSchedule: [{ benefitType: 'DEATH' as const, calculationMethod: 'SUM_ASSURED' as const, percent: '', flatAmount: '' }],
  freeLookDays: '30',
  ulFundCodes: ['EQ-GROWTH', 'MM-CASH'],
  ulAllocationText: 'fromYear,toYear,percent\n1,1,60\n2,,97.5',
  ulPolicyFee: '2500',
  ulMortalityBasis: 'UNISEX',
  ulMortalityText: '18,39,,1.2\n40,,,4.5',
  ulMinimumSurrenderYears: '3',
  ulLowFundMonths: '3',
  ulMinimums: ['100000', '', '', ''],
  ulMultipleMin: '5',
  ulMultipleMax: '50',
});

const messages = (input: unknown, category: 'UNIT_LINKED' | 'TERM_LIFE' = 'UNIT_LINKED') => {
  const r = publishVersionFormSchema(category).safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => i.message);
};

describe('unit-linked terms on the publish form (UnitLinkedPlanValidator)', () => {
  it('accepts complete terms with no rating table: the cost of insurance is the mortality table', () => {
    expect(messages(valid())).toEqual([]);
  });

  it('defaults death to the higher of sum assured and fund value, and lapse to exhaustion', () => {
    const form = blankPublishVersionForm();
    expect(form.ulDeathRule).toBe('HIGHER_OF');
    expect(form.ulLapseRule).toBe('EXHAUSTION');
  });

  it('needs a fund, a fee, and a minimum for at least one frequency, in the server words', () => {
    const m = messages({ ...valid(), ulFundCodes: [], ulPolicyFee: '', ulMinimums: ['', '', '', ''] });
    expect(m).toContain('A UNIT_LINKED version offers at least one fund');
    expect(m).toContain('A monthly policy fee of zero or more is required');
    expect(m).toContain('A UNIT_LINKED version sets a minimum premium for each frequency it takes');
  });

  it('refuses allocation bands that do not start at year 1 or end open', () => {
    expect(messages({ ...valid(), ulAllocationText: '2,,97.5' })).toContain(
      'Allocation bands must run on from year 1 without gaps; band 1 starts at year 2',
    );
    expect(messages({ ...valid(), ulAllocationText: '1,5,95' })).toContain('The last allocation band must be open-ended');
  });

  it('refuses a mortality gap, and a sex on a unisex table', () => {
    expect(messages({ ...valid(), ulMortalityText: '18,39,,1.2\n41,,,4.5' })).toContain(
      'Mortality bands must run on without gaps or overlaps; age 39 is followed by 41',
    );
    expect(messages({ ...valid(), ulMortalityText: '18,,FEMALE,1.2' })).toContain('A UNISEX mortality table has no sex on its rows');
  });

  it('needs both sexes on a by-sex table', () => {
    expect(messages({ ...valid(), ulMortalityBasis: 'BY_SEX', ulMortalityText: '18,,FEMALE,1.2' })).toContain(
      'A BY_SEX mortality table needs FEMALE and MALE rows for every band',
    );
    expect(messages({ ...valid(), ulMortalityBasis: 'BY_SEX', ulMortalityText: '18,,FEMALE,1.2\n18,,MALE,1.5' })).toEqual([]);
  });

  it('refuses a maximum multiple below the minimum', () => {
    expect(messages({ ...valid(), ulMultipleMin: '50', ulMultipleMax: '5' })).toContain(
      "The sum assured's maximum multiple 5 is below its minimum 50",
    );
  });

  it('checks nothing unit-linked on another category', () => {
    expect(messages({ ...valid(), ulFundCodes: [] }, 'TERM_LIFE')).not.toContain('A UNIT_LINKED version offers at least one fund');
  });

  it('sends the terms on a UNIT_LINKED version only, with frequencies left empty not offered', () => {
    const parsed = publishVersionFormSchema('UNIT_LINKED').parse(valid());
    const request = toApiRequest(parsed, 'UNIT_LINKED');
    expect(request.unitLinked).toMatchObject({
      fundCodes: ['EQ-GROWTH', 'MM-CASH'],
      allocationBands: [
        { fromYear: 1, toYear: 1, percent: 60 },
        { fromYear: 2, toYear: null, percent: 97.5 },
      ],
      monthlyPolicyFee: 2500,
      deathRule: 'HIGHER_OF',
      lapseRule: 'EXHAUSTION',
      minimumPremiumYears: null,
      premiumMinimums: [{ frequency: 'MONTHLY', amount: 100000 }],
    });
    expect(toApiRequest(parsed, 'TERM_LIFE').unitLinked).toBeUndefined();
  });

  it('names the first line a pasted grid cannot read', () => {
    expect(parseAllocation('1,1').error).toBe('Line 1 is not fromYear,toYear,percent: "1,1"');
    expect(parseMortality('18,,X,1').error).toBe('Line 1 is not ageFrom,ageTo,sex,ratePerMille: "18,,X,1"');
  });
});
