import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import { LoanRepaidField } from './LoanRepaidField';

vi.mock('@/api/policies');

beforeEach(() => vi.clearAllMocks());

/** 2026-11-07, decision E7: a claim, maturity or surrender repays the policy's loans first, and the screen says so. */
describe('LoanRepaidField', () => {
  it('says what the payout repaid of the loan', async () => {
    vi.mocked(policiesApi.getLoanRepaidFromPayout).mockResolvedValue({ repaid: { amount: '300000.00', currencyCode: 'TZS' } });
    render(<dl><LoanRepaidField payout="CLAIM" payoutRef="c-1" /></dl>);

    expect(await screen.findByText('Loan repaid first')).toBeInTheDocument();
    expect(screen.getByText('TZS 300,000.00')).toBeInTheDocument();
    expect(screen.getByText(/Taken off this claim to repay the policy's loan/)).toBeInTheDocument();
    expect(policiesApi.getLoanRepaidFromPayout).toHaveBeenCalledWith('CLAIM', 'c-1');
  });

  it('shows nothing when the payout repaid no loan', async () => {
    vi.mocked(policiesApi.getLoanRepaidFromPayout).mockResolvedValue({ repaid: { amount: '0.00', currencyCode: 'TZS' } });
    const { container } = render(<dl><LoanRepaidField payout="MATURITY" payoutRef="i-1" /></dl>);
    await vi.waitFor(() => expect(policiesApi.getLoanRepaidFromPayout).toHaveBeenCalled());
    expect(container).toHaveTextContent('');
    expect(screen.queryByText('Loan repaid first')).not.toBeInTheDocument();
  });
});
