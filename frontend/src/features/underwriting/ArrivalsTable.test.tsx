import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getArrivals } from '@/api/proposalAmendments';
import { renderScreen } from '@/test/renderScreen';
import { ArrivalsTable } from './ArrivalsTable';

vi.mock('@/api/proposalAmendments', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/proposalAmendments')>()),
  getArrivals: vi.fn(),
}));
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>party {partyId}</span> }));
vi.mock('@/components/ProductName', () => ({ ProductName: () => <span>the product</span> }));

const row = (caseId: string, proposalNumber: string, round: number, introducedBy: string | null) => ({
  caseView: { caseId, proposalNumber, status: 'OPEN', applicantPartyId: `p-${caseId}`, productId: 'prod-1' },
  lastSubmittedAt: '2026-10-20T09:00:00Z',
  round,
  resubmitted: round > 1,
  introducedBy,
});

beforeEach(() => vi.clearAllMocks());

describe('ArrivalsTable', () => {
  it('marks a resubmission with its round and says which agent it came from', async () => {
    vi.mocked(getArrivals).mockResolvedValue({
      items: [row('c-1', 'PRO-0001', 2, 'Juma Senior'), row('c-2', 'PRO-0002', 1, null)],
      page: { page: 0, pageSize: 20, totalElements: 2 },
    } as never);
    const onTotal = vi.fn();
    const onPreview = vi.fn();
    renderScreen(
      <ArrivalsTable resubmittedOnly={false} page={0} onPage={vi.fn()} previewing={null} onPreview={onPreview}
        onTotal={onTotal} />,
    );

    expect(await screen.findByText('Resubmitted · round 2')).toBeInTheDocument();
    expect(screen.getByText('Agent · Juma Senior')).toBeInTheDocument();
    expect(screen.getByText('Direct')).toBeInTheDocument();
    // The order is the server's -- most recently arrived first -- kept as it came.
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows[0]).toHaveTextContent('PRO-0001');
    expect(onTotal).toHaveBeenCalledWith(2);

    await userEvent.click(screen.getAllByRole('button')[0]!);
    expect(onPreview).toHaveBeenCalledWith('c-1');
  });

  it('asks for the ones back from agents alone, and says when there are none', async () => {
    vi.mocked(getArrivals).mockResolvedValue({ items: [], page: { page: 0, pageSize: 20, totalElements: 0 } } as never);
    renderScreen(
      <ArrivalsTable resubmittedOnly page={0} onPage={vi.fn()} previewing={null} onPreview={vi.fn()} onTotal={vi.fn()} />,
    );

    await waitFor(() => expect(getArrivals).toHaveBeenCalledWith({ resubmittedOnly: true, page: 0, pageSize: 20 }));
    expect(await screen.findByText('Nothing back from agents')).toBeInTheDocument();
  });
});
