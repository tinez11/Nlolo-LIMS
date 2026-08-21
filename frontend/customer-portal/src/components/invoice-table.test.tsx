import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { InvoiceTable } from './invoice-table';

const DUE = {
  invoiceId: '11111111-1111-1111-1111-111111111111',
  policyNumber: 'POL-1', dueDate: '2026-09-01',
  amount: { amount: '125000', currencyCode: 'TZS' },
  status: 'DUE' as const, gracePeriodEndsAt: null, dunningLevel: null,
};

describe('InvoiceTable', () => {
  it('renders each invoice with formatted money and status', () => {
    render(<InvoiceTable invoices={[DUE]} onPay={vi.fn()} />);
    expect(screen.getByText('TZS 125,000.00')).toBeInTheDocument();
    expect(screen.getByText('DUE')).toBeInTheDocument();
  });

  it('offers Pay only for invoices that can still be paid', () => {
    render(<InvoiceTable invoices={[{ ...DUE, status: 'PAID' }]} onPay={vi.fn()} />);
    expect(screen.queryByRole('button', { name: /pay/i })).not.toBeInTheDocument();
  });

  it('shows the dunning level when an arrears case is open', () => {
    render(<InvoiceTable invoices={[{ ...DUE, status: 'OVERDUE', dunningLevel: 3 }]} onPay={vi.fn()} />);
    expect(screen.getByText(/reminder 3/i)).toBeInTheDocument();
  });

  it('calls onPay once even when Pay is clicked three times in a tick', async () => {
    const onPay = vi.fn().mockImplementation(() => new Promise<void>((r) => setTimeout(r, 10)));
    render(<InvoiceTable invoices={[DUE]} onPay={onPay} />);
    const button = screen.getByRole('button', { name: /pay/i });

    button.click();
    button.click();
    button.click();

    expect(onPay).toHaveBeenCalledTimes(1);
  });

  it('shows an empty state when a status filter matches nothing', () => {
    render(<InvoiceTable invoices={[]} onPay={vi.fn()} />);
    expect(screen.getByText(/no invoices/i)).toBeInTheDocument();
  });
});
