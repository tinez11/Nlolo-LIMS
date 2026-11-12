import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as accumulationApi from '@/api/accumulation';
import * as policiesApi from '@/api/policies';
import * as unitLinkedApi from '@/api/unitlinked';
import type { AccountView, InvoiceView } from '@/api/types';
import * as receiptsApi from '@/api/receipts';
import { BUILT_IN_ROLES, grantAccess, grantRoles } from '@/test/access';
import { renderScreen } from '@/test/renderScreen';
import { useReceiptStore } from '@/store/receiptStore';
import { idle } from '@/store/createResourceSlice';
import { NewReceiptPage } from './NewReceiptPage';

vi.mock('@/api/policies');
vi.mock('@/api/accumulation');
vi.mock('@/api/unitlinked');
vi.mock('@/api/receipts');
vi.mock('@/api/access');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

const invoice = (id: string, dueDate: string, owed: string): InvoiceView => ({
  invoiceId: id, policyNumber: 'POL-1', dueDate, status: 'DUE', amount: TZS(owed), balanceDue: TZS(owed),
} as InvoiceView);

const savings = { policyNumber: 'POL-1', status: 'OPEN', balance: TZS('94000.00') } as AccountView;

function renderPage(query: string) {
  renderScreen(<NewReceiptPage />, { route: `/staff/receipts/new?${query}` });
}

const optionsOf = (label: string) =>
  within(screen.getByLabelText(label)).getAllByRole('option').map((o) => o.getAttribute('value'));

beforeEach(() => {
  vi.clearAllMocks();
  useReceiptStore.setState({ recording: idle(), sending: idle() });
  vi.mocked(policiesApi.listInvoices).mockResolvedValue([
    invoice('inv-late', '2026-12-01', '20000.00'),
    invoice('inv-old', '2026-11-01', '15000.00'),
  ]);
  vi.mocked(policiesApi.listLoans).mockResolvedValue([]);
  vi.mocked(accumulationApi.getAccount).mockResolvedValue(null);
  vi.mocked(policiesApi.getPolicy).mockResolvedValue({ policyNumber: 'POL-1', productCategory: 'ENDOWMENT' } as never);
});

