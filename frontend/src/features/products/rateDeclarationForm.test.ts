import { describe, expect, it } from 'vitest';
import type { TokenIdentity } from '@/auth/claims';
import type { VersionValueTermsView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { rateDeclarationSchema, showsRates } from './rateDeclarationForm';

const messages = (r: { success: boolean; error?: { issues: { message: string }[] } }) =>
  r.success ? [] : r.error!.issues.map((i) => i.message);

describe('rateDeclarationSchema', () => {
  it('accepts up to four decimals, the column’s precision', () => {
    expect(rateDeclarationSchema.safeParse({ ratePercent: '6.5', effectiveFrom: '2026-11-01' }).success).toBe(true);
    expect(rateDeclarationSchema.safeParse({ ratePercent: '6.1234', effectiveFrom: '2026-11-01' }).success).toBe(true);
  });

  it('refuses five decimals and a rate over 100, in the server’s words', () => {
    expect(rateDeclarationSchema.safeParse({ ratePercent: '6.12345', effectiveFrom: '2026-11-01' }).success).toBe(false);
    expect(messages(rateDeclarationSchema.safeParse({ ratePercent: '101', effectiveFrom: '2026-11-01' }))).toContain(
      'A declared rate must be between 0% and 100%',
    );
  });

  it('needs an effective date', () => {
    expect(messages(rateDeclarationSchema.safeParse({ ratePercent: '5', effectiveFrom: '' }))).toContain(
      'When does it take effect?',
    );
  });
});

describe('showsRates', () => {
  const finance = { realmRoles: ['FINANCE_OFFICER'] } as unknown as TokenIdentity;
  const plain = { bonus: null, cashValue: null, payouts: null, account: null, depositRates: [] } as VersionValueTermsView;
  const account = (band: number | null) => ({ ...plain, account: { guaranteedRatePercent: 3, minimumBalance: 0,
    minRatePercent: band, maxRatePercent: band, charges: [], usesAccountCharges: false } }) as VersionValueTermsView;
  it('shows only where a version is a savings account, the one kind a declaration credits', () => {
    grantAccess('products.view', 'ledger.view');
    expect(showsRates([plain], finance)).toBe(false);
    expect(showsRates([account(5)], finance)).toBe(false);   // a term account earns its locked rate
    expect(showsRates([{ ...account(null), depositRates: [{ minAmount: 1, termMonths: 12, ratePercent: 9 }] }], finance)).toBe(false);
    expect(showsRates([plain, account(null)], finance)).toBe(true);
  });
});
