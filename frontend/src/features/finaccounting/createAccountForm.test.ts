import { describe, expect, it } from 'vitest';
import { createAccountFormSchema } from './createAccountForm';

const valid = () => ({ accountCode: '1900', name: 'Petty Cash' });

describe('createAccountFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(createAccountFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts every valid leading block', () => {
    for (const block of ['1', '2', '3', '4', '5']) {
      expect(
        createAccountFormSchema.safeParse({ ...valid(), accountCode: `${block}234` }).success,
      ).toBe(true);
    }
  });

  it('rejects a leading block outside 1-5', () => {
    expect(createAccountFormSchema.safeParse({ ...valid(), accountCode: '9000' }).success).toBe(
      false,
    );
  });

  it('rejects an account code that is not 4 digits', () => {
    expect(createAccountFormSchema.safeParse({ ...valid(), accountCode: '100' }).success).toBe(
      false,
    );
    expect(createAccountFormSchema.safeParse({ ...valid(), accountCode: '10000' }).success).toBe(
      false,
    );
  });

  it('rejects a blank name', () => {
    expect(createAccountFormSchema.safeParse({ ...valid(), name: '' }).success).toBe(false);
    expect(createAccountFormSchema.safeParse({ ...valid(), name: '   ' }).success).toBe(false);
  });

  it('rejects a name over 200 characters', () => {
    expect(
      createAccountFormSchema.safeParse({ ...valid(), name: 'a'.repeat(201) }).success,
    ).toBe(false);
  });

  it('accepts a name at exactly 200 characters', () => {
    expect(
      createAccountFormSchema.safeParse({ ...valid(), name: 'a'.repeat(200) }).success,
    ).toBe(true);
  });
});
