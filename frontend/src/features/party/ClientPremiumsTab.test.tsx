import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getClientPremiums, type ClientPremiums } from '@/api/clientPremiums';
import { formatDate } from '@/lib/dates';
import { grantAccess } from '@/test/access';
import { renderScreen } from '@/test/renderScreen';
import { ClientPremiumsTab } from './ClientPremiumsTab';

vi.mock('@/api/clientPremiums');
vi.mock('@/api/documents');

const DATA: ClientPremiums = {
  window: '30',
  overdueCount: 1,
  overdueOwed: 80000,
  nextDueOn: '2026-11-20',
  nextDueAmount: 15000,
  dueInWindowCount: 2,
  dueInWindowOwed: 95000,
  currency: 'TZS',
  lastReceipt: {
    receiptId: 'rc-1', receiptNumber: 'RC-2026-000123', policyNumber: 'POL-A', receivedOn: '2026-11-05',
    amount: 80000, currency: 'TZS',
  },
  rows: [
    { invoiceId: 'i-1', policyNumber: 'POL-A', productName: 'Term life', dueDate: '2026-10-12', owed: 80000, currency: 'TZS', status: 'OVERDUE' },
    { invoiceId: 'i-2', policyNumber: 'POL-B', productName: 'Funeral', dueDate: '2026-11-20', owed: 15000, currency: 'TZS', status: 'DUE' },
  ],
  notInvoicedAhead: [{ policyNumber: 'CRL-1', productName: 'Credit life', how: 'PER_ENROLMENT_FILE' }],
};

beforeEach(() => {
  vi.clearAllMocks();
  grantAccess('collections.view', 'collections.manage');
  vi.mocked(getClientPremiums).mockResolvedValue(DATA);
});

describe('ClientPremiumsTab', () => {
  it('shows what is owed in full, overdue first, and the last receipt', async () => {
    renderScreen(<ClientPremiumsTab partyId="party-1" />);

    expect(await screen.findByText('Overdue now')).toBeInTheDocument();
    expect(screen.getAllByText('TZS 80,000.00').length).toBeGreaterThan(0);
    expect(screen.getByText(`on ${formatDate('2026-11-20')}`)).toBeInTheDocument();
    expect(screen.getByText('TZS 95,000.00')).toBeInTheDocument();
    expect(screen.getByText('RC-2026-000123')).toBeInTheDocument();
    const policies = screen.getAllByRole('link', { name: /^POL-/ });
    expect(policies[0]).toHaveTextContent('POL-A');
    expect(getClientPremiums).toHaveBeenCalledWith('party-1', '30');
  });

  it('opens the receipt form from each invoice for someone who may record payments', async () => {
    renderScreen(<ClientPremiumsTab partyId="party-1" />);

    const record = await screen.findAllByRole('link', { name: 'Record payment' });
    expect(record[0]).toHaveAttribute('href', '/staff/receipts/new?policy=POL-A&from=client');
  });

  it('offers no Record payment to someone who may only look', async () => {
    grantAccess('collections.view');
    renderScreen(<ClientPremiumsTab partyId="party-1" />);

    expect(await screen.findByText('Overdue now')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Record payment' })).not.toBeInTheDocument();
  });

  it('says how a policy with nothing raised ahead is paid', async () => {
    renderScreen(<ClientPremiumsTab partyId="party-1" />);

    expect(await screen.findByText(/is paid per enrolment file/)).toBeInTheDocument();
  });

  it('asks for the next 90 days when chosen', async () => {
    renderScreen(<ClientPremiumsTab partyId="party-1" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Next 90 days' }));
    await waitFor(() => expect(getClientPremiums).toHaveBeenLastCalledWith('party-1', '90'));
  });

  it('says so when nothing is owed in the window', async () => {
    vi.mocked(getClientPremiums).mockResolvedValue({ ...DATA, rows: [], overdueCount: 0, overdueOwed: 0 });
    renderScreen(<ClientPremiumsTab partyId="party-1" />);

    expect(await screen.findByText('Nothing owed in the next 30 days')).toBeInTheDocument();
  });
});