/** 2026-11-07, the user: one place, every method, and the method chosen decides what happens behind. */
describe('Deposit page', () => {
  it('offers finance mobile money first and every way money is received by hand', async () => {
    grantRoles('FINANCE_OFFICER');
    renderPage('policy=POL-1');
    expect(screen.getByRole('heading', { name: 'Deposit' })).toBeInTheDocument();
    expect(optionsOf('How is it being paid?')).toEqual(['MOBILE_MONEY', 'BANK_DEPOSIT', 'CASH', 'CHEQUE', 'LIPA_NUMBER', 'TANQR']);
  });

  it('offers customer service only mobile money, and says who records money received', () => {
    grantAccess('policies.service', 'policies.view');
    renderPage('policy=POL-1');
    expect(optionsOf('How is it being paid?')).toEqual(['MOBILE_MONEY']);
    expect(screen.getByText(/recorded by finance/)).toBeInTheDocument();
  });

  it('asks the phone for the oldest premium owed, then closes into what was sent', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(policiesApi.requestPaymentForInvoice).mockResolvedValue();
    const user = userEvent.setup();
    renderPage('policy=POL-1');
    expect(await screen.findByText(/what the premium due 2026-11-01 still owes/)).toHaveTextContent('TZS 15,000.00');
    // A premium request asks for what that premium owes; there is no amount to type.
    expect(screen.queryByLabelText(/^Amount/)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Phone number'), '+255700000002');
    await user.click(screen.getByRole('button', { name: 'Send request' }));

    expect(policiesApi.requestPaymentForInvoice).toHaveBeenCalledWith('inv-old', { payerRef: '+255700000002' }, expect.anything());
    const receipt = await screen.findByRole('status');
    expect(receipt).toHaveTextContent('Mobile-money request sent');
    expect(receipt).toHaveTextContent('TZS 15,000.00');
    expect(screen.queryByRole('button', { name: 'Send request' })).not.toBeInTheDocument();
  });

  it('offers Savings where the policy has an open account, and records cash for it for a second person', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(accumulationApi.getAccount).mockResolvedValue(savings);
    vi.mocked(accumulationApi.recordReceivedTopUp).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderPage('for=SAVINGS&policy=POL-1');
    await vi.waitFor(() => expect(accumulationApi.getAccount).toHaveBeenCalledWith('POL-1'));
    // No loan to repay, so For offers what this policy has: its premiums and its savings.
    expect(optionsOf('For')).toEqual(['PREMIUM', 'SAVINGS']);

    await user.selectOptions(screen.getByLabelText('How is it being paid?'), 'CASH');
    await user.type(screen.getByLabelText('Cash receipt number'), 'RC-104');
    await user.type(screen.getByLabelText(/^Amount/), '25000');
    await user.click(screen.getByRole('button', { name: 'Record deposit' }));

    expect(accumulationApi.recordReceivedTopUp).toHaveBeenCalledWith(
      'POL-1',
      expect.objectContaining({ amount: '25000.00', method: 'CASH', reference: 'RC-104' }),
      expect.anything(),
    );
    const receipt = await screen.findByRole('status');
    expect(receipt).toHaveTextContent('Savings deposit recorded');
    expect(receipt).toHaveTextContent('a second finance person confirms it under Approvals');
  });

  it('asks a phone to repay the loan the link named, for the amount typed', async () => {
    grantRoles('CUSTOMER_SERVICE_REP');
    vi.mocked(policiesApi.listLoans).mockResolvedValue([{
      loanId: 'loan-1', policyNumber: 'POL-1', status: 'REPAYING', principalAmount: TZS('300000.00'),
      outstandingBalance: TZS('120000.00'),
    } as never]);
    vi.mocked(policiesApi.requestLoanPayment).mockResolvedValue();
    const user = userEvent.setup();
    renderPage('for=LOAN&loan=loan-1&policy=POL-1');
    expect(await screen.findByText('At most TZS 120,000.00, what the loan owes.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Phone number'), '+255700000002');
    await user.type(screen.getByLabelText(/^Amount/), '50000');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(policiesApi.requestLoanPayment).toHaveBeenCalledWith(
      'loan-1', { payerRef: '+255700000002', amount: TZS('50000.00') }, expect.anything(),
    );
  });

  // 2026-11-07: the agent console takes its deposits here too -- asking the payer's phone for a premium, nothing else.
  it('offers an agent the premium by mobile money only, and leads back to the agent console', async () => {
    grantAccess();
    vi.mocked(policiesApi.requestPaymentForInvoice).mockResolvedValue();
    renderScreen(<NewReceiptPage realm="agents" />, { route: '/agents/deposit?policy=POL-1' });

    expect(optionsOf('How is it being paid?')).toEqual(['MOBILE_MONEY']);
    expect(optionsOf('For')).toEqual(['PREMIUM']);
    expect(policiesApi.listLoans).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/agents/policies/POL-1?tab=billing');

    const user = userEvent.setup();
    await screen.findByText(/what the premium due 2026-11-01 still owes/);
    await user.type(screen.getByLabelText('Phone number'), '+255700000002');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(policiesApi.requestPaymentForInvoice).toHaveBeenCalledWith('inv-old', { payerRef: '+255700000002' }, expect.anything());
  });

  // 2026-11-07: a unit-linked policy's top-up is a deposit For: Funds, by any method.
  it('offers Funds on a unit-linked policy, and records cash into them for a second person', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(policiesApi.getPolicy).mockResolvedValue({ policyNumber: 'POL-1', productCategory: 'UNIT_LINKED' } as never);
    vi.mocked(unitLinkedApi.recordReceivedTopUp).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderPage('for=FUNDS&policy=POL-1');
    await vi.waitFor(() => expect(optionsOf('For')).toEqual(['PREMIUM', 'FUNDS']));

    await user.selectOptions(screen.getByLabelText('How is it being paid?'), 'CHEQUE');
    await user.type(screen.getByLabelText('Cheque number'), 'CHQ-9');
    await user.type(screen.getByLabelText(/^Amount/), '250000');
    await user.click(screen.getByRole('button', { name: 'Record deposit' }));

    expect(unitLinkedApi.recordReceivedTopUp).toHaveBeenCalledWith('POL-1',
      expect.objectContaining({ amount: '250000.00', method: 'CHEQUE', reference: 'CHQ-9' }), expect.anything());
    expect(await screen.findByRole('status')).toHaveTextContent('It buys units once a second finance person confirms it');
  });

  // 2026-11-08: a fund top-up may have its own split, as the unit-linked tab's form had.
  it('takes a fund top-up’s own split, refusing one that does not total 100%', async () => {
    grantRoles('CUSTOMER_SERVICE_REP');
    vi.mocked(policiesApi.getPolicy).mockResolvedValue({
      policyNumber: 'POL-1', productCategory: 'UNIT_LINKED', productId: 'p-1', productVersionId: 'v-1',
    } as never);
    vi.mocked(unitLinkedApi.getUnitLinkedTerms).mockResolvedValue({ fundCodes: ['EQ1', 'BD1'] } as never);
    vi.mocked(unitLinkedApi.requestTopUp).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderPage('for=FUNDS&policy=POL-1');

    await user.type(await screen.findByLabelText('Top-up into EQ1 (%)'), '50');
    await user.type(screen.getByLabelText('Top-up into BD1 (%)'), '40');
    await user.type(screen.getByLabelText('Phone number'), '+255700000002');
    await user.type(screen.getByLabelText(/^Amount/), '200000');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(screen.getByRole('alert')).toHaveTextContent('The fund split totals 90%; it must total 100%');
    expect(unitLinkedApi.requestTopUp).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText('Top-up into BD1 (%)'));
    await user.type(screen.getByLabelText('Top-up into BD1 (%)'), '50');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(unitLinkedApi.requestTopUp).toHaveBeenCalledWith('POL-1', {
      amount: '200000.00', payerRef: '+255700000002',
      split: [{ fundCode: 'EQ1', percent: 50 }, { fundCode: 'BD1', percent: 50 }],
    }, expect.anything());
  });

  // 2026-11-08: POL-3E440FE9's 20,000 single premium was refused for more, "against the next invoice" -- it has none.
  it('caps a savings policy’s premium deposit at the premium, and says the rest is a savings deposit', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(policiesApi.getPolicy).mockResolvedValue({ policyNumber: 'POL-1', portfolioCode: 'SAV' } as never);
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([invoice('inv-1', '2026-11-07', '20000.00')]);
    const user = userEvent.setup();
    renderPage('policy=POL-1');

    await user.selectOptions(screen.getByLabelText('How is it being paid?'), 'CASH');
    expect(await screen.findByText(/At most TZS 20,000.00, what the premium due 2026-11-07 owes/)).toHaveTextContent(
      "once the policy's savings account is open");
    await user.type(screen.getByLabelText('Cash receipt number'), 'RC-200');
    await user.type(screen.getByLabelText(/^Amount/), '50000');
    await user.click(screen.getByRole('button', { name: 'Record deposit' }));

    expect(await screen.findByText(/More than the premium owes \(TZS 20,000.00\)/)).toBeInTheDocument();
    expect(useReceiptStore.getState().recording.status).toBe('idle');
  });

  it('asks a phone for a savings deposit of the amount typed', async () => {
    grantRoles('CUSTOMER_SERVICE_REP');
    vi.mocked(accumulationApi.getAccount).mockResolvedValue(savings);
    vi.mocked(accumulationApi.requestTopUp).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderPage('for=SAVINGS&policy=POL-1');
    await user.type(screen.getByLabelText('Phone number'), '+255700000002');
    await user.type(screen.getByLabelText(/^Amount/), '30000');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    expect(accumulationApi.requestTopUp).toHaveBeenCalledWith(
      'POL-1', { amount: '30000.00', payerRef: '+255700000002' }, expect.anything(),
    );
  });
});

