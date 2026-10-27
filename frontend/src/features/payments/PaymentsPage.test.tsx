import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as paymentsApi from '@/api/payments';
import type { PaymentRegisterPage } from '@/api/payments';
import { idle } from '@/store/createResourceSlice';
import { usePaymentStore } from '@/store/paymentStore';
import { PaymentsPage } from './PaymentsPage';

vi.mock('@/api/payments');

const page: PaymentRegisterPage = {
  items: [
    { id: 'p-1', direction: 'IN', purpose: 'PREMIUM', method: 'MOBILE_MONEY', status: 'IN_DOUBT',
      amount: { amount: '50180000.00', currencyCode: 'TZS' }, counterparty: '+255700000777', reference: null,
      sourceRef: 'inv-1', policyNumber: 'POL-1', createdAt: '2026-10-04T11:03:00Z', executedBy: null, executedAt: null },
    { id: 'p-2', direction: 'OUT', purpose: 'CLAIM_SETTLEMENT', method: 'EFT', status: 'FAILED',
      amount: { amount: '800000.00', currencyCode: 'TZS' }, counterparty: 'Lender Bank Ltd', reference: null,
      sourceRef: 'claim-9', policyNumber: null, createdAt: '2026-10-05T11:03:00Z', executedBy: null, executedAt: null },
  ],
  page: { page: 0, pageSize: 20, totalElements: 2 },
};

function renderPage(path = '/staff/payments') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <PaymentsPage />
    </MemoryRouter>,
  );
}

describe('PaymentsPage', () => {
  beforeEach(() => {
    usePaymentStore.setState({ register: idle() });
    vi.mocked(paymentsApi.listPayments).mockResolvedValue(page);
  });

  it('opens on what needs attention, money in and out, in plain words', async () => {
    renderPage();
    expect(await screen.findByText('In · Premium')).toBeInTheDocument();
    expect(screen.getByText('Out · Claim settlement')).toBeInTheDocument();
    expect(screen.getByText('Bank transfer')).toBeInTheDocument();
    expect(screen.getByText('In doubt')).toBeInTheDocument();
    expect(vi.mocked(paymentsApi.listPayments)).toHaveBeenCalledWith(
      expect.objectContaining({ state: 'attention', page: 0 }),
    );
  });

  it("asks for one policy's money when a policy number is applied", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('In · Premium');
    await user.type(screen.getByPlaceholderText('POL-XXXXXXXX'), 'pol-1');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(vi.mocked(paymentsApi.listPayments)).toHaveBeenLastCalledWith(
      expect.objectContaining({ policyNumber: 'POL-1' }),
    );
  });

  it('says plainly when nothing needs attention', async () => {
    vi.mocked(paymentsApi.listPayments).mockResolvedValue({ items: [], page: { page: 0, pageSize: 20, totalElements: 0 } });
    renderPage();
    expect(await screen.findByText('No payment needs attention')).toBeInTheDocument();
  });
});
