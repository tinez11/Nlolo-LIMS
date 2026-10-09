import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as claimsApi from '@/api/claims';
import * as portalApi from '@/api/portal';
import type { CustomerPolicyView } from '@/api/portal';
import { CustomerReportClaimPage } from './CustomerReportClaimPage';

vi.mock('@/api/portal');
vi.mock('@/api/claims');
// The segmented date control is tested on its own; a plain input stands in for it here.
vi.mock('@/components/DatePicker', () => ({
  DatePicker: ({ onChange }: { onChange: (v: string) => void }) => (
    <input aria-label="date" onChange={(e) => onChange(e.target.value)} />
  ),
}));

const summary = { policyNumber: 'POL-F', productName: 'Nuru Funeral Cover', productCategory: 'FUNERAL', status: 'ACTIVE',
  sumAssured: 0, currency: 'TZS', premium: 100000, premiumFrequency: 'ANNUALLY', nextDueDate: null, nextDueAmount: null,
  value: null };
const funeral: CustomerPolicyView = {
  summary, lifeAssuredName: 'Nadine Kileo', commencementDate: '2026-01-01', maturityDate: null, termMonths: null,
  beneficiaries: [], savings: null, units: null, annuity: null, claims: [],
  coveredLives: [
    { coveredLifeId: 'life-main', name: 'Nadine Kileo', role: 'MAIN_MEMBER', benefit: 4000000, status: 'ACTIVE', waitingPeriodEnds: null },
    { coveredLifeId: 'life-mum', name: 'Rehema Kileo', role: 'PARENT', benefit: 1500000, status: 'ACTIVE', waitingPeriodEnds: null },
    { coveredLifeId: 'life-gone', name: 'Old Kileo', role: 'PARENT', benefit: 1500000, status: 'ENDED', waitingPeriodEnds: null },
  ],
};

describe('CustomerReportClaimPage', () => {
  it('asks a funeral policyholder who died, reviews, then files the claim as theirs', async () => {
    vi.mocked(portalApi.getMe).mockResolvedValue({ partyId: 'party-1', displayName: 'Nadine Kileo', email: null, phoneNumber: null });
    vi.mocked(portalApi.getCustomerDashboard).mockResolvedValue({ displayName: 'Nadine Kileo', activePolicies: 1,
      claimsInProgress: 0, nextPremium: null, accountValue: null, policies: [summary] });
    vi.mocked(portalApi.getCustomerPolicy).mockResolvedValue(funeral);
    vi.mocked(claimsApi.registerClaim).mockResolvedValue({ claimId: 'new-claim' } as never);
    render(
      <MemoryRouter initialEntries={['/customers/claims/new']}>
        <Routes>
          <Route path="/customers/claims/new" element={<CustomerReportClaimPage />} />
          <Route path="/customers/claims/:claimId" element={<p>claim page</p>} />
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.selectOptions(await screen.findByLabelText('Policy'), 'POL-F');
    const who = await screen.findByLabelText('Who died?');
    // Only the family covered today.
    expect(screen.queryByRole('option', { name: /Old Kileo/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(who, 'life-mum');
    await userEvent.type(screen.getByLabelText('date'), '2026-10-01');
    await userEvent.type(screen.getByLabelText('Cause of death'), 'Stroke');
    await userEvent.type(screen.getByLabelText('Where it happened'), 'Amana Hospital');
    await userEvent.click(screen.getByRole('button', { name: 'Review' }));

    expect(await screen.findByText('Rehema Kileo (Parent)')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Send claim' }));

    expect(await screen.findByText('claim page')).toBeInTheDocument();
    expect(claimsApi.registerClaim).toHaveBeenCalledWith(expect.objectContaining({ policyNumber: 'POL-F',
      claimantPartyId: 'party-1', claimType: 'DEATH', coveredLifeId: 'life-mum', dateOfEvent: '2026-10-01' }), expect.anything());
  });
});
