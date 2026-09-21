import { describe, expect, it } from 'vitest';
import { createProductFormSchema } from './createProductForm';

const valid = () => ({
  productCode: 'NEW-TERM-01',
  productName: 'New Term Product',
  category: 'TERM_LIFE' as const,
  defaultCurrency: 'TZS',
});

describe('createProductFormSchema', () => {
  it('accepts a well-formed request', () => {
    expect(createProductFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts every category the spec declares', () => {
    for (const category of [
      'TERM_LIFE',
      'ENDOWMENT',
      'WHOLE_LIFE',
      'ANNUITY',
      'UNIT_LINKED',
      'GROUP_LIFE',
      'EDUCATION_SAVINGS',
      'CREDIT_LIFE',
    ] as const) {
      expect(createProductFormSchema.safeParse({ ...valid(), category }).success).toBe(true);
    }
  });

  it('rejects a blank product code', () => {
    expect(createProductFormSchema.safeParse({ ...valid(), productCode: '' }).success).toBe(false);
  });

  it('rejects a blank product name', () => {
    expect(createProductFormSchema.safeParse({ ...valid(), productName: '' }).success).toBe(false);
  });

  it('rejects a currency code that is not 3 uppercase letters', () => {
    expect(createProductFormSchema.safeParse({ ...valid(), defaultCurrency: 'tzs' }).success).toBe(
      false,
    );
  });
});
