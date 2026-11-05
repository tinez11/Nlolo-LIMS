import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import type { LoanView, Money } from '@/api/types';
import { grantAccess } from '@/test/access';
import { usePolicyStore } from '@/store/policyStore';
import { LoansPanel } from './LoansPanel';

vi.mock('@/api/policies');
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-staff-one' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'staff-one' }),
}));

const TZS = (amount: string): Money => ({ amount, currencyCode: 'TZS' });

const disbursedLoan: LoanView = {
  loanId: '8f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f',
  policyNumber: 'POL-000001',
  principalAmount: TZS('500000.00'),
  outstandingBalance: TZS('328770.00'),
  currentInterestRate: 12,
  status: 'DISBURSED',
};

/** The deposit link needs a router; the panel never navigates on its own. */
function renderPanel(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

/**
 * Panel-level coverage for the loans write surface.
 *
 * Since money movement step 1 (2026-11-04) a repayment is money in like a premium: a deposit For: Loan recorded on
 * the receipt page and confirmed by a second person, or a mobile-money request. Nothing here books a repayment. The
 * e2e spec can only reach these controls on a DISBURSED or REPAYING loan, which needs seeded cash value, so the
 * panel's real behaviour is pinned here, where the loan can simply be handed in.
 */
beforeEach(() => {
  vi.clearAllMocks();
  usePolicyStore.setState({ loans: {}, originatingLoan: {}, requestingLoanPayment: {}, decidingLoanPayout: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LoansPanel', () => {
  it('offers no repayment control on a loan that cannot take one', async () => {
    // DISBURSEMENT_REQUESTED: the server refuses a repayment on it with a 409, so nothing is offered.
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([
      { ...disbursedLoan, status: 'DISBURSEMENT_REQUESTED' },
    ]);

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange canRecordDeposit canRequestPayment />);

    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: 'Record deposit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request mobile-money payment' })).not.toBeInTheDocument();
  });

  it('offers a deposit and a mobile-money request on a repayable loan, never a typed repayment', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange canRecordDeposit canRequestPayment />);

    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Record repayment' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Record deposit' })).toHaveAttribute('href',
      `/staff/receipts/new?for=LOAN&loan=${disbursedLoan.loanId}&policy=POL-000001&from=policy`);
    expect(screen.getByRole('button', { name: 'Request mobile-money payment' })).toBeInTheDocument();
  });

  it('shows neither to someone without the collections permissions', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange
      canRecordDeposit={false} canRequestPayment={false} />);

    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: 'Record deposit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request mobile-money payment' })).not.toBeInTheDocument();
  });

  it('asks for what the loan owes, as the server stated it, under one attempt key', async () => {
    const listLoans = vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    const request = vi.spyOn(policiesApi, 'requestLoanPayment').mockResolvedValue(undefined);
    const user = userEvent.setup();

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange canRecordDeposit canRequestPayment />);
    await waitFor(() => expect(listLoans).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Request mobile-money payment' }));
    expect(screen.getByLabelText('Amount (TZS)')).toHaveValue('328770.00');
    await user.type(screen.getByPlaceholderText('The mobile-money number to ask'), '255754000001');
    await user.click(screen.getByRole('button', { name: 'Request payment' }));

    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(disbursedLoan.loanId, {
        payerRef: '255754000001',
        amount: { amount: '328770.00', currencyCode: 'TZS' },
      }, expect.anything()),
    );
    // Refetched: a fast confirmation already reflected server-side must show.
    await waitFor(() => expect(listLoans).toHaveBeenCalledTimes(2));
  });

  it('never sends the amount as a number, and rejects a third decimal before any request', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    const request = vi.spyOn(policiesApi, 'requestLoanPayment');
    const user = userEvent.setup();

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange canRecordDeposit canRequestPayment />);
    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Request mobile-money payment' }));
    await user.clear(screen.getByLabelText('Amount (TZS)'));
    await user.type(screen.getByLabelText('Amount (TZS)'), '100.555');
    await user.type(screen.getByPlaceholderText('The mobile-money number to ask'), '255754000001');
    await user.click(screen.getByRole('button', { name: 'Request payment' }));

    await waitFor(() => expect(screen.getByText(/up to 2 decimal places/)).toBeInTheDocument());
    expect(request).not.toHaveBeenCalled();
  });

  it('surfaces a refusal verbatim and keeps the form open', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    vi.spyOn(policiesApi, 'requestLoanPayment').mockRejectedValue({
      title: 'Conflict',
      detail: 'Ask for between 0.01 and 328770.00 TZS, what the loan owes now',
      status: 409,
    });
    const user = userEvent.setup();

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange canRecordDeposit canRequestPayment />);
    await waitFor(() => expect(screen.getByText(/of .*principal/)).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Request mobile-money payment' }));
    await user.type(screen.getByPlaceholderText('The mobile-money number to ask'), '255754000001');
    await user.click(screen.getByRole('button', { name: 'Request payment' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('what the loan owes now'));
    expect(screen.getByLabelText('Amount (TZS)')).toBeInTheDocument();
  });

  it('disables origination while the policy has no cash value, and says why', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([]);

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange canRecordDeposit canRequestPayment />);

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

    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('1000000.00')} canChange canRecordDeposit canRequestPayment />);
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

  it('shows an agent the loans but offers no loan and no repayment (2026-10-27)', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('1000000.00')} canChange={false}
      canRecordDeposit={false} canRequestPayment={false} />);
    await waitFor(() => expect(screen.getByText(/principal/)).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Take a loan' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Record deposit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request mobile-money payment' })).not.toBeInTheDocument();
  });

  // Money movement step 4: a loan's payout waits for a second person.
  const waiting: LoanView = { ...disbursedLoan, status: 'AWAITING_PAYOUT_APPROVAL', payeeRef: 'MPESA-0712345678',
    takenBy: 'staff-two', payoutDecidedBy: null, payoutRefusalReason: null };

  it('lets a second person release a waiting payout, saying whom it pays', async () => {
    grantAccess('payouts.approve');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([waiting]);
    vi.spyOn(policiesApi, 'approveLoanPayout').mockResolvedValue({ ...waiting, status: 'DISBURSEMENT_REQUESTED' });
    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange={false} canRecordDeposit={false} canRequestPayment={false} />);

    expect(await screen.findByText(/To MPESA-0712345678/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Release payout' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pay TZS 500,000.00' }));
    await waitFor(() => expect(policiesApi.approveLoanPayout).toHaveBeenCalledWith(waiting.loanId));
  });

  it('refuses a payout only with a reason', async () => {
    grantAccess('payouts.approve');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([waiting]);
    vi.spyOn(policiesApi, 'refuseLoanPayout').mockResolvedValue({ ...waiting, status: 'PAYOUT_REFUSED' });
    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange={false} canRecordDeposit={false} canRequestPayment={false} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Refuse' }));
    expect(screen.getByRole('button', { name: 'Refuse payout' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Why is it refused?'), 'Payee number does not match');
    await userEvent.click(screen.getByRole('button', { name: 'Refuse payout' }));
    await waitFor(() => expect(policiesApi.refuseLoanPayout).toHaveBeenCalledWith(waiting.loanId, 'Payee number does not match'));
  });

  it('does not let the person who took the loan release it without the own-work permission', async () => {
    grantAccess('payouts.approve');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([{ ...waiting, takenBy: 'staff-one' }]);
    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange={false} canRecordDeposit={false} canRequestPayment={false} />);

    expect(await screen.findByText('You took this loan, so someone else must release it.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Release payout' })).toBeDisabled();
  });

  it('shows a waiting payout without the buttons to someone who cannot release it', async () => {
    grantAccess('accounts.view');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([waiting]);
    renderPanel(<LoansPanel policyNumber="POL-000001" cashValue={TZS('0.00')} canChange={false} canRecordDeposit={false} canRequestPayment={false} />);

    expect(await screen.findByText(/waiting for a second person/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release payout' })).not.toBeInTheDocument();
  });
});
