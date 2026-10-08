import { describe, expect, it } from 'vitest';
import { blankFuneralPlan, bandsFor, parsePremiums, premiumsFromRows, type FuneralFields } from './funeralSchema';
import { blankPublishVersionForm, publishVersionFormSchema, toApiRequest } from './publishVersionSchema';

/**
 * Plan B: main member and children only (spouse, parents and extended family switched off). The defaults price a
 * main member from 18 to the highest priced age, 70, and a child from 0 to 24 (cover stops at 21, 25 a student).
 */
const PRICES = { MAIN_MEMBER_first: '60000', CHILD_first: '6000' };

const valid = () => {
  const form = blankPublishVersionForm();
  return {
    ...form,
    effectiveDate: '2026-10-01',
    tiraReference: 'TIRA/FUN/1',
    tiraApprovalDate: '2026-09-01',
    ratingTable: [],
    benefitSchedule: [{ benefitType: 'DEATH' as const, calculationMethod: 'SUM_ASSURED' as const, percent: '', flatAmount: '' }],
    freeLookDays: '30',
    funeralPlans: [{ ...blankFuneralPlan(), planCode: 'B', name: 'Familia B', benefits: ['2000000', '', '1000000', '', ''],
      premiums: PRICES }],
    funeralRoles: form.funeralRoles.map((r) =>
      r.role === 'SPOUSE' || r.role === 'PARENT' || r.role === 'EXTENDED' ? { ...r, allowed: false } : r),
    funeralMaxPricedAge: '70',
    funeralPayee: 'MAIN_MEMBER',
    funeralOnMainMemberDeath: 'POLICY_ENDS',
  };
};

const priced = (premiums: Record<string, string>, extra: object = {}) =>
  ({ ...valid(), funeralPlans: [{ ...valid().funeralPlans[0], premiums }], ...extra });

const messages = (input: unknown, category: 'FUNERAL' | 'TERM_LIFE' = 'FUNERAL') => {
  const r = publishVersionFormSchema(category).safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => i.message);
};

