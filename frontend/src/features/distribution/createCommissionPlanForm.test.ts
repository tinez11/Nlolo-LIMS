import { describe, expect, it } from 'vitest';
import {
  blankFlatRow,
  blankRateRow,
  createCommissionPlanFormSchema,
  toApiRequest,
} from './createCommissionPlanForm';

const validRateRule = () => ({ mode: 'rate' as const, tierType: 'FIRST_YEAR' as const, rate: '0.1000' });
const validFlatRule = () => ({
  mode: 'flat' as const,
  tierType: 'RENEWAL' as const,
  flatAmount: '5000.00',
  flatCurrency: 'TZS',
});

const valid = () => ({
  productId: '9924cbb2-8adb-4be4-b0a6-2835e4ad7373',
  rules: [validRateRule()],
});

describe('createCommissionPlanFormSchema', () => {
  it('accepts a well-formed request with a single rate rule', () => {
    expect(createCommissionPlanFormSchema.safeParse(valid()).success).toBe(true);
  });

  it('accepts a well-formed request with a single flat rule', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({ ...valid(), rules: [validFlatRule()] }).success,
    ).toBe(true);
  });

  it('accepts multiple rules of different tiers and modes', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [validRateRule(), validFlatRule()],
      }).success,
    ).toBe(true);
  });

  it('rejects no product selected', () => {
    expect(createCommissionPlanFormSchema.safeParse({ ...valid(), productId: '' }).success).toBe(
      false,
    );
  });

  it('rejects an empty rules array', () => {
    expect(createCommissionPlanFormSchema.safeParse({ ...valid(), rules: [] }).success).toBe(
      false,
    );
  });

  it('rejects a zero rate', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [{ ...validRateRule(), rate: '0' }],
      }).success,
    ).toBe(false);
  });

  it('rejects a rate with more than 4 decimal places', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [{ ...validRateRule(), rate: '0.12345' }],
      }).success,
    ).toBe(false);
  });

  it('rejects a zero flat amount', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [{ ...validFlatRule(), flatAmount: '0.00' }],
      }).success,
    ).toBe(false);
  });

  it('rejects a lowercase flat currency', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [{ ...validFlatRule(), flatCurrency: 'tzs' }],
      }).success,
    ).toBe(false);
  });
});

describe('toApiRequest', () => {
  it('sends a null flatAmount for a rate rule', () => {
    const request = toApiRequest(createCommissionPlanFormSchema.parse(valid()));
    expect(request.rules[0]).toEqual({ tierType: 'FIRST_YEAR', rate: '0.1000', flatAmount: null });
  });

  it('sends a null rate and a nested Money for a flat rule -- never both', () => {
    const request = toApiRequest(
      createCommissionPlanFormSchema.parse({ ...valid(), rules: [validFlatRule()] }),
    );
    expect(request.rules[0]).toEqual({
      tierType: 'RENEWAL',
      rate: null,
      flatAmount: { amount: '5000.00', currencyCode: 'TZS' },
    });
  });
});

describe('blank row factories', () => {
  it('blankRateRow starts on the rate branch', () => {
    expect(blankRateRow('OVERRIDE').mode).toBe('rate');
  });

  it('blankFlatRow starts on the flat branch', () => {
    expect(blankFlatRow('SUPERVISOR_OVERRIDE').mode).toBe('flat');
  });
});
