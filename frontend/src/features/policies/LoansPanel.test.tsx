import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import type { LoanLimit, LoanView, Money } from '@/api/types';
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
  principalOutstanding: TZS('300000.00'),
  interestOutstanding: TZS('28770.00'),
  currentInterestRate: 12,
  status: 'DISBURSED',
  takenAt: '2026-10-01T08:00:00Z',
  payeeRef: '+255712345678',
};

const limit = (over: Partial<LoanLimit> = {}): LoanLimit => ({
  policyNumber: 'POL-000001', lends: true, value: TZS('1000000.00'), maxLoanToValuePercent: 80,
  limit: TZS('800000.00'), borrowed: TZS('300000.00'), interestOwed: TZS('28770.00'), canBorrowNow: TZS('471230.00'),
  annualInterestRate: 12, ...over,
});

/** The deposit link needs a router; the panel never navigates on its own. */
function renderPanel(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

const panel = (over: { canChange?: boolean; canRecordDeposit?: boolean; canRequestPayment?: boolean } = {}) => (
  <LoansPanel policyNumber="POL-000001" canChange={over.canChange ?? true}
    canRecordDeposit={over.canRecordDeposit ?? true} canRequestPayment={over.canRequestPayment ?? true} />
);

beforeEach(() => {
  vi.clearAllMocks();
  usePolicyStore.setState({ loans: {}, originatingLoan: {}, decidingLoanPayout: {} });
  vi.spyOn(policiesApi, 'getLoanLimit').mockResolvedValue(limit());
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** 2026-11-07, the user: the Loans page shows everything a loan needs, clearly -- every figure the server's. */
describe('LoansPanel', () => {
  it('leads with how much can be borrowed and how that is reached', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    renderPanel(panel());

    expect(await screen.findByText('80% of the value, TZS 800,000.00')).toBeInTheDocument();
    for (const [label, value] of [['Policy value', 'TZS 1,000,000.00'], ['Borrowed', 'TZS 300,000.00'],
      ['Can borrow now', 'TZS 471,230.00'], ['Interest rate', '12% a year']]) {
      expect(screen.getAllByText(label)[0]!.closest('div')).toHaveTextContent(value!);
    }
  });

  it('shows each loan: when taken, where paid, and what it owes split into borrowed and interest', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    renderPanel(panel());

    expect(await screen.findByText('Loan of TZS 500,000.00')).toBeInTheDocument();
    expect(screen.getByText(/paid to \+255712345678/)).toBeInTheDocument();
    expect(screen.getByText('Borrowed, still owed').closest('div')).toHaveTextContent('TZS 300,000.00');
    expect(screen.getByText('Owed now').closest('div')).toHaveTextContent('TZS 328,770.00');
    expect(screen.getByText('Paid out')).toBeInTheDocument();
  });

  it('opens a loan’s history in plain words, naming the payout a loan was repaid from', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    vi.spyOn(policiesApi, 'getLoanHistory').mockResolvedValue([
      { kind: 'DISBURSEMENT', amount: TZS('500000.00'), at: '2026-10-01T08:00:00Z', reference: 'MM-1' },
      { kind: 'INTEREST_ACCRUAL', amount: TZS('28770.00'), at: '2026-10-31T21:00:00Z', reference: null },
      { kind: 'SETTLEMENT', amount: TZS('200000.00'), at: '2026-11-05T08:00:00Z', reference: 'CLAIM:c-1' },
    ]);
    renderPanel(panel());
    await userEvent.click(await screen.findByRole('button', { name: 'History' }));

    const history = await screen.findByRole('table', { name: 'Loan history' });
    expect(within(history).getByText('Interest added')).toBeInTheDocument();
    expect(within(history).getByText('Repaid from the claim')).toBeInTheDocument();
    expect(policiesApi.getLoanHistory).toHaveBeenCalledWith(disbursedLoan.loanId);
  });

  it('offers no repayment control on a loan that cannot take one', async () => {
    // DISBURSEMENT_REQUESTED: the server refuses a repayment on it with a 409, so nothing is offered.
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([{ ...disbursedLoan, status: 'DISBURSEMENT_REQUESTED' }]);
    renderPanel(panel());

    expect(await screen.findByText('Loan of TZS 500,000.00')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Repay' })).not.toBeInTheDocument();
  });

  it('offers Repay on a repayable loan, leading to the one Deposit page -- never a typed repayment', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    renderPanel(panel());

    expect(await screen.findByRole('link', { name: 'Repay' })).toHaveAttribute('href',
      `/staff/receipts/new?for=LOAN&loan=${disbursedLoan.loanId}&policy=POL-000001&from=policy`);
    expect(screen.queryByRole('button', { name: 'Record repayment' })).not.toBeInTheDocument();
  });

  it('shows no Repay to someone without the collections permissions', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    renderPanel(panel({ canRecordDeposit: false, canRequestPayment: false }));

    expect(await screen.findByText('Loan of TZS 500,000.00')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Repay' })).not.toBeInTheDocument();
  });

  it('disables origination while the policy has no value, and says why', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([]);
    vi.spyOn(policiesApi, 'getLoanLimit').mockResolvedValue(limit({
      value: TZS('0.00'), limit: TZS('0.00'), borrowed: TZS('0.00'), interestOwed: TZS('0.00'), canBorrowNow: TZS('0.00'),
    }));
    renderPanel(panel());

    // Rendered text, not a `title`: a tooltip is unreachable by keyboard and silent to a screen reader.
    expect(await screen.findByText(/the cash value is 0\.00/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take a loan' })).toBeDisabled();
  });

  it('says when the product gives no loans at all', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([]);
    vi.spyOn(policiesApi, 'getLoanLimit').mockResolvedValue(limit({ lends: false, maxLoanToValuePercent: 0 }));
    renderPanel(panel());

    expect(await screen.findByText('This product gives no loans: its maximum loan is 0%.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take a loan' })).toBeDisabled();
  });

  it('says the most that can be borrowed before anything is typed, then takes the loan', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([]);
    const originate = vi.spyOn(policiesApi, 'originateLoan').mockResolvedValue(disbursedLoan);
    const user = userEvent.setup();
    renderPanel(panel());

    const takeLoan = await screen.findByRole('button', { name: 'Take a loan' });
    await waitFor(() => expect(takeLoan).toBeEnabled());
    await user.click(takeLoan);
    expect(screen.getByText('Up to TZS 471,230.00.')).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Loan amount/), '400000');
    await user.type(screen.getByLabelText(/Pay the loan to/), '+255712345678');
    await user.click(screen.getByRole('button', { name: 'Take a loan' }));

    await waitFor(() =>
      expect(originate).toHaveBeenCalledWith('POL-000001', {
        requestedAmount: { amount: '400000', currencyCode: 'TZS' },
        payeeRef: '+255712345678',
      }),
    );
  });

  it('shows an agent the loans but offers no loan and no repayment (2026-10-27)', async () => {
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([disbursedLoan]);
    renderPanel(panel({ canChange: false, canRecordDeposit: false, canRequestPayment: false }));
    expect(await screen.findByText('Loan of TZS 500,000.00')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Take a loan' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Repay' })).not.toBeInTheDocument();
  });

  // Money movement step 4: a loan's payout waits for a second person.
  const waiting: LoanView = { ...disbursedLoan, status: 'AWAITING_PAYOUT_APPROVAL', payeeRef: 'MPESA-0712345678',
    takenBy: 'staff-two', payoutDecidedBy: null, payoutRefusalReason: null };

  it('lets a second person release a waiting payout, saying whom it pays', async () => {
    grantAccess('payouts.approve');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([waiting]);
    vi.spyOn(policiesApi, 'approveLoanPayout').mockResolvedValue({ ...waiting, status: 'DISBURSEMENT_REQUESTED' });
    renderPanel(panel({ canChange: false, canRecordDeposit: false, canRequestPayment: false }));

    expect(await screen.findByText(/To MPESA-0712345678/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Release payout' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pay TZS 500,000.00' }));
    await waitFor(() => expect(policiesApi.approveLoanPayout).toHaveBeenCalledWith(waiting.loanId));
  });

  it('refuses a payout only with a reason', async () => {
    grantAccess('payouts.approve');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([waiting]);
    vi.spyOn(policiesApi, 'refuseLoanPayout').mockResolvedValue({ ...waiting, status: 'PAYOUT_REFUSED' });
    renderPanel(panel({ canChange: false, canRecordDeposit: false, canRequestPayment: false }));

    await userEvent.click(await screen.findByRole('button', { name: 'Refuse' }));
    expect(screen.getByRole('button', { name: 'Refuse payout' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Why is it refused?'), 'Payee number does not match');
    await userEvent.click(screen.getByRole('button', { name: 'Refuse payout' }));
    await waitFor(() => expect(policiesApi.refuseLoanPayout).toHaveBeenCalledWith(waiting.loanId, 'Payee number does not match'));
  });

  it('does not let the person who took the loan release it without the own-work permission', async () => {
    grantAccess('payouts.approve');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([{ ...waiting, takenBy: 'staff-one' }]);
    renderPanel(panel({ canChange: false, canRecordDeposit: false, canRequestPayment: false }));

    expect(await screen.findByText('You took this loan, so someone else must release it.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Release payout' })).toBeDisabled();
  });

  it('shows a waiting payout without the buttons to someone who cannot release it', async () => {
    grantAccess('accounts.view');
    vi.spyOn(policiesApi, 'listLoans').mockResolvedValue([waiting]);
    renderPanel(panel({ canChange: false, canRecordDeposit: false, canRequestPayment: false }));

    expect(await screen.findByText(/waiting for a second person to release/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release payout' })).not.toBeInTheDocument();
  });
});
