import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CoverAmount } from './CoverAmount';
import { isDepositAccount } from './policyValue';

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

/** 2026-11-08, the user: a savings account's "sum assured" is its opening deposit, and no screen may call it cover. */
describe('a deposit account has no sum assured', () => {
  it('knows savings accounts and fixed deposits, and nothing else', () => {
    expect(isDepositAccount({ portfolioCode: 'SAV' })).toBe(true);
    expect(isDepositAccount({ portfolioCode: 'DEP' })).toBe(true);
    expect(isDepositAccount({ portfolioCode: 'END' })).toBe(false);
    expect(isDepositAccount({ portfolioCode: 'PEN' })).toBe(false);
    expect(isDepositAccount({ portfolioCode: null })).toBe(false);
  });

  it('shows a savings account’s cover as none, and a term policy’s as its sum assured', () => {
    const { rerender } = render(<CoverAmount policy={{ portfolioCode: 'SAV', sumAssured: TZS('20000.00') }} />);
    expect(screen.queryByText(/20,000/)).not.toBeInTheDocument();
    expect(screen.getByText('No cover above the account value')).toBeInTheDocument();

    rerender(<CoverAmount policy={{ portfolioCode: 'TERM', sumAssured: TZS('2000000.00') }} />);
    expect(screen.getByText('TZS 2,000,000.00')).toBeInTheDocument();
  });
});