describe('funeral terms on the publish form (FuneralPlanValidator)', () => {
  it('accepts a complete plan with no rating table (R1)', () => {
    expect(messages(valid())).toEqual([]);
  });

  it('needs a plan, a payee and a death rule, in the server words', () => {
    const m = messages({ ...valid(), funeralPlans: [], funeralPayee: '', funeralOnMainMemberDeath: '' });
    expect(m).toContain('A FUNERAL version needs at least one plan');
    expect(m).toContain('Choose who is paid when a dependant dies');
    expect(m).toContain('Choose what happens when the main member dies');
  });

  it('refuses a plan that does not cover the main member', () => {
    const plans = [{ ...blankFuneralPlan(), planCode: 'B', name: 'B', benefits: ['', '', '1000000', '', ''] }];
    expect(messages({ ...valid(), funeralPlans: plans })).toContain('Plan B does not cover the main member');
  });

  it('refuses a duplicate plan code', () => {
    const plan = valid().funeralPlans[0];
    expect(messages({ ...valid(), funeralPlans: [plan, plan] })).toContain('Plan code B appears twice');
  });

  it('includes a dependant at 0 but never the main member (a flat family rate)', () => {
    expect(messages(priced({ MAIN_MEMBER_first: '36000', CHILD_first: '0' }))).toEqual([]);
    expect(messages(priced({ MAIN_MEMBER_first: '0', CHILD_first: '0' })))
      .toContain("Plan B, main member 18–70: the main member's premium must be above zero; nobody is covered free");
  });

  it('names the box with no price, and the one that is not a price', () => {
    expect(messages(priced({ MAIN_MEMBER_first: '60000' }))).toContain('Plan B, child 0–24: enter the yearly premium');
    expect(messages(priced({ ...PRICES, CHILD_first: 'six' }))).toContain('Plan B, child 0–24: a premium is an amount, 0 or more');
  });

  it('splits a role into age bands where staff say its price changes, each band a box to fill', () => {
    const splits = ['41, 56', '', '', '', ''];
    expect(messages(priced(PRICES, { funeralBandSplits: splits }))).toEqual(expect.arrayContaining([
      'Plan B, main member 41–55: enter the yearly premium',
      'Plan B, main member 56–70: enter the yearly premium',
    ]));
    const all = priced({ ...PRICES, MAIN_MEMBER_41: '80000', MAIN_MEMBER_56: '120000' }, { funeralBandSplits: splits });
    expect(messages(all)).toEqual([]);
    expect(toApiRequest(publishVersionFormSchema('FUNERAL').parse(all), 'FUNERAL').funeral?.premiums).toEqual([
      { planCode: 'B', role: 'MAIN_MEMBER', ageFrom: 18, ageTo: 40, yearlyPremium: 60000 },
      { planCode: 'B', role: 'MAIN_MEMBER', ageFrom: 41, ageTo: 55, yearlyPremium: 80000 },
      { planCode: 'B', role: 'MAIN_MEMBER', ageFrom: 56, ageTo: 70, yearlyPremium: 120000 },
      { planCode: 'B', role: 'CHILD', ageFrom: 0, ageTo: 24, yearlyPremium: 6000 },
    ]);
  });

  it('refuses a band that starts outside the priced ages, or out of order', () => {
    expect(messages(priced(PRICES, { funeralBandSplits: ['10', '', '', '', ''] })))
      .toContain('A band starts after the youngest entry age, 18, and no later than 70');
    expect(messages(priced(PRICES, { funeralBandSplits: ['56, 41', '', '', '', ''] })))
      .toContain('List the ages youngest first, each once');
  });

  it('prices a version sold to group schemes only by its group rates, sending no premium table', () => {
    const group = { ...valid(), funeralSoldAs: 'GROUP' };
    expect(messages(group)).toContain(
      'Plan B needs a group rate per member per month above zero: this version is sold to group schemes');
    const rated = { ...group, funeralPlans: [{ ...valid().funeralPlans[0], groupRate: '3000', premiums: {} }] };
    expect(messages(rated)).toEqual([]);
    const request = toApiRequest(publishVersionFormSchema('FUNERAL').parse(rated), 'FUNERAL');
    expect(request.funeral?.soldAs).toBe('GROUP');
    expect(request.funeral?.plans?.[0].groupMonthlyRate).toBe(3000);
    expect(request.funeral?.premiums).toEqual([]);
  });

  it('refuses a group rate on a version sold to individuals only', () => {
    const rated = { ...valid(), funeralPlans: [{ ...valid().funeralPlans[0], groupRate: '3000' }] };
    expect(messages(rated)).toContain('Plan B has a group rate, but this version is sold to individual policies only');
  });

  it('refuses a benefit for a role that is switched off', () => {
    const plans = [{ ...blankFuneralPlan(), planCode: 'B', name: 'B', benefits: ['2000000', '2000000', '1000000', '', ''] }];
    expect(messages({ ...valid(), funeralPlans: plans })).toContain('Plan B covers spouses, but SPOUSE is not allowed');
  });

  it('refuses a waiting period of zero', () => {
    expect(messages({ ...valid(), funeralWaitingMonths: '0' }))
      .toContain('A waiting period is a number of months above zero; leave it empty for none');
  });

  it('sends the terms only on a FUNERAL product, roles switched off left out', () => {
    const parsed = publishVersionFormSchema('FUNERAL').parse(valid());
    const request = toApiRequest(parsed, 'FUNERAL');
    expect(request.funeral?.plans).toEqual([{ planCode: 'B', name: 'Familia B', groupMonthlyRate: null }]);
    expect(request.funeral?.soldAs).toBe('INDIVIDUAL');
    expect(request.funeral?.benefits).toEqual([
      { planCode: 'B', role: 'MAIN_MEMBER', benefit: 2000000 },
      { planCode: 'B', role: 'CHILD', benefit: 1000000 },
    ]);
    expect(request.funeral?.premiums).toHaveLength(2);
    expect(request.funeral?.roles?.map((r) => r.role)).toEqual(['MAIN_MEMBER', 'CHILD']);
    expect(request.funeral?.waitingPeriodMonths).toBe(6);
    expect(request.ratingTable).toEqual([]);
    expect(toApiRequest(publishVersionFormSchema('TERM_LIFE').parse({
      ...valid(),
      ratingTable: [
        { factorType: 'AGE' as const, band: '', multiplier: 1, ageFrom: '0', ageTo: '120', sumAssuredFrom: '', sumAssuredTo: '' },
        { factorType: 'SUM_ASSURED_BAND' as const, band: 'ALL', multiplier: 1, ageFrom: '', ageTo: '', sumAssuredFrom: '', sumAssuredTo: '' },
      ],
    }), 'TERM_LIFE').funeral).toBeUndefined();
  });
});

describe('the premium grid', () => {
  const roleRule = valid().funeralRoles[0];

  it('runs a role from its youngest entry age to its last priced age, split where staff say', () => {
    expect(bandsFor(roleRule, '', '70').bands).toEqual([{ key: 'MAIN_MEMBER_first', from: 18, to: 70 }]);
    expect(bandsFor(roleRule, '41 56', '70').bands.map((b) => [b.from, b.to])).toEqual([[18, 40], [41, 55], [56, 70]]);
  });

  it('fills itself from rows pasted from a spreadsheet', () => {
    const v = valid() as unknown as FuneralFields;
    const rows = 'plan\trole\tfrom\tto\tpremium\nB\tMAIN_MEMBER\t18\t40\t60000\nB\tMAIN_MEMBER\t41\t70\t90000\nB\tCHILD\t0\t24\t0';
    const filled = premiumsFromRows(rows, v);
    expect(filled.error).toBeUndefined();
    expect(filled.splits[0]).toBe('41');
    expect(filled.premiums[0]).toEqual({ MAIN_MEMBER_first: '60000', MAIN_MEMBER_41: '90000', CHILD_first: '0' });
    expect(premiumsFromRows('Z,MAIN_MEMBER,18,70,1', v).error).toBe('Plan Z is not one of the plans above — add it first');
  });
});

describe('parsePremiums', () => {
  it('skips a header and blank lines, and reads tabs as well as commas', () => {
    expect(parsePremiums('plan,role,from,to,premium\n\nB\tCHILD\t0\t24\t6000').rows)
      .toEqual([{ planCode: 'B', role: 'CHILD', ageFrom: 0, ageTo: 24, yearlyPremium: 6000 }]);
  });
});
