import { describe, expect, it } from 'vitest';
import { toVestingInstruction, vestingFormSchema, type VestingFormContext } from './vestingForm';

const CTX: VestingFormContext = {
  today: '2036-03-01',
  target: '2040-06-15',
  earliest: '2035-06-15',
  latest: '2050-06-15',
  cap: '25',
  jointForms: ['JOINT-50'],
};

const valid = { vestingDate: '2040-06-15', formCode: 'LIFE-0G', frequency: 'MONTHLY', jointLifePartyId: '', lumpSumPercent: '25', contributions: '' };

const issues = (input: unknown, ctx = CTX) => {
  const r = vestingFormSchema(ctx).safeParse(input);
  return r.success ? [] : r.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`);
};

describe('a vesting instruction (VestingRules)', () => {
  it('accepts vesting on the target with a lump sum at the cap', () => {
    expect(issues(valid)).toEqual([]);
  });

  it('refuses a date in the past, before the window or after it', () => {
    expect(issues({ ...valid, vestingDate: '2036-02-28' })).toContain('vestingDate: A vesting date cannot be in the past');
    expect(issues({ ...valid, vestingDate: '2034-01-01' }, { ...CTX, today: '2033-01-01' })).toContain(
      'vestingDate: The earliest this pension can vest is 2035-06-15, at the minimum vesting age',
    );
    expect(issues({ ...valid, vestingDate: '2050-06-16', contributions: 'STOP' })).toContain(
      'vestingDate: The latest this pension can be deferred to is 2050-06-15, at the maximum vesting age',
    );
  });

  it('makes a deferral say what contributions do, and sends it only then', () => {
    expect(issues({ ...valid, vestingDate: '2042-06-15' })).toContain(
      'contributions: A deferral must say whether contributions continue to the new date or stop at 2040-06-15',
    );
    const parsed = vestingFormSchema(CTX).parse({ ...valid, contributions: 'STOP' });
    expect(toVestingInstruction(parsed, CTX).contributions).toBeNull();
  });

  it('keeps the lump sum within the cap', () => {
    expect(issues({ ...valid, lumpSumPercent: '26' })).toContain('lumpSumPercent: The lump sum can be from 0% to 25% of the balance');
    expect(issues({ ...valid, lumpSumPercent: '' })).toContain('lumpSumPercent: The lump sum can be from 0% to 25% of the balance');
  });

  it('names the joint life on a joint form', () => {
    expect(issues({ ...valid, formCode: 'JOINT-50' })).toContain('jointLifePartyId: Form JOINT-50 is joint-life: name the joint life');
  });
});
