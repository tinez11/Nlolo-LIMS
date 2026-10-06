import { describe, expect, it } from 'vitest';
import { createTreatyFormSchema, toApiRequest } from './createTreatyForm';

const validQuotaShare = () => ({
  treatyType: 'QUOTA_SHARE' as const,
  reinsurerName: 'Africa Re',
  retentionLimitAmount: '5000000.00',
  retentionLimitCurrency: 'TZS',
  effectiveFrom: '2026-01-01',
  effectiveTo: '',
  commissionPercent: '20.00',
  cessionPercent: '25.00',
});

const validSurplus = () => ({
  treatyType: 'SURPLUS' as const,
  reinsurerName: 'Africa Re',
  retentionLimitAmount: '5000000.00',
  retentionLimitCurrency: 'TZS',
  effectiveFrom: '2026-01-01',
  effectiveTo: '',
  commissionPercent: '0',
});

describe('createTreatyFormSchema -- QUOTA_SHARE branch', () => {
  it('accepts a well-formed request', () => {
    expect(createTreatyFormSchema.safeParse(validQuotaShare()).success).toBe(true);
  });

  it('rejects a missing cession percent', () => {
    const { cessionPercent: _omit, ...rest } = validQuotaShare();
    expect(createTreatyFormSchema.safeParse(rest).success).toBe(false);
  });

  it('rejects a cession percent over 100', () => {
    expect(
      createTreatyFormSchema.safeParse({ ...validQuotaShare(), cessionPercent: '150.00' }).success,
    ).toBe(false);
  });

  it('rejects a zero cession percent', () => {
    expect(
      createTreatyFormSchema.safeParse({ ...validQuotaShare(), cessionPercent: '0' }).success,
    ).toBe(false);
  });
});

describe('createTreatyFormSchema -- SURPLUS/XOL branches', () => {
  it('accepts a well-formed SURPLUS request with no cessionPercent field at all', () => {
    expect(createTreatyFormSchema.safeParse(validSurplus()).success).toBe(true);
  });

  it('accepts a well-formed XOL request', () => {
    expect(
      createTreatyFormSchema.safeParse({ ...validSurplus(), treatyType: 'XOL', xolAnnualPremium: '' }).success,
    ).toBe(true);
  });
});

describe('createTreatyFormSchema -- shared rules', () => {
  it('rejects a blank reinsurer name', () => {
    expect(createTreatyFormSchema.safeParse({ ...validSurplus(), reinsurerName: '' }).success).toBe(
      false,
    );
  });

  it('rejects a reinsurer name over 200 characters', () => {
    expect(
      createTreatyFormSchema.safeParse({ ...validSurplus(), reinsurerName: 'X'.repeat(201) })
        .success,
    ).toBe(false);
  });

  it('rejects a malformed retention limit amount', () => {
    expect(
      createTreatyFormSchema.safeParse({ ...validSurplus(), retentionLimitAmount: 'lots' }).success,
    ).toBe(false);
  });

  it('accepts a zero retention limit -- the backend allows zero or positive', () => {
    expect(
      createTreatyFormSchema.safeParse({ ...validSurplus(), retentionLimitAmount: '0.00' }).success,
    ).toBe(true);
  });

  it('rejects effectiveTo before effectiveFrom', () => {
    expect(
      createTreatyFormSchema.safeParse({
        ...validSurplus(),
        effectiveFrom: '2026-06-01',
        effectiveTo: '2026-01-01',
      }).success,
    ).toBe(false);
  });

  it('accepts a blank effectiveTo -- no known end date', () => {
    expect(createTreatyFormSchema.safeParse({ ...validSurplus(), effectiveTo: '' }).success).toBe(
      true,
    );
  });
});

describe('createTreatyFormSchema -- IFRS 17 I3c terms', () => {
  it('rejects a blank commission percent -- 0 is an answer, silence is not', () => {
    expect(createTreatyFormSchema.safeParse({ ...validSurplus(), commissionPercent: '' }).success).toBe(false);
  });

  it('accepts a zero commission percent', () => {
    expect(createTreatyFormSchema.safeParse({ ...validSurplus(), commissionPercent: '0' }).success).toBe(true);
  });

  it('rejects a commission percent over 100', () => {
    expect(createTreatyFormSchema.safeParse({ ...validSurplus(), commissionPercent: '100.01' }).success).toBe(
      false,
    );
  });

  it('rejects a zero XOL annual premium but accepts a blank one', () => {
    const xol = { ...validSurplus(), treatyType: 'XOL' as const };
    expect(createTreatyFormSchema.safeParse({ ...xol, xolAnnualPremium: '0' }).success).toBe(false);
    expect(createTreatyFormSchema.safeParse({ ...xol, xolAnnualPremium: '' }).success).toBe(true);
  });
});

describe('toApiRequest', () => {
  it('sends the commission percent and an XOL annual premium', () => {
    const request = toApiRequest(
      createTreatyFormSchema.parse({
        ...validSurplus(),
        treatyType: 'XOL',
        commissionPercent: '12.50',
        xolAnnualPremium: '120000.00',
      }),
    );
    expect(request.commissionPercent).toBe('12.50');
    expect(request.xolAnnualPremium).toBe('120000.00');
  });

  it('sends a null XOL annual premium for any other treaty', () => {
    expect(toApiRequest(createTreatyFormSchema.parse(validQuotaShare())).xolAnnualPremium).toBeNull();
  });

  it('nests retentionLimit as a Money object and includes cessionPercent for QUOTA_SHARE', () => {
    const request = toApiRequest(createTreatyFormSchema.parse(validQuotaShare()));
    expect(request.retentionLimit).toEqual({ amount: '5000000.00', currencyCode: 'TZS' });
    expect(request.cessionPercent).toBe('25.00');
  });

  it('sends a null cessionPercent for SURPLUS/XOL', () => {
    const request = toApiRequest(createTreatyFormSchema.parse(validSurplus()));
    expect(request.cessionPercent).toBeNull();
  });

  it('sends a null effectiveTo when left blank', () => {
    const request = toApiRequest(createTreatyFormSchema.parse(validSurplus()));
    expect(request.effectiveTo).toBeNull();
  });
});
