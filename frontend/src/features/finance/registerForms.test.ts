import { describe, expect, it } from 'vitest';
import {
  ELECTION_VALUES,
  permits,
  approvalSchema,
  electionSchema,
  rejectionSchema,
  reopenSchema,
} from './registerForms';

const TODAY = '2026-10-05';

function errorsOf(result: { success: boolean; error?: { issues: { path: PropertyKey[]; message: string }[] } }) {
  return Object.fromEntries((result.error?.issues ?? []).map((i) => [i.path.join('.'), i.message]));
}

describe('electionSchema', () => {
  const valid = { key: 'OCI_OPTION', scope: '*', value: 'ON', effectiveFrom: TODAY, rationale: '' };

  it('accepts a permitted value from today', () => {
    expect(electionSchema(TODAY).safeParse(valid).success).toBe(true);
  });

  it("refuses a value the key does not permit, in the server's words", () => {
    const r = electionSchema(TODAY).safeParse({ ...valid, value: 'MAYBE' });
    expect(errorsOf(r).value).toBe('MAYBE is not a permitted value for OCI_OPTION');
  });

  it('refuses a past effective date, in the server\'s words', () => {
    const r = electionSchema(TODAY).safeParse({ ...valid, effectiveFrom: '2026-10-04' });
    expect(errorsOf(r).effectiveFrom).toBe(
      'An election applies from today or later; a past change is a restatement, made through journals',
    );
  });

  it('needs an effective date and a value', () => {
    const r = errorsOf(electionSchema(TODAY).safeParse({ ...valid, effectiveFrom: '', value: '' }));
    expect(r.effectiveFrom).toBe('An election has an effective date');
    expect(r.value).toBe('Choose the value this election takes');
  });

  it('takes a comma-separated model list for MODEL_OVERRIDE_ALLOWED', () => {
    const base = { ...valid, key: 'MODEL_OVERRIDE_ALLOWED', scope: 'SAV' };
    expect(electionSchema(TODAY).safeParse({ ...base, value: 'GMM,PAA' }).success).toBe(true);
    expect(electionSchema(TODAY).safeParse({ ...base, value: 'NONE' }).success).toBe(true);
    expect(electionSchema(TODAY).safeParse({ ...base, value: 'GMM,XYZ' }).success).toBe(false);
  });

  it('lists every key the server knows', () => {
    expect(Object.keys(ELECTION_VALUES)).toHaveLength(12); // ElectionKey.java, the two I3b rates included
    expect(ELECTION_VALUES.MEASUREMENT_MODEL).toEqual(['GMM', 'VFA', 'PAA', 'IFRS9']);
  });
});

describe('the decision forms', () => {
  it('an approval names its sign-off reference', () => {
    expect(errorsOf(approvalSchema.safeParse({ signOffRef: '  ' })).signOffRef).toBe(
      'An approval names its sign-off reference',
    );
    expect(approvalSchema.safeParse({ signOffRef: 'AC-2026-14' }).success).toBe(true);
  });

  it('a rejection gives its reason', () => {
    expect(errorsOf(rejectionSchema.safeParse({ reason: '' })).reason).toBe('A rejection gives its reason');
  });

  it('reopening a locked period needs a reason', () => {
    expect(errorsOf(reopenSchema.safeParse({ reason: ' ' })).reason).toBe('Reopening a locked period needs a reason');
  });
});

describe('rate elections (IFRS 17 I3b)', () => {
  it('take NONE or a percentage above 0 and at most 100, as the server does', () => {
    expect(permits('COMMISSION_WITHHOLDING_RATE', 'NONE')).toBe(true);
    expect(permits('COMMISSION_WITHHOLDING_RATE', '5')).toBe(true);
    expect(permits('PREMIUM_LEVY_RATE', '2.5')).toBe(true);
    expect(permits('PREMIUM_LEVY_RATE', '0')).toBe(false);
    expect(permits('PREMIUM_LEVY_RATE', '101')).toBe(false);
    expect(permits('PREMIUM_LEVY_RATE', 'five')).toBe(false);
  });
});
