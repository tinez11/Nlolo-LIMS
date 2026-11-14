import { describe, expect, it } from 'vitest';
import { plainWords } from './plainWords';

const ID = '7f3e2a10-4b5c-4d6e-8f90-a1b2c3d4e5f6';
const ID2 = 'c0ffee00-1234-4abc-9def-001122334455';

/** Real refusals the server sends (2026-11-14 sweep), and what a person now reads. */
describe('plainWords', () => {
  it('names a thing by "this", not by its id', () => {
    expect(plainWords(`Claim ${ID} is SETTLED; reopen it before asking for documents`))
      .toBe('This claim is settled; reopen it before asking for documents');
    expect(plainWords(`The document is not on claim ${ID}`)).toBe('The document is not on this claim');
    expect(plainWords(`Free-look cancellation ${ID} is PENDING_REVIEW, not awaiting approval`))
      .toBe('This free-look cancellation is pending review, not awaiting approval');
    expect(plainWords(`Product version ${ID} belongs to product ${ID2}`))
      .toBe('This product version belongs to this product');
    expect(plainWords(`Promoting member ${ID} would leave the scheme without a main member`))
      .toBe('Promoting this member would leave the scheme without a main member');
  });

  it('says a state in words', () => {
    expect(plainWords(`Loan ${ID} is not waiting for its payout to be approved (current: PAYOUT_REFUSED)`))
      .toBe('This loan is not waiting for its payout to be approved: it is payout refused');
    expect(plainWords('Only a validated run can be decided; this one is POSTED'))
      .toBe('Only a validated run can be decided; this one is posted');
    expect(plainWords(`Order ${ID} is AWAITING_PRICE; only a waiting order is cancelled`))
      .toBe('This order is awaiting price; only a waiting order is cancelled');
  });

  it('leaves acronyms, money, policy numbers and references alone', () => {
    expect(plainWords('The loan owes TZS 20,000.00 now. Ask for that or less.'))
      .toBe('The loan owes TZS 20,000.00 now. Ask for that or less.');
    expect(plainWords('POL-5705F7FB needs KYC before IFRS 17 cohort 2026; client reference BLIC001 is taken'))
      .toBe('POL-5705F7FB needs KYC before IFRS 17 cohort 2026; client reference BLIC001 is taken');
    expect(plainWords('Ledger guard: LEDGER_PERIOD_LOCKED_2026')).toBe('Ledger guard: LEDGER_PERIOD_LOCKED_2026');
  });

  it('drops an id it cannot name', () => {
    expect(plainWords(`Nothing found for ${ID}.`)).toBe('Nothing found for.');
    expect(plainWords(null)).toBeNull();
  });
});
