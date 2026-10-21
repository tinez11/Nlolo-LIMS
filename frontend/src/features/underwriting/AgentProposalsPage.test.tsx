import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getNeedsAction } from '@/api/proposalAmendments';
import { listCases } from '@/api/underwriting';
import type { UnderwritingCaseView } from '@/api/types';
import { renderScreen } from '@/test/renderScreen';
import { AgentProposalsPage } from './AgentProposalsPage';

vi.mock('@/api/underwriting', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/underwriting')>()),
  listCases: vi.fn(),
}));
vi.mock('@/api/proposalAmendments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/proposalAmendments')>()),
  getNeedsAction: vi.fn(),
}));
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>party {partyId}</span> }));
vi.mock('@/components/ProductName', () => ({ ProductName: () => <span>the product</span> }));

const aCase = (over: Partial<UnderwritingCaseView> = {}): UnderwritingCaseView =>
  ({
    caseId: 'c-1',
    proposalNumber: 'PRO-0001',
    status: 'OPEN',
    applicantPartyId: 'p-1',
    productId: 'prod-1',
    decisionOutcome: null,
    ...over,
  }) as UnderwritingCaseView;

beforeEach(() => vi.clearAllMocks());

function renderPage(route = '/agents/proposals') {
  renderScreen(
    <Routes>
      <Route path="/agents/proposals" element={<AgentProposalsPage />} />
      <Route path="/agents/proposals/:caseId" element={<p>Proposal detail page</p>} />
    </Routes>,
    { route },
  );
}

describe('AgentProposalsPage', () => {
  // 2026-10-21: before this page existed, an agent had no way to see a proposal that was accepted or still being
  // reviewed -- only "Needs your action" (returned, postponed, declined) existed at all.
  it('lists every proposal, including one already accepted, with its current status', async () => {
    vi.mocked(listCases).mockResolvedValue({
      items: [
        aCase({ status: 'DECIDED', decisionOutcome: 'ACCEPT' }),
        aCase({ caseId: 'c-2', proposalNumber: 'PRO-0002', status: 'IN_REVIEW' }),
      ],
      page: { page: 0, pageSize: 20, totalElements: 2 },
    } as never);
    renderPage();

    await screen.findByText('PRO-0001');
    const rows = screen.getAllByRole('row').slice(1); // drop the header row
    expect(within(rows[0]!).getByText('PRO-0001')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('Accept')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('PRO-0002')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('In review')).toBeInTheDocument();
    expect(listCases).toHaveBeenCalledWith({ page: 0, pageSize: 20 });
  });

  it('filters by status', async () => {
    vi.mocked(listCases).mockResolvedValue({ items: [], page: { page: 0, pageSize: 20, totalElements: 0 } } as never);
    renderPage();
    await screen.findByText('No proposals yet');

    await userEvent.click(screen.getByText('Open'));
    await waitFor(() => expect(listCases).toHaveBeenCalledWith({ status: 'OPEN', page: 0, pageSize: 20 }));
  });

  // "Needs your action" used to be its own page; it is a view of this one now.
  it('folds Needs your action in as a view of the same page', async () => {
    vi.mocked(listCases).mockResolvedValue({ items: [aCase()], page: { page: 0, pageSize: 20, totalElements: 1 } } as never);
    vi.mocked(getNeedsAction).mockResolvedValue([
      {
        caseView: aCase({ status: 'RETURNED' }),
        reasons: [{ category: 'COVER_OR_TERM', problem: 'Ten years was asked for', change: 'Make it fifteen years' }],
        note: null,
        actionable: true,
      },
    ] as never);
    renderPage();
    await screen.findByText('PRO-0001');
    expect(listCases).toHaveBeenCalled();

    await userEvent.click(screen.getByText('Needs your action'));
    expect(await screen.findByText(/Make it fifteen years/)).toBeInTheDocument();
    expect(getNeedsAction).toHaveBeenCalled();
    // The general list is not asked again while this view is active.
    expect(listCases).toHaveBeenCalledTimes(1);
  });

  // 2026-10-21: close enough to "as it happens" for an agent watching one proposal move through review -- asked
  // again on its own, not only when the agent happens to navigate away and back.
  it('asks again on its own while the tab is visible', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(listCases).mockResolvedValue({ items: [aCase()], page: { page: 0, pageSize: 20, totalElements: 1 } } as never);
      renderPage();
      await vi.waitFor(() => expect(listCases).toHaveBeenCalledTimes(1));

      await vi.advanceTimersByTimeAsync(30_000);
      await vi.waitFor(() => expect(listCases).toHaveBeenCalledTimes(2));
    } finally {
      vi.useRealTimers();
    }
  });

  it('opens a proposal from the list', async () => {
    vi.mocked(listCases).mockResolvedValue({ items: [aCase()], page: { page: 0, pageSize: 20, totalElements: 1 } } as never);
    renderPage();

    await userEvent.click(await screen.findByText('PRO-0001'));
    expect(await screen.findByText('Proposal detail page')).toBeInTheDocument();
  });
});