/** 2026-11-12, the user: a company with one finance officer records and confirms in one step. */
describe('Record and confirm', () => {
  async function fillCashPremium(user: ReturnType<typeof userEvent.setup>) {
    await user.selectOptions(screen.getByLabelText('How is it being paid?'), 'CASH');
    await user.type(screen.getByLabelText('Cash receipt number'), 'RC-200');
    await user.type(screen.getByLabelText(/^Amount/), '15000');
  }

  it('records the deposit and confirms it for someone who may confirm their own', async () => {
    grantAccess(...BUILT_IN_ROLES.FINANCE_OFFICER!, 'collections.approve-own');
    vi.mocked(receiptsApi.recordReceipt).mockResolvedValue({ receiptId: 'rc-1' } as never);
    vi.mocked(receiptsApi.approveReceipt).mockResolvedValue({ receiptId: 'rc-1', status: 'CONFIRMED' } as never);
    const user = userEvent.setup();
    renderPage('policy=POL-1');
    await screen.findByText(/what the premium due 2026-11-01 still owes/);
    await fillCashPremium(user);
    await user.click(screen.getByRole('button', { name: 'Record and confirm' }));

    await vi.waitFor(() => expect(receiptsApi.approveReceipt).toHaveBeenCalledWith('rc-1'));
    expect(receiptsApi.recordReceipt).toHaveBeenCalledTimes(1);
  });

  it('only records, for someone who may not confirm their own', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(receiptsApi.recordReceipt).mockResolvedValue({ receiptId: 'rc-2' } as never);
    const user = userEvent.setup();
    renderPage('policy=POL-1');
    await screen.findByText(/what the premium due 2026-11-01 still owes/);
    expect(screen.queryByRole('button', { name: 'Record and confirm' })).not.toBeInTheDocument();
    await fillCashPremium(user);
    await user.click(screen.getByRole('button', { name: 'Record deposit' }));

    await vi.waitFor(() => expect(receiptsApi.recordReceipt).toHaveBeenCalledTimes(1));
    expect(receiptsApi.approveReceipt).not.toHaveBeenCalled();
  });
});
