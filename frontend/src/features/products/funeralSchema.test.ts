import { describe, expect, it } from 'vitest';
import { blankFuneralPlan, parsePremiums } from './funeralSchema';
import { blankPublishVersionForm, publishVersionFormSchema, toApiRequest } from './publishVersionSchema';

/** Plan B: main member and children only, priced to 70 (parents and extended family switched off). */
const PREMIUMS = 'plan,role,ageFrom,ageTo,yearlyPremium\nB,MAIN_MEMBER,18,70,60000\nB,CHILD,0,24,6000';

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
    funeralPlans: [{ ...blankFuneralPlan(), planCode: 'B', name: 'Familia B', benefits: ['2000000', '', '1000000', '', ''] }],
    funeralPremiumsText: PREMIUMS,
    funeralRoles: form.funeralRoles.map((r) =>
      r.role === 'SPOUSE' || r.role === 'PARENT' || r.role === 'EXTENDED' ? { ...r, allowed: false } : r),
    funeralMaxPricedAge: '70',
    funeralPayee: 'MAIN_MEMBER',
    funeralOnMainMemberDeath: 'POLICY_ENDS',
  };
};

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
    expect(messages({ ...valid(), funeralPremiumsText: 'B,MAIN_MEMBER,18,70,36000\nB,CHILD,0,24,0' })).toEqual([]);
    expect(messages({ ...valid(), funeralPremiumsText: 'B,MAIN_MEMBER,18,70,0\nB,CHILD,0,24,0' }))
      .toContain('Plan B: a main member’s premium must be above zero; nobody is covered free'.replace('’', "'"));
  });

  it('prices a version sold to group schemes only by its group rates', () => {
    const group = { ...valid(), funeralSoldAs: 'GROUP', funeralPremiumsText: '' };
    expect(messages(group)).toContain(
      'Plan B needs a group rate per member per month above zero: this version is sold to group schemes');
    const rated = { ...group, funeralPlans: [{ ...valid().funeralPlans[0], groupRate: '3000' }] };
    expect(messages(rated)).toEqual([]);
    expect(messages({ ...rated, funeralPremiumsText: PREMIUMS }))
      .toContain('A version sold to group schemes only is priced by its group rates; remove the premium table');
    const request = toApiRequest(publishVersionFormSchema('FUNERAL').parse(rated), 'FUNERAL');
    expect(request.funeral?.soldAs).toBe('GROUP');
    expect(request.funeral?.plans[0].groupMonthlyRate).toBe(3000);
    expect(request.funeral?.premiums).toEqual([]);
  });

  it('refuses a group rate on a version sold to individuals only', () => {
    const rated = { ...valid(), funeralPlans: [{ ...valid().funeralPlans[0], groupRate: '3000' }] };
    expect(messages(rated)).toContain('Plan B has a group rate, but this version is sold to individual policies only');
  });

  it('names the first age the premium table does not price', () => {
    // A student child is covered to 25, so the table must price 0-24.
    const text = 'B,MAIN_MEMBER,18,70,60000\nB,CHILD,0,20,6000';
    expect(messages({ ...valid(), funeralPremiumsText: text })).toContain('Plan B, CHILD: no premium for age 21');
  });

  it('refuses overlapping bands', () => {
    const text = `${PREMIUMS}\nB,CHILD,5,10,100`;
    expect(messages({ ...valid(), funeralPremiumsText: text })).toContain('Plan B, CHILD: ages 0-24 and 5-10 overlap');
  });

  it('refuses a premium line that does not parse, by number', () => {
    expect(messages({ ...valid(), funeralPremiumsText: 'B,MAIN_MEMBER,18,70,60000\nB,DOG,0,5,1' }))
      .toContain('Line 2 is not plan,role,ageFrom,ageTo,yearlyPremium: "B,DOG,0,5,1"');
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

describe('parsePremiums', () => {
  it('skips a header and blank lines, and reads tabs as well as commas', () => {
    expect(parsePremiums('plan,role,from,to,premium\n\nB\tCHILD\t0\t24\t6000').rows)
      .toEqual([{ planCode: 'B', role: 'CHILD', ageFrom: 0, ageTo: 24, yearlyPremium: 6000 }]);
  });
});
