import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/api/activity';
import type { ActivityDetail, ActivityLine } from '@/api/activity';
import { grantAccess } from '@/test/access';
import { renderScreen as render } from '@/test/renderScreen';
import { ActivityLogPage } from './ActivityLogPage';

vi.mock('@/api/activity');

const approved: ActivityLine = {
  id: 'a-1', occurredAt: '2026-11-14T07:32:00Z', actorSubject: 'sub-asha', actorName: 'Asha M.', actorKind: 'STAFF',
  label: 'Approved the claim', kind: 'claims', recordType: 'claim', recordId: 'c-42', recordLabel: 'CLM-0042',
  amount: { amount: '4500000.00', currencyCode: 'TZS' }, outcome: 'DONE', reason: null, eventCount: 1,
};
const refused: ActivityLine = {
  ...approved, id: 'a-2', label: 'Approved a payout', outcome: 'REFUSED', eventCount: 0, amount: null,
  reason: 'You cannot approve your own payout',
};
const lapsed: ActivityLine = {
  ...approved, id: 'e-1', actorSubject: null, actorName: null, actorKind: 'SYSTEM', label: 'policy.PolicyLapsed',
  kind: 'policies', recordType: null, recordId: null, recordLabel: 'POL-1', amount: null, eventCount: 0,
};
const detail: ActivityDetail = {
  line: approved,
  changes: [{ field: 'approvedAmount', before: null, after: '4500000.00' }],
  events: [{ eventType: 'claims.ClaimApproved', occurredAt: '2026-11-14T07:32:01Z', refs: { claimId: 'c-42' }, amount: null }],
  technical: { method: 'POST', route: '/claims/{claimId}/approve', statusCode: 200, body: '{"approvedAmount":"4500000.00"}' },
};

/** 2026-11-14, the user: an audit log admins can read -- who did what, when, and what followed. */
describe('ActivityLogPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    grantAccess('audit.view');
    vi.mocked(api.listActivity).mockResolvedValue({ items: [approved, refused, lapsed], page: { page: 0, pageSize: 25, totalElements: 3 } });
    vi.mocked(api.listActivityPeople).mockResolvedValue([{ subject: 'sub-asha', name: 'Asha M.' }]);
    vi.mocked(api.getActivity).mockResolvedValue(detail);
  });

  it('says who did what in words, why a refusal happened, and what the platform did by itself', async () => {
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    const first = await screen.findByRole('button', { name: /Approved the claim/ });
    expect(first).toHaveTextContent('Asha M. (staff)');
    expect(first).toHaveTextContent('CLM-0042');
    expect(first).toHaveTextContent('1 thing followed');
    expect(screen.getByRole('button', { name: /Approved a payout/ })).toHaveTextContent('Refused');
    expect(screen.getByRole('button', { name: /Policy lapsed/ })).toHaveTextContent('System');
    // The last seven days unless asked otherwise, as Dar es Salaam days.
    expect(api.listActivity).toHaveBeenCalledWith(
      expect.objectContaining({ from: expect.stringMatching(/T00:00:00\+03:00$/), to: expect.stringMatching(/T23:59:59\.999\+03:00$/) }), 0);
  });

  it('opens a line onto what changed and what followed, and filters to the person', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    await user.click(await screen.findByRole('button', { name: /Approved the claim/ }));
    expect(await screen.findByText('Approved amount: set to 4500000.00')).toBeInTheDocument();
    expect(screen.getByText('What followed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the claim' })).toHaveAttribute('href', '/staff/claims/c-42');
    expect(screen.getByText('Technical detail')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Only Asha M.' }));
    await waitFor(() => expect(api.listActivity).toHaveBeenLastCalledWith(expect.objectContaining({ person: 'sub-asha' }), 0));
  });

  it('shows the refusal reason when a refused line is opened', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    await user.click(await screen.findByRole('button', { name: /Approved a payout/ }));
    expect(screen.getByText(/Why it was refused:/).parentElement).toHaveTextContent('You cannot approve your own payout');
  });
});
