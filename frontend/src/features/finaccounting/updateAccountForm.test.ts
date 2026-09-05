import { describe, expect, it } from 'vitest';
import { toApiRequest, updateAccountFormSchema } from './updateAccountForm';

const valid = () => ({ name: 'Petty Cash', description: '' });

describe('updateAccountFormSchema', () => {
  it('accepts a well-formed name', () => {
    expect(updateAccountFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('rejects a blank name', () => {
    expect(updateAccountFormSchema.safeParse({ ...valid(), name: '' }).success).toBe(false);
    expect(updateAccountFormSchema.safeParse({ ...valid(), name: '   ' }).success).toBe(false);
  });

  it('rejects a name over 200 characters', () => {
    expect(updateAccountFormSchema.safeParse({ ...valid(), name: 'a'.repeat(201) }).success).toBe(
      false,
    );
  });

  it('accepts a name at exactly 200 characters', () => {
    expect(updateAccountFormSchema.safeParse({ ...valid(), name: 'a'.repeat(200) }).success).toBe(
      true,
    );
  });

  it('rejects a description over 2000 characters', () => {
    expect(
      updateAccountFormSchema.safeParse({ ...valid(), description: 'a'.repeat(2001) }).success,
    ).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('sends a cleared description as absent, not as an empty string', () => {
    expect(toApiRequest({ name: 'Petty Cash', description: '' }).description).toBeUndefined();
    expect(toApiRequest({ name: 'Petty Cash', description: '   ' }).description).toBeUndefined();
  });

  it('trims what it does send', () => {
    expect(toApiRequest({ name: '  Petty Cash  ', description: '  Small change  ' })).toEqual({
      name: 'Petty Cash',
      description: 'Small change',
    });
  });
});
