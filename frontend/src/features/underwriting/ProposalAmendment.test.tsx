import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  getCaseHistory,
  getCaseQuotation,
  getNeedsAction,
  resubmitProposal,
  returnForAmendment,
} from '@/api/proposalAmendments';
import { getCase } from '@/api/underwriting';
import { renderScreen } from '@/test/renderScreen';
import { AgentProposalPage } from './AgentProposalPage';
import { NeedsActionPage } from './NeedsActionPage';
import { ReturnToAgentPanel } from './ReturnToAgentPanel';

vi.mock('@/api/proposalAmendments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/proposalAmendments')>()),
  getCaseHistory: vi.fn(),
  getCaseQuotation: vi.fn(),
  getNeedsAction: vi.fn(),
  resubmitProposal: vi.fn(),
  returnForAmendment: vi.fn(),
  amendTerms: vi.fn(),
  amendApplicant: vi.fn(),
}));
vi.mock('@/api/underwriting');
vi.mock('@/api/party');
vi.mock('@/api/products');
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>party {partyId}</span> }));
vi.mock('@/components/ProductName', () => ({ ProductName: () => <span>the product</span> }));

const RETURNED_CASE = {
  caseId: 'case-1',
  proposalNumber: 'PRO-0001',
  status: 'RETURNED',
  applicantPartyId: 'party-1',
  productId: 'prod-1',
  decisionOutcome: null,
  sumAssured: { amount: '1000000.00', currencyCode: 'TZS' },
  requestedTermMonths: 120,
  premiumFrequency: 'MONTHLY',
};
const RETURN_EVENT = {
  eventId: 'e-1',
  kind: 'RETURNED',
  round: 1,
  actorSubject: 'uw',
  reasons: [{ category: 'COVER_OR_TERM', problem: 'Ten years is too short', change: 'Make it fifteen years' }],
  changes: [],
  previousDecision: null,
  note: 'One thing',
  at: '2026-10-20T08:00:00Z',
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getCaseQuotation).mockRejectedValue(new Error('404'));
  vi.mocked(getCaseHistory).mockResolvedValue([RETURN_EVENT] as never);
});

describe('ReturnToAgentPanel', () => {
  it('waits for what is wrong and what to change, then returns with every reason', async () => {
    vi.mocked(returnForAmendment).mockResolvedValue({} as never);
    const onReturned = vi.fn();
    renderScreen(<ReturnToAgentPanel caseId="case-1" onReturned={onReturned} />);

    const button = screen.getByRole('button', { name: 'Return to the agent' });
    expect(button).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText('What it concerns'), 'COVER_OR_TERM');
    await userEvent.type(screen.getByLabelText('What is wrong'), 'Ten years is too short');
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('What to change'), 'Make it fifteen years');
    await userEvent.click(button);

    await waitFor(() => expect(returnForAmendment).toHaveBeenCalledWith('case-1',
      [{ category: 'COVER_OR_TERM', problem: 'Ten years is too short', change: 'Make it fifteen years' }], null));
    expect(onReturned).toHaveBeenCalled();
  });
});

describe('NeedsActionPage', () => {
  it('lists a returned proposal with what to change, and a decline as final', async () => {
    vi.mocked(getNeedsAction).mockResolvedValue([
      { caseView: RETURNED_CASE, reasons: RETURN_EVENT.reasons, note: 'One thing', actionable: true },
      {
        caseView: { ...RETURNED_CASE, caseId: 'case-2', proposalNumber: 'PRO-0002', status: 'DECIDED', decisionOutcome: 'DECLINED' },
        reasons: [],
        note: 'Uninsurable medical history',
        actionable: false,
      },
    ] as never);
    renderScreen(<NeedsActionPage />, { route: '/agents/needs-action' });

    expect(await screen.findByText('PRO-0001')).toBeInTheDocument();
    expect(screen.getByText(/Make it fifteen years/)).toBeInTheDocument();
    expect(screen.getByText('Uninsurable medical history')).toBeInTheDocument();
    expect(screen.getByText(/final/)).toBeInTheDocument();
  });

  it('says so when nothing is waiting', async () => {
    vi.mocked(getNeedsAction).mockResolvedValue([]);
    renderScreen(<NeedsActionPage />);

    expect(await screen.findByText('Nothing needs your action')).toBeInTheDocument();
  });
});

function renderProposal() {
  renderScreen(
    <Routes>
      <Route path="/agents/proposals/:caseId" element={<AgentProposalPage />} />
    </Routes>,
    { route: '/agents/proposals/case-1' },
  );
}

describe('AgentProposalPage', { timeout: 20_000 }, () => {
  it('shows what the underwriter asked and resubmits the same proposal', async () => {
    vi.mocked(getCase).mockResolvedValue(RETURNED_CASE as never);
    vi.mocked(resubmitProposal).mockResolvedValue({ ...RETURNED_CASE, status: 'OPEN' } as never);
    renderProposal();

    expect(await screen.findByRole('heading', { name: 'What the underwriter asked' })).toBeInTheDocument();
    expect(screen.getAllByText(/Make it fifteen years/).length).toBeGreaterThan(0);
    await userEvent.type(screen.getByLabelText('Note to the underwriter (optional)'), 'Done as asked');
    await userEvent.click(screen.getByRole('button', { name: 'Resubmit to underwriting' }));

    await waitFor(() => expect(resubmitProposal).toHaveBeenCalledWith('case-1', 'Done as asked'));
  });

  it('a decline shows its reason, offers a new quotation, and nothing to resubmit', async () => {
    vi.mocked(getCase).mockResolvedValue({
      ...RETURNED_CASE, status: 'DECIDED', decisionOutcome: 'DECLINED', decisionDeclineReason: 'Uninsurable',
    } as never);
    vi.mocked(getCaseHistory).mockResolvedValue([]);
    renderProposal();

    expect(await screen.findByText(/This decision is final/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'new quotation' })).toHaveAttribute('href', '/agents/quotation');
    expect(screen.queryByRole('button', { name: 'Resubmit to underwriting' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save the plan' })).not.toBeInTheDocument();
  });

  it('a postponed proposal can be resubmitted', async () => {
    vi.mocked(getCase).mockResolvedValue({
      ...RETURNED_CASE, status: 'DECIDED', decisionOutcome: 'POSTPONED', decisionDeclineReason: 'Try again in six months',
    } as never);
    vi.mocked(getCaseHistory).mockResolvedValue([]);
    renderProposal();

    expect(await screen.findByText(/Try again in six months/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resubmit to underwriting' })).toBeInTheDocument();
  });
});
