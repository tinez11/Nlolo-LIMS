import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { CustomerDashboardView, CustomerPolicySummary } from '@/api/portal';
import { CustomerHomePage } from './CustomerHomePage';

vi.mock('@/api/portal');

const policy = (over: Partial<CustomerPolicySummary> = {}): CustomerPolicySummary => ({
  policyNumber: 'POL-ABFE4139', productName: 'Nuru Funeral Cover', productCategory: 'FUNERAL', status: 'ACTIVE',
  sumAssured: 0, currency: 'TZS', premium: 100000, premiumFrequency: 'ANNUALLY', nextDueDate: null, nextDueAmount: null,
  value: null, ...over,
});

const dashboard = (over: Partial<CustomerDashboardView> = {}): CustomerDashboardView => ({
  displayName: 'Nadine Kileo', activePolicies: 1, claimsInProgress: 0, unreadMessages: 0, nextPremium: null, accountValue: null,
  policies: [policy()], ...over,
});

function renderHome() {
  vi.mocked(portalApi.getMe).mockResolvedValue({ partyId: 'p-1', displayName: 'Nadine Kileo', email: null, phoneNumber: null });
  render(<MemoryRouter><CustomerHomePage /></MemoryRouter>);
}

describe('CustomerHomePage (dashboard)', () => {
  it('greets the customer, counts their policies and links each one', async () => {
    vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue(dashboard());
    renderHome();

    expect(await screen.findByRole('heading', { name: 'Welcome, Nadine' })).toBeInTheDocument();
    expect(screen.getByText('Policies in force')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Nuru Funeral Cover/ })).toHaveAttribute('href', '/customers/policies/POL-ABFE4139');
    expect(screen.getByText(/TZS 100,000.00 a year/)).toBeInTheDocument();
    expect(screen.getByText('In force')).toBeInTheDocument();
  });

  it('shows only the cards that apply', async () => {
    vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue(dashboard());
    renderHome();
    await screen.findByRole('heading', { name: 'Welcome, Nadine' });
    expect(screen.queryByText('Next premium')).not.toBeInTheDocument();
    expect(screen.queryByText('Claims in progress')).not.toBeInTheDocument();
    expect(screen.queryByText('Savings and investments')).not.toBeInTheDocument();
  });

  it('shows the next premium, claims in progress and what savings are worth when there are any', async () => {
    vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue(dashboard({
      claimsInProgress: 1,
      nextPremium: { policyNumber: 'POL-1', productName: 'Savings', amount: 50000, currency: 'TZS', dueDate: '2026-11-01', status: 'IN_GRACE' },
      accountValue: { amount: 4850000, currency: 'TZS' },
    }));
    renderHome();
    expect(await screen.findByText('TZS 50,000.00')).toBeInTheDocument();
    expect(screen.getByText(/in the grace period/)).toBeInTheDocument();
    expect(screen.getByText('Claims in progress')).toBeInTheDocument();
    expect(screen.getByText('TZS 4,850,000.00')).toBeInTheDocument();
    expect(screen.queryByText('New messages')).not.toBeInTheDocument();
  });

  it('points to unread messages', async () => {
    vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue(dashboard({ unreadMessages: 2 }));
    renderHome();
    expect(await screen.findByRole('link', { name: /New messages/ })).toHaveAttribute('href', '/customers/messages');
  });
});
