import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import * as receiptsApi from '@/api/receipts';
import type { InvoiceView, PremiumCreditView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { usePolicyStore } from '@/store/policyStore';
import { useReceiptStore } from '@/store/receiptStore';
import { InvoicesPanel } from './InvoicesPanel';

vi.mock('@/api/policies');
vi.mock('@/api/receipts');
vi.mock('@/api/creditLife');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

const invoice: InvoiceView = {
  invoiceId: 'inv-1', policyNumber: 'POL-CL-1', dueDate: '2027-04-01', amount: TZS('84000.00'), status: 'PAID',
  amountPaid: TZS('84000.00'), amountCredited: TZS('9000.00'), balanceDue: TZS('0.00'),
};

const credit: PremiumCreditView = {
  creditId: 'cr-1', policyNumber: 'POL-CL-1', policyMemberId: 'm-1', originalInvoiceId: 'inv-1',
  amount: TZS('9000.00'), exitReason: 'SETTLED_EARLY', exitDate: '2027-05-03', createdAt: '2027-05-03T08:00:00Z',
  refundAmount: TZS('9000.00'), refundStatus: 'AWAITING_APPROVAL', refundPayee: 'CRDB — policyholder of POL-CL-1',
  refundRefusalReason: null,
};

function renderPanel() {
  render(
    <MemoryRouter>
      <InvoicesPanel policyNumber="POL-CL-1" />
    </MemoryRouter>,
  );
}

describe('InvoicesPanel lender refunds (money movement step 4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    usePolicyStore.setState({ invoices: {}, detail: {} });
    useReceiptStore.setState({ waiting: {} });
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([invoice]);
    vi.mocked(policiesApi.listPolicyCredits).mockResolvedValue([credit]);
    vi.mocked(policiesApi.getSchemeMember).mockRejectedValue(new Error('no member'));
    vi.mocked(receiptsApi.listWaitingReceipts).mockResolvedValue([]);
  });

  it('lets a second person approve a refund raised automatically, then reads the credits again', async () => {
    grantAccess('payouts.approve');
    vi.mocked(policiesApi.decideLenderRefund).mockResolvedValue({});
    renderPanel();

    expect(await screen.findByText(/Paid back to the lender: TZS 9,000.00 · waiting for a second person/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Approve refund' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pay TZS 9,000.00' }));
    await waitFor(() => expect(policiesApi.decideLenderRefund).toHaveBeenCalledWith('cr-1', null));
    await waitFor(() => expect(policiesApi.listPolicyCredits).toHaveBeenCalledTimes(2));
  });

  it('says a refused refund is still owed, and offers nothing to someone who cannot approve', async () => {
    grantAccess('collections.view');
    vi.mocked(policiesApi.listPolicyCredits).mockResolvedValue([
      { ...credit, refundStatus: 'REFUSED', refundRefusalReason: 'Offset against next file' },
    ]);
    renderPanel();

    expect(await screen.findByText(/refused; still owed to the lender \(Offset against next file\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve refund' })).not.toBeInTheDocument();
  });
});
