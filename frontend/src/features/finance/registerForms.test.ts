import { describe, expect, it } from 'vitest';
import {
  ELECTION_VALUES,
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
    expect(Object.keys(ELECTION_VALUES)).toHaveLength(10);
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
