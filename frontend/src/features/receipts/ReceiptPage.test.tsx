import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as receiptsApi from '@/api/receipts';
import type { Receipt } from '@/api/receipts';
import { grantRoles } from '@/test/access';
import { useReceiptStore } from '@/store/receiptStore';
import { ReceiptPage } from './ReceiptPage';

vi.mock('@/api/receipts');
vi.mock('@/api/access');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: null }) }));

const waiting: Receipt = {
  receiptId: 'r-1', method: 'BANK_DEPOSIT', reference: 'SLIP-42', payerRef: 'Amina Owner',
  amount: { amount: '120000.00', currencyCode: 'TZS' }, invoiceId: 'inv-1', policyNumber: 'POL-1',
  receivedOn: '2026-10-27', status: 'AWAITING_APPROVAL', recordedBy: 'finance-one',
  recordedAt: '2026-10-27T08:00:00Z',
};

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/staff/receipts/r-1']}>
      <Routes>
        <Route path="/staff/receipts/:receiptId" element={<ReceiptPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ReceiptPage', () => {
  beforeEach(() => {
    useReceiptStore.setState({ receipt: {}, deciding: {} });
    vi.mocked(receiptsApi.getReceipt).mockResolvedValue(waiting);
  });

  it('shows what came in, how, and its reference by name', async () => {
    grantRoles('FINANCE_OFFICER');
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Bank deposit receipt' })).toBeInTheDocument();
    expect(screen.getByText('Bank slip or transfer reference')).toBeInTheDocument();
    expect(screen.getByText('SLIP-42')).toBeInTheDocument();
  });

  it('lets a finance officer confirm it, and shows the outcome the server gives', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(receiptsApi.approveReceipt).mockResolvedValue({ ...waiting, status: 'CONFIRMED', decidedBy: 'finance-two' });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Confirm' }));
    await user.click(screen.getByRole('button', { name: 'Confirm receipt' }));
    expect(await screen.findByRole('heading', { name: 'Confirmed' })).toBeInTheDocument();
    expect(receiptsApi.approveReceipt).toHaveBeenCalledWith('r-1');
  });

  it('asks for a reason before refusing', async () => {
    grantRoles('FINANCE_OFFICER');
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Refuse' }));
    expect(screen.getByRole('button', { name: 'Refuse receipt' })).toBeDisabled();
  });

  it('tells someone who cannot confirm that finance will', async () => {
    grantRoles('CUSTOMER_SERVICE_REP');
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Waiting for confirmation' })).toBeInTheDocument();
  });
});
