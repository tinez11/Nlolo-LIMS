import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import type { LoanView, Money } from '@/api/types';
import { usePolicyStore } from '@/store/policyStore';
import { LoansPanel } from './LoansPanel';

vi.mock('@/api/policies');

const TZS = (amount: string): Money => ({ amount, currencyCode: 'TZS' });

const disbursedLoan: LoanView = {
  loanId: '8f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f',
  policyNumber: 'POL-000001',
  principalAmount: TZS('500000.00'),
  outstandingBalance: TZS('328770.00'),
  currentInterestRate: 12,
  status: 'DISBURSED',
};

/**
 * Panel-level coverage for the loans write surface.
 *
 * This exists because the e2e spec CANNOT reach it. A repayment control only appears on
 * a DISBURSED or REPAYING loan, no such loan can exist against real data (origination is
 * capped at cash value and nothing on the platform ever credits cash value), so
 * `staff-policy-loans.spec.ts`'s repayment-form test skips by design. A skipped test is
 * not coverage, and the alternative -- seeding a cash value just to make e2e green --
 * would be testing a fixture. So the form's real behavior is pinned here instead, where
 * the loan can simply be handed in.
 */
beforeEach(() => {
  vi.clearAllMocks();
  usePolicyStore.setState({ loans: {}, originatingLoan: {}, repayingLoan: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LoansPanel', () => {
  it('offers no repayment control on a loan that cannot take one', async () => {
    // DISBURSEMENT_REQUESTED is the status every loan on this platform actually rests at,
    // because nothing consumes LoanDisbursementRequested outside a test. Offering
    // "Record repayment" here would 409 on click -- PolicyLoanApiImpl.recordRepayment
    // requires DISBURSED or REPAYING.
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([
      { ...disbursedLoan, status: 'DISBURSEMENT_REQUESTED' },
    ]);

    render(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} />);

    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Record repayment' })).not.toBeInTheDocument();
  });

  it('records a repayment and refetches the list rather than patching it', async () => {
    const listLoans = vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    const recordRepayment = vi
      .spyOn(policiesApi, 'recordLoanRepayment')
      .mockResolvedValue({ ...disbursedLoan, status: 'SETTLED', outstandingBalance: TZS('0.00') });
    const user = userEvent.setup();

    render(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} />);
    await waitFor(() => expect(listLoans).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Record repayment' }));
    await user.type(screen.getByPlaceholderText('200000'), '328770');
    await user.type(screen.getByPlaceholderText('Receipt or transaction id'), 'PAY-REF-01');
    await user.click(screen.getByRole('button', { name: 'Record repayment' }));

    await waitFor(() =>
      expect(recordRepayment).toHaveBeenCalledWith(disbursedLoan.loanId, {
        amount: { amount: '328770', currencyCode: 'TZS' },
        paymentReference: 'PAY-REF-01',
      }),
    );
    // Refetched, not patched: a repayment that clears the balance also flips the loan to
    // SETTLED, and the row must show both together.
    await waitFor(() => expect(listLoans).toHaveBeenCalledTimes(2));
  });

  it('never sends the amount as a number, and rejects a third decimal before any request', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    const recordRepayment = vi.spyOn(policiesApi, 'recordLoanRepayment');
    const user = userEvent.setup();

    render(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} />);
    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Record repayment' }));
    await user.type(screen.getByPlaceholderText('200000'), '100.555');
    await user.type(screen.getByPlaceholderText('Receipt or transaction id'), 'R');
    await user.click(screen.getByRole('button', { name: 'Record repayment' }));

    // loan_transaction.amount is NUMERIC(19,2) -- a third decimal would be silently
    // rounded by Postgres, so it must never leave the browser.
    await waitFor(() =>
      expect(screen.getByText(/up to 2 decimal places/)).toBeInTheDocument(),
    );
    expect(recordRepayment).not.toHaveBeenCalled();
  });

  it('surfaces a rejection verbatim instead of rewording it', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    vi.spyOn(policiesApi, 'recordLoanRepayment').mockRejectedValue({
      title: 'Conflict',
      detail: 'Loan must be DISBURSED or REPAYING to accept a repayment (current: SETTLED)',
      status: 409,
    });
    const user = userEvent.setup();

    render(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} />);
    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Record repayment' }));
    await user.type(screen.getByPlaceholderText('200000'), '100');
    await user.type(screen.getByPlaceholderText('Receipt or transaction id'), 'R');
    await user.click(screen.getByRole('button', { name: 'Record repayment' }));

    // The server's own message names the actual status, which is more useful than
    // anything this form could invent -- and the form must stay open so it can be fixed.
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('current: SETTLED'));
    expect(screen.getByPlaceholderText('200000')).toBeInTheDocument();
  });

  it('disables origination while the policy has no cash value, and says why', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([]);

    render(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} />);

    await waitFor(() => expect(screen.getByText('No loans')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Take a loan' })).toBeDisabled();
    // Rendered text, not a `title`: a tooltip is unreachable by keyboard and silent to a screen
    // reader, so the reason has to be on the page for this assertion to mean anything.
    expect(screen.getByText(/cash value, which is 0\.00/)).toBeInTheDocument();
  });

  it('enables origination the moment cash value exists, with no code change', async () => {
    // The gate is on the LIVE value, not hardcoded to the platform's current
    // always-zero behavior -- so this is the assertion that proves the panel is ready
    // rather than permanently disabled.
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([]);
    const originate = vi.spyOn(policiesApi, 'originateLoan').mockResolvedValue(disbursedLoan);
    const user = userEvent.setup();

    render(<LoansPanel policyNumber="POL-000001" cashValue={TZS('1000000.00')} />);
    await waitFor(() => expect(screen.getByText('No loans')).toBeInTheDocument());

    const takeLoan = screen.getByRole('button', { name: 'Take a loan' });
    expect(takeLoan).toBeEnabled();
    await user.click(takeLoan);
    await user.type(screen.getByPlaceholderText('500000'), '500000');
    await user.type(screen.getByPlaceholderText('Mobile-money destination'), 'MPESA-0712345678');
    await user.click(screen.getByRole('button', { name: 'Take a loan' }));

    await waitFor(() =>
      expect(originate).toHaveBeenCalledWith('POL-000001', {
        requestedAmount: { amount: '500000', currencyCode: 'TZS' },
        payeeRef: 'MPESA-0712345678',
      }),
    );
  });
});
