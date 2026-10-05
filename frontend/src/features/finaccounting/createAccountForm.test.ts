import { describe, expect, it } from 'vitest';
import { createAccountFormSchema, toApiRequest } from './createAccountForm';

const valid = () => ({ accountCode: '1900', parentCode: '', name: 'Petty Cash', description: '' });

describe('createAccountFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(createAccountFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts every valid leading block', () => {
    for (const block of ['1', '2', '3', '4', '5', '6', '7', '8', '9']) {
      expect(
        createAccountFormSchema.safeParse({ ...valid(), accountCode: `${block}234` }).success,
      ).toBe(true);
    }
  });

  it('rejects a leading 0, which is in no class', () => {
    expect(createAccountFormSchema.safeParse({ ...valid(), accountCode: '0900' }).success).toBe(
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

  it('accepts a blank parent -- that is how a block root is created', () => {
    expect(createAccountFormSchema.safeParse({ ...valid(), parentCode: '' }).success).toBe(true);
  });

  it('rejects a parent that is not a well-formed account code', () => {
    expect(createAccountFormSchema.safeParse({ ...valid(), parentCode: '0900' }).success).toBe(
      false,
    );
    expect(createAccountFormSchema.safeParse({ ...valid(), parentCode: '12' }).success).toBe(false);
  });
});

describe('toApiRequest', () => {
  /* An empty parentCode would fail the server's own ^[1-9]\d{3}$ pattern with a 400,
     rather than creating the block root the user asked for. */
  it('sends an omitted parent and description as absent, not as empty strings', () => {
    const request = toApiRequest({
      accountCode: '1260',
      parentCode: '',
      name: 'Sundry Receivables',
      description: '',
    });
    expect(request.parentCode).toBeUndefined();
    expect(request.description).toBeUndefined();
  });

  it('sends and trims a parent and description when given', () => {
    expect(
      toApiRequest({
        accountCode: '1260',
        parentCode: ' 1200 ',
        name: ' Sundry Receivables ',
        description: ' Odds and ends ',
      }),
    ).toEqual({
      accountCode: '1260',
      parentCode: '1200',
      name: 'Sundry Receivables',
      description: 'Odds and ends',
    });
  });
});
