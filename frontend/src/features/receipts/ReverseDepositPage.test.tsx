import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as receiptsApi from '@/api/receipts';
import type { Receipt } from '@/api/receipts';
import { grantRoles } from '@/test/access';
import { useReceiptStore } from '@/store/receiptStore';
import { ReverseDepositPage } from './ReverseDepositPage';

vi.mock('@/api/receipts');
vi.mock('@/api/access');

const confirmed: Receipt = {
  receiptId: 'r-1', purpose: 'PREMIUM', method: 'CHEQUE', reference: 'CHQ-731', payerRef: 'Amina Owner',
  amount: { amount: '120000.00', currencyCode: 'TZS' }, invoiceId: 'inv-1', policyNumber: 'POL-1',
  receivedOn: '2026-10-27', status: 'CONFIRMED', recordedBy: 'finance-one', recordedAt: '2026-10-27T08:00:00Z',
};

describe('ReverseDepositPage', () => {
  beforeEach(() => {
    useReceiptStore.setState({ receipt: {}, deciding: {}, reversals: {}, reversing: {} });
    vi.mocked(receiptsApi.getReceipt).mockResolvedValue(confirmed);
    vi.mocked(receiptsApi.listDepositReversals).mockResolvedValue([]);
  });

  it('asks for a reversal only with a reason, then returns to the deposit', async () => {
    grantRoles('FINANCE_OFFICER');
    vi.mocked(receiptsApi.requestDepositReversal).mockResolvedValue({
      reversalId: 'rev-1', policyNumber: 'POL-1', paymentId: 'r-1', amount: confirmed.amount, reason: 'The cheque bounced',
      status: 'REQUESTED', requestedBy: 'finance-one', requestedAt: '2026-11-04T08:00:00Z', decidedBy: null,
      decidedAt: null, rejectionReason: null, actedAlone: false,
    });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/staff/receipts/r-1/reverse']}>
        <Routes>
          <Route path="/staff/receipts/:receiptId/reverse" element={<ReverseDepositPage />} />
          <Route path="/staff/receipts/:receiptId" element={<p>Deposit page</p>} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('CHQ-731')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    await user.type(screen.getByLabelText('Why is it reversed?'), 'The cheque bounced');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Ask to reverse' }));

    await waitFor(() => expect(receiptsApi.requestDepositReversal).toHaveBeenCalledWith('r-1', 'The cheque bounced'));
    expect(await screen.findByText('Deposit page')).toBeInTheDocument();
  });
});
