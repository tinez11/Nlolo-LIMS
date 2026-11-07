import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as paymentsApi from '@/api/payments';
import type { PaymentDirection, PaymentRegisterPage } from '@/api/payments';
import { idle } from '@/store/createResourceSlice';
import { usePaymentStore } from '@/store/paymentStore';
import { PaymentsPage } from './PaymentsPage';

vi.mock('@/api/payments');

const deposits: PaymentRegisterPage = {
  items: [
    { id: 'p-1', direction: 'IN', purpose: 'PREMIUM', method: 'MOBILE_MONEY', status: 'IN_DOUBT',
      amount: { amount: '50180000.00', currencyCode: 'TZS' }, counterparty: '+255700000777', reference: null,
      sourceRef: 'inv-1', policyNumber: 'POL-1', createdAt: '2026-10-04T11:03:00Z', executedBy: null, executedAt: null },
  ],
  page: { page: 0, pageSize: 20, totalElements: 1 },
};

const payouts: PaymentRegisterPage = {
  items: [
    { id: 'p-2', direction: 'OUT', purpose: 'CLAIM_SETTLEMENT', method: 'EFT', status: 'FAILED',
      amount: { amount: '800000.00', currencyCode: 'TZS' }, counterparty: 'Lender Bank Ltd', reference: null,
      sourceRef: 'claim-9', policyNumber: null, createdAt: '2026-10-05T11:03:00Z', executedBy: null, executedAt: null },
  ],
  page: { page: 0, pageSize: 20, totalElements: 1 },
};

function renderPage(direction: PaymentDirection, path = direction === 'IN' ? '/staff/payments' : '/staff/payouts/all') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <PaymentsPage direction={direction} />
    </MemoryRouter>,
  );
}

describe('PaymentsPage', () => {
  beforeEach(() => {
    usePaymentStore.setState({ register: idle() });
    vi.mocked(paymentsApi.listPayments).mockResolvedValue(deposits);
  });

  it('is Deposits for money in: opens on what needs attention, asking only for money in, in plain words', async () => {
    renderPage('IN');
    expect(await screen.findByText('Premium')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Deposits' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'From' })).toBeInTheDocument();
    expect(screen.getByText('In doubt')).toBeInTheDocument();
    expect(vi.mocked(paymentsApi.listPayments)).toHaveBeenCalledWith(
      expect.objectContaining({ state: 'attention', direction: 'IN', page: 0 }),
    );
  });

  it('is All payouts for money out, under the Payouts views, with no Deposit', async () => {
    vi.mocked(paymentsApi.listPayments).mockResolvedValue(payouts);
    renderPage('OUT');
    expect(await screen.findByText('Claim payout')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'All payouts' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'To' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All payouts' })).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByRole('link', { name: 'Deposit' })).not.toBeInTheDocument();
    expect(vi.mocked(paymentsApi.listPayments)).toHaveBeenCalledWith(expect.objectContaining({ direction: 'OUT' }));
  });

  it("asks for one policy's money when a policy number is applied", async () => {
    const user = userEvent.setup();
    renderPage('IN');
    await screen.findByText('Premium');
    await user.type(screen.getByLabelText('Policy or receipt number'), 'pol-1');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(vi.mocked(paymentsApi.listPayments)).toHaveBeenLastCalledWith(
      expect.objectContaining({ policyNumber: 'POL-1', direction: 'IN' }),
    );
  });

  it('finds a receipt by its number, in any state -- the number a customer quotes (receipts step 1)', async () => {
    const user = userEvent.setup();
    renderPage('IN');
    await screen.findByText('Premium');
    await user.type(screen.getByLabelText('Policy or receipt number'), 'rc-2026-000014');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(vi.mocked(paymentsApi.listPayments)).toHaveBeenLastCalledWith(
      expect.objectContaining({ receiptNumber: 'RC-2026-000014', state: 'all', direction: 'IN' }),
    );
    expect(vi.mocked(paymentsApi.listPayments).mock.lastCall?.[0]).not.toHaveProperty('policyNumber');
  });

  it('says plainly when nothing needs attention', async () => {
    vi.mocked(paymentsApi.listPayments).mockResolvedValue({ items: [], page: { page: 0, pageSize: 20, totalElements: 0 } });
    renderPage('OUT');
    expect(await screen.findByText('No payout needs attention')).toBeInTheDocument();
  });
});
