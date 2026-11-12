import { describe, expect, it } from 'vitest';
import {
  blankFlatRow,
  blankRateRow,
  createCommissionPlanFormSchema,
  fractionToPercent,
  percentToFraction,
  toApiRequest,
} from './createCommissionPlanForm';

const validRateRule = () => ({ mode: 'rate' as const, tierType: 'FIRST_YEAR' as const, rate: '10' });
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

  /** Finance finding 187 (2026-11-10): "5" meant 5% and was saved as 500%. Above 100% is refused. */
  it('refuses more than 100% of the premium', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({ ...valid(), rules: [{ ...validRateRule(), rate: '500' }] }).success,
    ).toBe(false);
    expect(
      createCommissionPlanFormSchema.safeParse({ ...valid(), rules: [{ ...validRateRule(), rate: '100' }] }).success,
    ).toBe(true);
  });

  it('rejects a zero rate', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [{ ...validRateRule(), rate: '0' }],
      }).success,
    ).toBe(false);
  });

  it('rejects a percent with more than 2 decimal places', () => {
    expect(
      createCommissionPlanFormSchema.safeParse({
        ...valid(),
        rules: [{ ...validRateRule(), rate: '12.345' }],
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

describe('percentToFraction', () => {
  it('turns a typed percent into the stored fraction exactly', () => {
    expect(percentToFraction('5')).toBe('0.0500');
    expect(percentToFraction('12.5')).toBe('0.1250');
    expect(percentToFraction('0.25')).toBe('0.0025');
    expect(percentToFraction('100')).toBe('1.0000');
  });

  it('reads a stored fraction back as a percent -- the 500% plan shows as 500', () => {
    expect(fractionToPercent('0.0500')).toBe('5');
    expect(fractionToPercent('0.1250')).toBe('12.5');
    expect(fractionToPercent('5.0000')).toBe('500');
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
