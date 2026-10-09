import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import * as claimsApi from '@/api/claims';
import * as portalApi from '@/api/portal';
import type { CustomerClaimView } from '@/api/portal';
import { CustomerClaimPage } from './CustomerClaimPage';

vi.mock('@/api/portal');
vi.mock('@/api/claims');

const claim: CustomerClaimView = {
  claimId: 'c-1', policyNumber: 'POL-1', productName: 'Nuru Funeral Cover', claimType: 'DEATH', status: 'UNDER_ASSESSMENT',
  statusText: 'Being reviewed', dateOfEvent: '2026-10-01',
  steps: [
    { label: 'Claim received', state: 'DONE', date: '2026-10-02T08:00:00Z' },
    { label: 'Being reviewed', state: 'CURRENT', date: '2026-10-03T08:00:00Z' },
    { label: 'Decision', state: 'PENDING', date: null },
    { label: 'Payment', state: 'PENDING', date: null },
  ],
  decision: null, approvedAmount: null, currency: null,
  documents: [{ documentRef: 'd-1', description: 'Death certificate', uploadedAt: '2026-10-02T08:00:00Z', uploadedByName: 'Nadine' }],
  requests: [{ requestId: 'r-1', document: 'Burial permit', reason: 'We need it to pay the funeral benefit',
    requestedAt: '2026-10-03T08:00:00Z', status: 'OPEN', receivedAt: null }],
};

function renderClaim() {
  render(
    <MemoryRouter initialEntries={['/customers/claims/c-1']}>
      <Routes><Route path="/customers/claims/:claimId" element={<CustomerClaimPage />} /></Routes>
    </MemoryRouter>,
  );
}

describe('CustomerClaimPage', () => {
  it('shows where the claim is and sends an asked-for document against its request', async () => {
    vi.mocked(portalApi.getCustomerClaim).mockResolvedValue(claim);
    vi.mocked(claimsApi.attachClaimEvidence).mockResolvedValue({} as never);
    renderClaim();

    expect(await screen.findByText('We need from you')).toBeInTheDocument();
    expect(screen.getByText('We need it to pay the funeral benefit')).toBeInTheDocument();
    expect(screen.getByText('Claim received')).toBeInTheDocument();
    expect(screen.getByText('Death certificate')).toBeInTheDocument();

    const file = new File(['x'], 'permit.pdf', { type: 'application/pdf' });
    await userEvent.upload(screen.getByLabelText('Send Burial permit'), file);
    const send = screen.getAllByRole('button', { name: 'Send' })[0];
    if (!send) throw new Error('no send button');
    await userEvent.click(send);
    expect(claimsApi.attachClaimEvidence).toHaveBeenCalledWith('c-1', file, 'Burial permit', 'r-1');
  });

  it('says the decision in plain words once made', async () => {
    vi.mocked(portalApi.getCustomerClaim).mockResolvedValue({ ...claim, status: 'REJECTED', statusText: 'Declined', requests: [],
      decision: 'Your claim was declined. Please contact us and we will explain the decision.' });
    renderClaim();
    expect(await screen.findByText(/Your claim was declined/)).toBeInTheDocument();
    expect(screen.queryByText('We need from you')).not.toBeInTheDocument();
  });
});
