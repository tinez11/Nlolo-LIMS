import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { discardAgentQuotation, getAgentQuotation, submitProposal } from '@/api/agentQuotations';
import { listProducts } from '@/api/products';
import { renderScreen } from '@/test/renderScreen';
import { SubmitProposalPage } from './SubmitProposalPage';

vi.mock('@/api/agentQuotations');
vi.mock('@/api/products');
vi.mock('@/api/party');
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>party {partyId}</span> }));

const SAVED = {
  quotationId: 'q-1',
  applicantPartyId: null,
  productId: 'prod-end',
  productVersionId: 'ver-end',
  inputs: {
    productId: 'prod-end',
    productVersionId: 'ver-end',
    prospect: { fullName: 'Halima Prospect' },
    sumAssured: 1000000,
    frequency: 'MONTHLY',
    policyTermMonths: 240,
  },
  quoted: {
    sumAssured: 1000000,
    currency: 'TZS',
    quote: { instalmentAmount: 1000, annualAfterFrequencyLoading: 12000, totalOverTerm: 240000, instalmentsOverTerm: 240 },
  },
  quotedOn: '2026-10-20',
  validUntil: '2026-11-19',
  caseId: null,
  proposalNumber: null,
};

function renderAt(route: string) {
  renderScreen(
    <Routes>
      <Route path="/agents">
        <Route path="quotation" element={<p>the calculator</p>} />
        <Route path="quotation/:quotationId" element={<SubmitProposalPage />} />
      </Route>
    </Routes>,
    { route },
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(listProducts).mockResolvedValue([{ productId: 'prod-end', productName: 'Prospect Endowment' }] as never);
  vi.mocked(getAgentQuotation).mockResolvedValue(SAVED as never);
});

describe('SubmitProposalPage', () => {
  it('shows the saved figures and submits the registered customer with the saved quotation', async () => {
    vi.mocked(submitProposal).mockResolvedValue({ ...SAVED, caseId: 'case-1', proposalNumber: 'PRO-0001' } as never);
    renderAt('/agents/quotation/q-1?party=party-9');

    expect(await screen.findByText('TZS 1,000.00 monthly')).toBeInTheDocument();
    expect(screen.getByText('Halima Prospect')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Submit to underwriting' }));

    await waitFor(() => expect(submitProposal).toHaveBeenCalledWith('q-1', 'party-9'));
    expect(await screen.findByText('Submitted to underwriting')).toBeInTheDocument();
    expect(screen.getByText('PRO-0001')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the proposal' })).toHaveAttribute('href', '/agents/proposals/case-1');
  });

  it('a quotation that is gone says so, and offers the way back', async () => {
    vi.mocked(getAgentQuotation).mockRejectedValue({
      status: 404, kind: 'notFound', title: 'Not found', detail: 'No quotation q-1', traceId: 't', errorCode: null,
      fieldErrors: [], mayBeDenied: true,
    });
    renderAt('/agents/quotation/q-1?party=party-9');

    expect(await screen.findByText('This quotation is no longer available.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit to underwriting' })).not.toBeInTheDocument();
  });

  it('a submission that lost to another shows the proposal that went in, not an error', async () => {
    vi.mocked(submitProposal).mockRejectedValue({
      status: 422, kind: 'validation', title: 'Cannot do that', detail: 'This quotation was already submitted as a proposal',
      traceId: 't', errorCode: null, fieldErrors: [], mayBeDenied: false,
    });
    vi.mocked(getAgentQuotation)
      .mockResolvedValueOnce(SAVED as never)
      .mockResolvedValueOnce({ ...SAVED, caseId: 'case-1', proposalNumber: 'PRO-0001' } as never);
    renderAt('/agents/quotation/q-1?party=party-9');

    await userEvent.click(await screen.findByRole('button', { name: 'Submit to underwriting' }));

    expect(await screen.findByText('Submitted to underwriting')).toBeInTheDocument();
    expect(screen.queryByText(/already submitted/)).not.toBeInTheDocument();
  });

  it('cannot be submitted before the customer is registered', async () => {
    renderAt('/agents/quotation/q-1');

    expect(await screen.findByRole('button', { name: 'Submit to underwriting' })).toBeDisabled();
    expect(screen.getByText('Register the customer first.')).toBeInTheDocument();
  });

  it('cancel discards the quotation and goes back to the calculator', async () => {
    vi.mocked(discardAgentQuotation).mockResolvedValue(undefined);
    renderAt('/agents/quotation/q-1?party=party-9');

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel and discard the quotation' }));

    await waitFor(() => expect(discardAgentQuotation).toHaveBeenCalledWith('q-1'));
    expect(submitProposal).not.toHaveBeenCalled();
    expect(await screen.findByText('the calculator')).toBeInTheDocument();
  });
});
