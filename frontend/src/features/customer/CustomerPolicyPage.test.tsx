import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { CustomerPolicyView } from '@/api/portal';
import { CustomerPolicyPage } from './CustomerPolicyPage';

vi.mock('@/api/portal');
// The premium table loads its own rows; it is tested on its own.
vi.mock('@/features/documents/PaymentScheduleTable', () => ({
  PaymentScheduleTable: () => <p>payment schedule</p>,
  PaymentScheduleDownloads: () => null,
}));

const funeralPolicy: CustomerPolicyView = {
  summary: { policyNumber: 'POL-ABFE4139', productName: 'Nuru Funeral Cover', productCategory: 'FUNERAL', status: 'ACTIVE',
    sumAssured: 0, currency: 'TZS', premium: 100000, premiumFrequency: 'ANNUALLY', nextDueDate: null, nextDueAmount: null,
    value: null },
  lifeAssuredName: 'Nadine Kileo', commencementDate: '2026-10-08', maturityDate: null, termMonths: null,
  beneficiaries: [{ name: 'Amina Kileo', sharePercent: 100 }],
  coveredLives: [{ coveredLifeId: 'life-1', name: 'Nadine Kileo', role: 'MAIN_MEMBER', benefit: 4000000, status: 'ACTIVE', waitingPeriodEnds: '2099-01-01' }],
  savings: null, units: null, annuity: null, deposit: null,
  claims: [{ claimId: 'c-1', claimType: 'DEATH', status: 'SETTLED', dateOfEvent: '2026-10-08', approvedAmount: 4000000, currency: 'TZS' }],
};

function renderPolicy() {
  render(
    <MemoryRouter initialEntries={['/customers/policies/POL-ABFE4139']}>
      <Routes><Route path="/customers/policies/:policyNumber" element={<CustomerPolicyPage />} /></Routes>
    </MemoryRouter>,
  );
}

describe('CustomerPolicyPage', () => {
  it('shows a funeral plan as its policyholder reads it: who is covered, premiums, who is paid, claims', async () => {
    vi.mocked(portalApi.getCustomerPolicy).mockResolvedValue(funeralPolicy);
    renderPolicy();

    expect(await screen.findByRole('heading', { name: 'Nuru Funeral Cover' })).toBeInTheDocument();
    expect(screen.getByText('TZS 100,000.00 a year')).toBeInTheDocument();
    expect(screen.getByText('Who is covered')).toBeInTheDocument();
    expect(screen.getByText(/Waiting period to/)).toBeInTheDocument();
    expect(screen.getByText('payment schedule')).toBeInTheDocument();
    expect(screen.getByText('Amina Kileo')).toBeInTheDocument();
    // Each claim opens on its own page.
    expect(screen.getByRole('link', { name: /Death.*Paid · TZS 4,000,000.00/ })).toHaveAttribute('href', '/customers/claims/c-1');
    // A zero sum assured is not "Cover: TZS 0" -- a funeral plan's cover is per life.
    expect(screen.queryByText('Cover')).not.toBeInTheDocument();
    expect(screen.queryByText('Savings')).not.toBeInTheDocument();
  });

  it('shows a deposit as its holder reads it: the plan, what it has earned, and what it pays at maturity', async () => {
    vi.mocked(portalApi.getCustomerPolicy).mockResolvedValue({
      ...funeralPolicy,
      summary: { ...funeralPolicy.summary, productName: 'Nlolo Fixed Deposit', productCategory: 'ENDOWMENT',
        premiumFrequency: 'SINGLE', premium: 1000000 },
      coveredLives: null, claims: [],
      deposit: { principal: 1000000, currency: 'TZS', termMonths: 3, ratePercent: 3, startDate: '2026-10-09',
        maturityDate: '2027-01-09', interestSoFar: 0, interestAtMaturity: 30000, amountAtMaturity: 1030000 },
    });
    renderPolicy();
    expect(await screen.findByText('Your deposit')).toBeInTheDocument();
    expect(screen.getByText('3 months, 3% for the term')).toBeInTheDocument();
    expect(screen.getByText('TZS 1,030,000.00')).toBeInTheDocument();
    expect(screen.getByText(/close it early at any time/)).toBeInTheDocument();
  });

  it('says plainly when the policy is not theirs', async () => {
    vi.mocked(portalApi.getCustomerPolicy).mockRejectedValue({
      status: 403, kind: 'forbidden', errorCode: 'FORBIDDEN', title: 'Forbidden', detail: 'Policy POL-X is not held by this customer',
      traceId: 't', fieldErrors: [], mayBeDenied: false,
    });
    renderPolicy();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
