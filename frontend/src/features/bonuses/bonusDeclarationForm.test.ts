import { describe, expect, it } from 'vitest';
import type { TokenIdentity } from '@/auth/claims';
import { bonusDeclarationSchema, showsBonuses } from './bonusDeclarationForm';

const messages = (r: { success: boolean; error?: { issues: { message: string }[] } }) =>
  r.success ? [] : r.error!.issues.map((i) => i.message);

const valid = { valuationDate: '2026-12-31', reversionaryRatePercent: '3.5', terminalRatePercent: '40' };

describe('bonusDeclarationSchema', () => {
  it('accepts a declaration with up to four decimals', () => {
    expect(bonusDeclarationSchema.safeParse(valid).success).toBe(true);
    expect(bonusDeclarationSchema.safeParse({ ...valid, reversionaryRatePercent: '3.1234' }).success).toBe(true);
  });

  it('refuses a reversionary rate over 100 in the server’s words', () => {
    expect(messages(bonusDeclarationSchema.safeParse({ ...valid, reversionaryRatePercent: '101' }))).toContain(
      'A reversionary bonus rate must be between 0% and 100%',
    );
  });

  it('allows a terminal rate up to 1000 and refuses above it in the server’s words', () => {
    expect(bonusDeclarationSchema.safeParse({ ...valid, terminalRatePercent: '1000' }).success).toBe(true);
    expect(messages(bonusDeclarationSchema.safeParse({ ...valid, terminalRatePercent: '1001' }))).toContain(
      'A terminal bonus rate must be between 0% and 1000% of attached bonuses',
    );
  });

  it('needs a valuation date', () => {
    expect(messages(bonusDeclarationSchema.safeParse({ ...valid, valuationDate: '' }))).toContain('As at which valuation date?');
  });
});

describe('showsBonuses', () => {
  const finance = { realmRoles: ['FINANCE_OFFICER'] } as unknown as TokenIdentity;
  it('is false off the with-profits categories', () => {
    expect(showsBonuses('TERM_LIFE', finance)).toBe(false);
    expect(showsBonuses('EDUCATION_SAVINGS', finance)).toBe(false);
  });
});
