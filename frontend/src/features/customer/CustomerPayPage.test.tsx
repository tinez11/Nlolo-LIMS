import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import * as portalApi from '@/api/portal';
import { CustomerPayPage } from './CustomerPayPage';

vi.mock('@/api/portal');
vi.mock('@/api/policies');

describe('CustomerPayPage', () => {
  it('asks the customer to approve a premium on their phone', async () => {
    vi.mocked(portalApi.getMe).mockResolvedValue({ partyId: 'p-1', displayName: 'Nadine', email: null, phoneNumber: '0754123456' });
    vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue({ displayName: 'Nadine', activePolicies: 1, claimsInProgress: 0,
      unreadMessages: 0, nextPremium: null, accountValue: null, policies: [{ policyNumber: 'POL-1', productName: 'Nuru Funeral Cover',
        productCategory: 'FUNERAL', status: 'ACTIVE', sumAssured: 0, currency: 'TZS', premium: 10000, premiumFrequency: 'MONTHLY',
        nextDueDate: null, nextDueAmount: null, value: null }] });
    vi.mocked(policiesApi.listInvoices).mockResolvedValue([
      { invoiceId: 'i-1', policyNumber: 'POL-1', dueDate: '2026-10-08', status: 'DUE', amount: { amount: '10000.00', currencyCode: 'TZS' } },
      { invoiceId: 'i-2', policyNumber: 'POL-1', dueDate: '2026-11-08', status: 'DUE', amount: { amount: '10000.00', currencyCode: 'TZS' } },
    ]);
    vi.mocked(policiesApi.requestPaymentForInvoice).mockResolvedValue(undefined);
    render(<MemoryRouter><CustomerPayPage /></MemoryRouter>);

    // Only the next premium, not the year raised ahead.
    const pay = await screen.findAllByRole('button', { name: 'Pay' });
    expect(pay).toHaveLength(1);
    await userEvent.click(pay[0] as HTMLElement);
    expect(screen.getByLabelText(/Mobile money number/)).toHaveValue('0754123456');
    await userEvent.click(screen.getByRole('button', { name: 'Send payment request' }));

    expect(await screen.findByRole('status')).toHaveTextContent(/Check your phone/);
    expect(policiesApi.requestPaymentForInvoice).toHaveBeenCalledWith('i-1', { payerRef: '0754123456' }, expect.anything());
  });
});
