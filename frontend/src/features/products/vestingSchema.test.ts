import { describe, expect, it } from 'vitest';
import { blankAnnuityForm } from './annuitySchema';
import { blankPublishVersionForm, publishVersionFormSchema, toApiRequest } from './publishVersionSchema';

/**
 * A deferred annuity (product step 5 D2): saves from 18 to 55, vests 55-56, unisex single life, a
 * zero-charge account. The smallest deferred version that publishes.
 */
const valid = () => ({
  ...blankPublishVersionForm(),
  effectiveDate: '2026-10-01',
  tiraReference: 'TIRA/PEN/1',
  tiraApprovalDate: '2026-09-01',
  ratingTable: [
    { factorType: 'AGE' as const, band: '', multiplier: 1, ageFrom: '0', ageTo: '120', sumAssuredFrom: '', sumAssuredTo: '' },
    { factorType: 'SUM_ASSURED_BAND' as const, band: 'ALL', multiplier: 1, ageFrom: '', ageTo: '', sumAssuredFrom: '', sumAssuredTo: '' },
  ],
  benefitSchedule: [{ benefitType: 'DEATH' as const, calculationMethod: 'SUM_ASSURED' as const, percent: '', flatAmount: '' }],
  freeLookDays: '15',
  minEntryAge: '18',
  maxEntryAge: '55',
  valueBasis: 'ACCOUNT',
  guaranteedRatePercent: '4',
  minimumBalance: '0',
  accountCharges: [
    { fromPolicyYear: '1', toPolicyYear: '', contributionAllocationPercent: '0', transferAllocationPercent: '0', monthlyPolicyFee: '0' },
  ],
  annuityTiming: 'ARREARS',
  annuityBasisReference: 'a(90) 4%',
  annuityBasisDate: '2026-09-01',
  // Rates over the vesting window only: the entry ages need none.
  annuityForms: [{ ...blankAnnuityForm(), formCode: 'L10', guaranteeYears: '10', ratesText: '55, 60\n56, 62' }],
  annuityKind: 'DEFERRED',
  vestingMinAge: '55',
  vestingMaxAge: '56',
  vestingDefaultForm: 'L10',
  vestingDefaultFrequency: 'ANNUAL',
  vestingCap: '25',
  vestingLocked: 'YES',
});

/** Each issue as `path: message`, so a message is proven to land on a field the form renders. */
const issues = (input: unknown) => {
  const r = publishVersionFormSchema('ANNUITY').safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`);
};

describe('a deferred annuity on the publish form (VestingPlanValidator)', () => {
  it('accepts a complete deferred version, its grid covering the vesting window and not the entry ages', () => {
    expect(issues(valid())).toEqual([]);
  });

  it('checks the grid over the vesting window', () => {
    expect(issues({ ...valid(), vestingMaxAge: '57' })).toContain('annuityForms.0.ratesText: Form L10 has no rate for age 57');
  });

  it('refuses a malformed window on the minimum vesting age', () => {
    const message = 'vestingMinAge: The vesting window runs from a minimum to a maximum vesting age, each between 0 and 120';
    expect(issues({ ...valid(), vestingMinAge: '57' })).toContain(message);
    expect(issues({ ...valid(), vestingMinAge: '' })).toContain(message);
    expect(issues({ ...valid(), vestingMaxAge: '121' })).toContain(message);
  });

  it('needs a default form on the version, and not a joint one', () => {
    expect(issues({ ...valid(), vestingDefaultForm: 'L5' })).toContain(
      "vestingDefaultForm: The default form L5 is not one of this version's forms",
    );
    const joint = { ...blankAnnuityForm(), formCode: 'J50', joint: true, survivorPercent: '50', ratesText: '55 0 0 50\n56 0 0 51' };
    expect(issues({ ...valid(), annuityForms: [valid().annuityForms[0]!, joint], annuityJointDiffMin: '0',
      annuityJointDiffMax: '0', vestingDefaultForm: 'J50' })).toContain(
      'vestingDefaultForm: The default form J50 is joint-life; a pension that vests with no instruction vests on one life',
    );
  });

  it('needs a default frequency the version offers', () => {
    expect(issues({ ...valid(), vestingDefaultFrequency: 'MONTHLY' })).toContain(
      "vestingDefaultFrequency: The default frequency MONTHLY is not one of this version's frequencies",
    );
  });

  it('needs a lump-sum cap from 0 to 100', () => {
    const message = 'vestingCap: The lump-sum cap must be between 0% and 100% of the balance';
    expect(issues({ ...valid(), vestingCap: '' })).toContain(message);
    expect(issues({ ...valid(), vestingCap: '101' })).toContain(message);
  });

  it('has no default for the lock', () => {
    expect(issues({ ...valid(), vestingLocked: '' })).toContain(
      'vestingLocked: A deferred annuity must state whether it can be surrendered before it vests',
    );
  });

  it('needs the maximum entry age below the maximum vesting age', () => {
    expect(issues({ ...valid(), maxEntryAge: '56' })).toContain(
      'vestingMaxAge: The maximum entry age must be below the maximum vesting age, so every customer can reach a vesting age',
    );
  });

  it('refuses an account on an immediate annuity, as before', () => {
    expect(issues({ ...valid(), annuityKind: 'IMMEDIATE' })).toContain(
      'valueBasis: A ANNUITY product cannot use an account value basis',
    );
  });

  it('sends the vesting block and the account only on a deferred annuity', () => {
    const request = toApiRequest(publishVersionFormSchema('ANNUITY').parse(valid()), 'ANNUITY');
    expect(request.annuity?.vesting).toEqual({
      minVestingAge: 55,
      maxVestingAge: 56,
      defaultFormCode: 'L10',
      defaultFrequency: 'ANNUAL',
      maxCommutationPercent: 25,
      surrenderBeforeVesting: false,
    });
    expect(request.accumulation).toBeDefined();
  });
});
