import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as underwritingApi from '@/api/underwriting';
import type { UnderwritingCaseView } from '@/api/types';
import { FormField } from './FormField';
import { UnderwritingCasePicker } from './UnderwritingCasePicker';

vi.mock('@/api/underwriting');
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span> }));

const decided = (proposalNumber: string, decisionOutcome: 'ACCEPT' | 'DECLINED'): UnderwritingCaseView =>
  ({ caseId: `case-${proposalNumber}`, proposalNumber, decisionOutcome, applicantPartyId: 'p-1' }) as UnderwritingCaseView;

const page = (items: UnderwritingCaseView[], totalElements = items.length) =>
  ({ items, page: { page: 0, pageSize: 50, totalElements } });

beforeEach(() => {
  vi.clearAllMocks();
});

describe('UnderwritingCasePicker', () => {
  it('offers only the cases awaiting issue, under the field label, and finds an older one by proposal number', async () => {
    vi.mocked(underwritingApi.listCasesAwaitingIssue).mockImplementation(async (q = '') =>
      q === '' ? page([decided('PRO-AAAA1111', 'ACCEPT')], 75) : page([decided('PRO-OLD99999', 'DECLINED')]));
    const onChange = vi.fn();
    render(
      <FormField label="Underwriting case this policy is issued from">
        <UnderwritingCasePicker value={null} onChange={onChange} />
      </FormField>,
    );

    const select = await screen.findByLabelText('Underwriting case this policy is issued from');
    await screen.findByRole('option', { name: 'PRO-AAAA1111 — ACCEPT' });
    expect(underwritingApi.listCasesAwaitingIssue).toHaveBeenCalledWith('');
    expect(screen.getByText(/Showing the newest 1 of 75/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Search cases by proposal number'), 'old99');
    await screen.findByRole('option', { name: 'PRO-OLD99999 — DECLINED' });
    expect(underwritingApi.listCasesAwaitingIssue).toHaveBeenLastCalledWith('old99');

    await userEvent.selectOptions(select, 'case-PRO-OLD99999');
    await waitFor(() => expect(onChange).toHaveBeenCalledWith('case-PRO-OLD99999',
      expect.objectContaining({ proposalNumber: 'PRO-OLD99999' })));
  });

  it('picks the one matching case on Enter, and Enter never submits the form around it', async () => {
    vi.mocked(underwritingApi.listCasesAwaitingIssue).mockImplementation(async (q = '') =>
      q === '' ? page([decided('PRO-AAAA1111', 'ACCEPT'), decided('PRO-BBBB2222', 'DECLINED')]) : page([decided('PRO-BBBB2222', 'DECLINED')]));
    const onChange = vi.fn();
    const onSubmit = vi.fn((e: { preventDefault: () => void }) => e.preventDefault());
    render(<form onSubmit={onSubmit}><UnderwritingCasePicker value={null} onChange={onChange} /></form>);

    await screen.findByRole('option', { name: 'PRO-AAAA1111 — ACCEPT' });
    await userEvent.type(screen.getByLabelText('Search cases by proposal number'), 'bbbb');
    expect(await screen.findByText('1 case matches — choose below, or press Enter.')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Search cases by proposal number'), '{Enter}');

    expect(onChange).toHaveBeenCalledWith('case-PRO-BBBB2222', expect.objectContaining({ proposalNumber: 'PRO-BBBB2222' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('says plainly when no decided case is waiting for a policy', async () => {
    vi.mocked(underwritingApi.listCasesAwaitingIssue).mockResolvedValue(page([]));
    render(<UnderwritingCasePicker value={null} onChange={vi.fn()} />);
    expect(await screen.findByText(/No decided case is waiting for a policy/)).toBeInTheDocument();
  });
});
