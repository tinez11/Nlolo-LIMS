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
import { success } from '@/store/createResourceSlice';
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

describe('InvoicesPanel asks no phone itself (2026-11-07: every deposit is on the Deposit page)', () => {
  const due: InvoiceView = {
    ...invoice, status: 'DUE', amountPaid: TZS('0.00'), amountCredited: TZS('0.00'), balanceDue: TZS('84000.00'),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    usePolicyStore.setState({ invoices: {}, detail: {} });
    useReceiptStore.setState({ waiting: {} });
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([due]);
    vi.mocked(policiesApi.listPolicyCredits).mockResolvedValue([]);
    vi.mocked(policiesApi.getSchemeMember).mockRejectedValue(new Error('no member'));
    vi.mocked(receiptsApi.listWaitingReceipts).mockResolvedValue([]);
  });

  it('offers no Request payment on a due invoice, even to someone who may ask a phone', async () => {
    grantAccess('policies.service', 'collections.manage');
    renderPanel();
    expect(await screen.findByText('TZS 84,000.00')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request payment' })).not.toBeInTheDocument();
  });
});

/** 2026-11-08: POL-3E440FE9's starting premium was waived, then offered Waive again, and could never be paid. */
describe('InvoicesPanel waivers', () => {
  const due: InvoiceView = {
    ...invoice, status: 'DUE', amountPaid: TZS('0.00'), amountCredited: TZS('0.00'), balanceDue: TZS('84000.00'),
  };
  const waived: InvoiceView = { ...due, status: 'WAIVED', balanceDue: TZS('0.00') };

  function withPolicy(status: string) {
    usePolicyStore.setState({ detail: { 'POL-CL-1': success({ policyNumber: 'POL-CL-1', status } as never) } });
  }

  beforeEach(() => {
    vi.clearAllMocks();
    usePolicyStore.setState({ invoices: {}, detail: {} });
    useReceiptStore.setState({ waiting: {} });
    vi.mocked(policiesApi.listPolicyCredits).mockResolvedValue([]);
    vi.mocked(policiesApi.getSchemeMember).mockRejectedValue(new Error('no member'));
    vi.mocked(receiptsApi.listWaitingReceipts).mockResolvedValue([]);
    grantAccess('collections.manage', 'collections.view');
  });

  it('offers no second waiver of a waived premium, but lets finance undo it, saying why', async () => {
    withPolicy('PROPOSED');
    vi.mocked(policiesApi.listInvoices).mockResolvedValueOnce([waived]).mockResolvedValue([due]);
    vi.mocked(policiesApi.undoWaiver).mockResolvedValue();
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Undo waiver' }));
    expect(screen.queryByRole('button', { name: 'Waive' })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/Why is the waiver undone/), 'Waived in error; the customer will pay');
    await user.click(screen.getByRole('button', { name: 'Undo waiver' }));
    await user.click(screen.getByRole('button', { name: 'Owe it again' }));

    expect(policiesApi.undoWaiver).toHaveBeenCalledWith('inv-1', { reason: 'Waived in error; the customer will pay' });
    await waitFor(() => expect(policiesApi.listInvoices).toHaveBeenCalledTimes(2));
  });

  it("offers no waiver of a proposed policy's premium, and says it is paid", async () => {
    withPolicy('PROPOSED');
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([due]);
    renderPanel();
    expect(await screen.findByText('This premium starts the cover: it is paid, not waived.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Waive' })).not.toBeInTheDocument();
  });

  // 2026-11-08: the row said the premium is paid, and not where.
  it('leads a premium already due to the Deposit page, and offers no Pay on one not yet due', async () => {
    withPolicy('PROPOSED');
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([
      { ...due, invoiceId: 'inv-now', dueDate: '2026-01-07' },
      { ...due, invoiceId: 'inv-later', dueDate: '2999-01-07' },
    ]);
    renderPanel();
    const pay = await screen.findAllByRole('link', { name: 'Pay' });
    expect(pay).toHaveLength(1);
    expect(pay[0]).toHaveAttribute('href', '/staff/receipts/new?policy=POL-CL-1&from=policy');
  });

  it('offers a waiver of what an in-force policy still owes, and none of a paid premium', async () => {
    withPolicy('ACTIVE');
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([due, { ...invoice, invoiceId: 'inv-2' }]);
    renderPanel();
    expect(await screen.findAllByRole('button', { name: 'Waive' })).toHaveLength(1);
  });
});
