import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { CustomerPolicyView, CustomerStatementView } from '@/api/portal';
import { formatDate } from '@/lib/dates';
import { CustomerPolicyPage } from './CustomerPolicyPage';
import { useLanguageStore } from './language';

vi.mock('@/api/portal');
vi.mock('@/features/documents/PaymentScheduleTable', () => ({
  PaymentScheduleTable: () => <p>payment schedule</p>,
  PaymentScheduleDownloads: () => null,
}));

const endowment: CustomerPolicyView = {
  summary: { policyNumber: 'POL-E1', productName: 'Elimu Plan', productCategory: 'ENDOWMENT', status: 'ACTIVE',
    sumAssured: 1000000, currency: 'TZS', premium: 50000, premiumFrequency: 'MONTHLY', nextDueDate: null,
    nextDueAmount: null, value: null },
  lifeAssuredName: 'Amina Owner', commencementDate: '2026-10-01', maturityDate: null, termMonths: 120,
  beneficiaries: [], coveredLives: null, savings: null, units: null, annuity: null, deposit: null, claims: [],
  balance: { amount: 70000, currency: 'TZS', coversPremiumsTo: '2026-12-01' },
};

const statement: CustomerStatementView = {
  currency: 'TZS', total: 2, page: 0, size: 10, lines: [
    { entryId: 'e-2', type: 'PREMIUM_PAID', at: '2026-11-04T09:00:00Z', amount: -50000, balanceAfter: 70000 },
    { entryId: 'e-1', type: 'DEPOSIT', at: '2026-11-04T08:59:00Z', amount: 120000, balanceAfter: 120000 },
  ],
};

function renderPolicy() {
  render(
    <MemoryRouter initialEntries={['/customers/policies/POL-E1']}>
      <Routes><Route path="/customers/policies/:policyNumber" element={<CustomerPolicyPage />} /></Routes>
    </MemoryRouter>,
  );
}

describe('CustomerPolicyPage balance (money movement step 5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useLanguageStore.setState({ language: 'en' });
    vi.mocked(portalApi.getCustomerPolicy).mockResolvedValue(endowment);
    vi.mocked(portalApi.getCustomerStatement).mockResolvedValue(statement);
  });

  it('shows the balance, how far ahead it pays, and the statement newest first', async () => {
    renderPolicy();
    expect(await screen.findByRole('heading', { name: 'Balance' })).toBeInTheDocument();
    expect(screen.getByText(`Covers premiums to ${formatDate('2026-12-01')}`)).toBeInTheDocument();
    expect(await screen.findByText('Premium paid')).toBeInTheDocument();
    expect(screen.getByText('Payment')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show older' })).not.toBeInTheDocument();
  });

  it('says it in Swahili', async () => {
    useLanguageStore.setState({ language: 'sw' });
    renderPolicy();
    expect(await screen.findByRole('heading', { name: 'Salio' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Taarifa ya malipo' })).toBeInTheDocument();
    expect(await screen.findByText('Ada imelipwa')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Omba kutoa pesa' })).toBeInTheDocument();
  });

  it('asks for a withdrawal to the customer’s phone and says it waits for us', async () => {
    vi.mocked(portalApi.requestCustomerWithdrawal).mockResolvedValue({ withdrawalId: 'w-1' });
    const user = userEvent.setup();
    renderPolicy();
    await user.click(await screen.findByRole('button', { name: 'Request a withdrawal' }));
    expect(screen.getByRole('button', { name: 'Send request' })).toBeDisabled();
    await user.type(screen.getByLabelText(/^Amount/), '20000');
    await user.type(screen.getByLabelText('Mobile money number'), '+255712000001');
    await user.click(screen.getByRole('button', { name: 'Send request' }));
    await waitFor(() => expect(portalApi.requestCustomerWithdrawal).toHaveBeenCalledWith('POL-E1', '20000', '+255712000001'));
    expect(await screen.findByText('Your request is with us. We let you know when it is paid.')).toBeInTheDocument();
  });

  it('shows no balance on a policy without one', async () => {
    vi.mocked(portalApi.getCustomerPolicy).mockResolvedValue({ ...endowment, balance: null });
    renderPolicy();
    expect(await screen.findByRole('heading', { name: 'Elimu Plan' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Balance' })).not.toBeInTheDocument();
    expect(portalApi.getCustomerStatement).not.toHaveBeenCalled();
  });
});
