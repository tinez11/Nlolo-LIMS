import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as underwritingApi from '@/api/underwriting';
import type { UnderwritingCaseView } from '@/api/types';
import { idle } from '@/store/createResourceSlice';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { grantAccess } from '@/test/access';
import { UnderwritingQueuePage } from './UnderwritingQueuePage';

vi.mock('@/api/underwriting', async (original) => ({
  ...(await original<typeof import('@/api/underwriting')>()),
  listCases: vi.fn(),
}));
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span> }));
vi.mock('@/components/ProductName', () => ({ ProductName: () => <span>Product</span> }));

const row = (caseId: string, source: UnderwritingCaseView['source']) => ({
  caseId, proposalNumber: `PRP-${caseId}`, status: 'OPEN', applicantPartyId: `party-${caseId}`, productId: 'p', source,
}) as UnderwritingCaseView;

beforeEach(() => {
  vi.clearAllMocks();
  grantAccess('underwriting.view');
  useUnderwritingStore.setState({ list: idle() });
  vi.mocked(underwritingApi.listCases).mockResolvedValue({
    items: [row('1', 'CALL_BACK'), row('2', 'CUSTOMER_ONLINE'), row('3', 'STAFF')],
    page: { page: 0, pageSize: 20, totalElements: 3 },
  });
});

describe('the underwriting queue', () => {
  it('says where each case came from, and lists only call backs when asked', async () => {
    render(
      <MemoryRouter initialEntries={['/staff/underwriting']}>
        <Routes><Route path="/staff/underwriting" element={<UnderwritingQueuePage />} /></Routes>
      </MemoryRouter>,
    );
    const table = await screen.findByRole('table', { name: 'Underwriting cases' });
    expect(within(table).getByText('Call back')).toBeInTheDocument();
    expect(within(table).getByText('Customer online')).toBeInTheDocument();
    expect(within(table).getByText('Staff')).toBeInTheDocument();

    await userEvent.click(within(screen.getByRole('group', { name: 'Source' })).getByRole('button', { name: 'Call back' }));
    expect(underwritingApi.listCases).toHaveBeenLastCalledWith({ source: 'CALL_BACK', page: 0, pageSize: 20 });
  });

  /** A dashboard card's dates arrive and stay (2026-11-11, analytics plan 2): they used to be dropped on the floor. */
  it('lists only the cases decided in the dashboard card months, and can clear them', async () => {
    render(
      <MemoryRouter initialEntries={['/staff/underwriting?decidedFrom=2026-07-01&decidedTo=2026-09-30&outcome=DECLINED']}>
        <Routes><Route path="/staff/underwriting" element={<UnderwritingQueuePage />} /></Routes>
      </MemoryRouter>,
    );
    await screen.findByRole('table', { name: 'Underwriting cases' });
    expect(underwritingApi.listCases).toHaveBeenCalledWith({
      decidedFrom: '2026-07-01', decidedTo: '2026-09-30', outcome: 'DECLINED', page: 0, pageSize: 20,
    });
    expect(screen.getByText('Decided Jul 1, 2026 to Sep 30, 2026 · Declined')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Clear the decided dates' }));
    expect(underwritingApi.listCases).toHaveBeenLastCalledWith({ page: 0, pageSize: 20 });
  });
});
