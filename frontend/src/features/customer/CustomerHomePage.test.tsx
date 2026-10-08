import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import * as portalApi from '@/api/portal';
import type { PolicyView } from '@/api/types';
import { CustomerHomePage } from './CustomerHomePage';

vi.mock('@/api/portal');
vi.mock('@/api/policies');

describe('CustomerHomePage', () => {
  it('greets the customer and lists the policies they hold', async () => {
    vi.mocked(portalApi.getMe).mockResolvedValue({ partyId: 'p-1', displayName: 'Nadine Kileo', email: null, phoneNumber: '+255715000001' });
    vi.mocked(policiesApi.searchPolicies).mockResolvedValue({
      items: [{ policyNumber: 'POL-ABFE4139', productCategory: 'FUNERAL', status: 'ACTIVE',
        premium: { amount: '100000.00', currencyCode: 'TZS' }, premiumFrequency: 'ANNUALLY' } as PolicyView],
      page: { page: 0, pageSize: 50, totalElements: 1 },
    });
    render(<CustomerHomePage />);

    expect(await screen.findByRole('heading', { name: 'Welcome, Nadine' })).toBeInTheDocument();
    expect(screen.getByText('POL-ABFE4139')).toBeInTheDocument();
    expect(screen.getByText(/a year/)).toBeInTheDocument();
    // Nothing on the page chooses whose policies: the server scopes them to the token.
    expect(policiesApi.searchPolicies).toHaveBeenCalledWith({ pageSize: 50 });
  });
});
