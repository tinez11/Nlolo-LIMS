import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import * as receiptsApi from '@/api/receipts';
import type { BalanceStatementLine, BalanceStatementView, Money } from '@/api/types';
import { formatDate } from '@/lib/dates';
import { grantAccess } from '@/test/access';
import { usePolicyStore } from '@/store/policyStore';
import { useReceiptStore } from '@/store/receiptStore';
import { BalancePanel, BalanceStatementPanel } from './BalancePanel';

vi.mock('@/api/policies');
vi.mock('@/api/receipts');

const TZS = (amount: string): Money => ({ amount, currencyCode: 'TZS' });

function line(seq: number, type: BalanceStatementLine['type'], amount: string, balanceAfter: string,
  extra: Partial<BalanceStatementLine> = {}): BalanceStatementLine {
  return {
    entryId: `00000000-0000-0000-0000-00000000000${seq}`, seq, type, at: '2026-11-04T09:00:00Z',
    amount: TZS(amount), balanceAfter: TZS(balanceAfter), paymentId: null, invoiceId: null, withdrawalId: null,
    reversesEntryId: null, method: null, reference: null, source: 'PAYMENT', ...extra,
  };
}

function statement(lines: BalanceStatementLine[], total = lines.length): BalanceStatementView {
  return { policyNumber: 'POL-000001', total, page: 0, size: 25, lines };
}

/** The deposit links need a router; the panels never navigate on their own. */
function renderPanel(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  usePolicyStore.setState({ balance: {}, balanceStatement: {} });
  useReceiptStore.setState({ waitingOnPolicy: {} });
  vi.mocked(receiptsApi.listWaitingDeposits).mockResolvedValue([]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('BalancePanel', () => {
  it('shows the server’s balance and how far ahead it pays, and links to Deposit', async () => {
    grantAccess('collections.manage');
    vi.mocked(policiesApi.getPolicyBalance).mockResolvedValue({
      policyNumber: 'POL-000001', balance: TZS('25000.00'), coversPremiumsTo: '2026-12-04',
    });
    renderPanel(<BalancePanel policyNumber="POL-000001" />);

    expect(await screen.findByText('TZS 25,000.00')).toBeInTheDocument();
    expect(screen.getByText(formatDate('2026-12-04'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Deposit' }))
      .toHaveAttribute('href', '/staff/receipts/new?policy=POL-000001&from=policy');
  });

  it('shows a deposit recorded on the policy while it waits for a second person', async () => {
    grantAccess('collections.view');
    vi.mocked(policiesApi.getPolicyBalance).mockResolvedValue({
      policyNumber: 'POL-000001', balance: TZS('0.00'), coversPremiumsTo: null,
    });
    vi.mocked(receiptsApi.listWaitingDeposits).mockResolvedValue([{
      receiptId: 'rc-1', purpose: 'DEPOSIT', method: 'BANK_DEPOSIT', reference: 'SLIP-77', payerRef: 'Neema',
      amount: TZS('30000.00'), invoiceId: null, loanId: null, policyNumber: 'POL-000001', receivedOn: '2026-11-05',
      note: null, fieldReceiptId: null, status: 'AWAITING_APPROVAL', recordedBy: 'staff-1',
      recordedAt: '2026-11-05T08:00:00Z', decidedBy: null, decidedAt: null, decisionReason: null,
    }]);
    renderPanel(<BalancePanel policyNumber="POL-000001" />);

    expect(await screen.findByText('A deposit is waiting to be confirmed')).toBeInTheDocument();
    expect(receiptsApi.listWaitingDeposits).toHaveBeenCalledWith('POL-000001');
    expect(screen.getByText('TZS 30,000.00')).toBeInTheDocument();
    expect(screen.getByText(/goes onto the balance/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/staff/receipts/rc-1');
  });

  it('says no premium is ahead, and offers no deposit to someone who cannot record one', async () => {
    grantAccess('collections.view');
    vi.mocked(policiesApi.getPolicyBalance).mockResolvedValue({
      policyNumber: 'POL-000001', balance: TZS('0.00'), coversPremiumsTo: null,
    });
    renderPanel(<BalancePanel policyNumber="POL-000001" />);

    expect(await screen.findByText('No premium ahead')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Deposit' })).not.toBeInTheDocument();
  });
});

describe('BalanceStatementPanel', () => {
  it('lists newest first with money in and out apart, and links a recorded deposit to its own page', async () => {
    vi.mocked(policiesApi.getBalanceStatement).mockResolvedValue(statement([
      line(2, 'PREMIUM_PAID', '-15000.00', '25000.00'),
      line(1, 'DEPOSIT', '40000.00', '40000.00', { paymentId: 'pay-1', method: 'CHEQUE', reference: 'CHQ-1' }),
    ]));
    renderPanel(<BalanceStatementPanel policyNumber="POL-000001" />);

    const table = await screen.findByRole('table', { name: 'Statement' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('Premium paid');
    expect(rows[0]).toHaveTextContent('TZS 15,000.00');
    expect(rows[0]).toHaveTextContent('TZS 25,000.00');
    expect(within(rows[1]!).getByRole('link', { name: 'Deposit' })).toHaveAttribute('href', '/staff/receipts/pay-1');
    expect(rows[1]).toHaveTextContent('CHQ-1');
    expect(screen.queryByRole('button', { name: 'Show older entries' })).not.toBeInTheDocument();
  });

  it('brings older entries a page at a time', async () => {
    vi.mocked(policiesApi.getBalanceStatement)
      .mockResolvedValueOnce(statement([line(2, 'PREMIUM_PAID', '-15000.00', '25000.00')], 2))
      .mockResolvedValueOnce({ ...statement([line(1, 'DEPOSIT', '40000.00', '40000.00')], 2), page: 1 });
    renderPanel(<BalanceStatementPanel policyNumber="POL-000001" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Show older entries' }));
    expect(await screen.findByText('Deposit')).toBeInTheDocument();
    expect(policiesApi.getBalanceStatement).toHaveBeenLastCalledWith('POL-000001', 1, 25);
    expect(screen.queryByRole('button', { name: 'Show older entries' })).not.toBeInTheDocument();
  });

  it('says so when nothing has reached the balance', async () => {
    vi.mocked(policiesApi.getBalanceStatement).mockResolvedValue(statement([]));
    renderPanel(<BalanceStatementPanel policyNumber="POL-000001" />);
    expect(await screen.findByText('Nothing on the balance yet')).toBeInTheDocument();
  });
});
