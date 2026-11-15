import { screen, waitFor, within } from '@testing-library/react';
import { todayIso } from '@/lib/dates';
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
  amount: { amount: '4500000.00', currencyCode: 'TZS' }, outcome: 'DONE', reason: null, eventCount: 1, actedAlone: false,
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

  it('says why a line was refused on the line itself, before it is opened', async () => {
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    const line = await screen.findByRole('button', { name: /Approved a payout/ });
    expect(line).toHaveAttribute('aria-expanded', 'false');
    expect(line).toHaveTextContent('You cannot approve your own payout');
    expect(screen.getByRole('button', { name: /Approved the claim/ })).not.toHaveTextContent('approve your own');
  });

  it('reads as a timeline: one heading per day, each line carrying only its time', async () => {
    const yesterday: ActivityLine = { ...approved, id: 'a-0', occurredAt: '2026-11-13T09:00:00Z', label: 'Closed the case' };
    vi.mocked(api.listActivity).mockResolvedValue({ items: [approved, yesterday], page: { page: 0, pageSize: 25, totalElements: 2 } });
    render(<ActivityLogPage />, { route: '/staff/audit-log?from=2026-11-01&to=2026-11-14' });

    const log = await screen.findByRole('list', { name: 'Activity' });
    expect(within(log).getAllByRole('heading', { level: 2 })).toHaveLength(2);
    const line = within(log).getByRole('button', { name: /Approved the claim/ });
    expect(line.querySelector('time')).toHaveAttribute('dateTime', approved.occurredAt);
    expect(line).not.toHaveTextContent('2026');
  });

  it('offers ranges, and own dates only when asked or when the address names them', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    expect(await screen.findByRole('button', { name: 'Last 7 days', pressed: true })).toBeInTheDocument();
    expect(screen.queryByLabelText('From')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Today' }));
    await waitFor(() =>
      expect(api.listActivity).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: expect.stringMatching(new RegExp(`^${todayIso()}T00`)) }), 0),
    );

    await user.click(screen.getByRole('button', { name: 'Choose dates' }));
    expect(screen.getByLabelText('From')).toBeInTheDocument();
    expect(screen.getByLabelText('To')).toBeInTheDocument();
  });

  it('says "Not recorded" for what happened before the log, and "Acted alone" on the action itself', async () => {
    const before: ActivityLine = { ...lapsed, id: 'e-0', actorKind: 'UNRECORDED', label: 'claims.ClaimAssessed', recordLabel: null };
    const alone: ActivityLine = { ...approved, id: 'a-9', label: 'Approved a withdrawal', actedAlone: true };
    vi.mocked(api.listActivity).mockResolvedValue({ items: [alone, before], page: { page: 0, pageSize: 25, totalElements: 2 } });
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    expect(await screen.findByRole('button', { name: /Approved a withdrawal/ })).toHaveTextContent('Acted alone');
    const old = screen.getByRole('button', { name: /Claim assessed/ });
    expect(old).toHaveTextContent('Not recorded');
    expect(old).not.toHaveTextContent('System');
  });

  it('opens several lines at once, and keeps them open across a filter change', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    await user.click(await screen.findByRole('button', { name: /Approved the claim/ }));
    await user.click(screen.getByRole('button', { name: /Approved a payout/ }));
    expect(screen.getByRole('button', { name: /Approved the claim/ })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: /Approved a payout/ })).toHaveAttribute('aria-expanded', 'true');

    await user.click(screen.getByRole('button', { name: 'Refused' }));
    await waitFor(() => expect(api.listActivity).toHaveBeenLastCalledWith(expect.objectContaining({ outcome: 'REFUSED' }), 0));
    expect(screen.getByRole('button', { name: /Approved a payout/ })).toHaveAttribute('aria-expanded', 'true');
  });

  it('names its chip groups, and a chip pressed again lets go', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log?outcome=REFUSED' });

    const outcome = await screen.findByRole('group', { name: 'Outcome' });
    expect(screen.getByRole('group', { name: 'Kind' })).toBeInTheDocument();
    const refused = within(outcome).getByRole('button', { name: 'Refused', pressed: true });
    await user.click(refused);
    await waitFor(() => expect(api.listActivity).toHaveBeenLastCalledWith(expect.not.objectContaining({ outcome: expect.anything() }), 0));
    expect(screen.queryByRole('button', { name: /All kinds|Any outcome/ })).not.toBeInTheDocument();
  });

  it('applies Record as it is typed, with no Find to press', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    await user.type(await screen.findByRole('searchbox', { name: 'Record' }), 'CLM-0042');
    await waitFor(() => expect(api.listActivity).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'CLM-0042' }), 0));
    expect(screen.queryByRole('button', { name: 'Find' })).not.toBeInTheDocument();
  });

  it('pins the action a link names above the list, opened, and offers a link to any opened action', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log?line=a-1' });

    const pinned = await screen.findByRole('list', { name: 'Linked action' });
    expect(api.getActivity).toHaveBeenCalledWith('a-1');
    const line = within(pinned).getByRole('button', { name: /Approved the claim/ });
    expect(line).toHaveAttribute('aria-expanded', 'true');
    expect(line).toHaveTextContent('2026'); // standing alone, it carries its date

    expect(await within(pinned.parentElement!).findByRole('button', { name: 'Copy link to this action' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('list', { name: 'Linked action' })).not.toBeInTheDocument());
  });

  it('shows the refusal reason when a refused line is opened', async () => {
    const user = userEvent.setup();
    render(<ActivityLogPage />, { route: '/staff/audit-log' });

    await user.click(await screen.findByRole('button', { name: /Approved a payout/ }));
    expect(screen.getByText(/Why it was refused:/).parentElement).toHaveTextContent('You cannot approve your own payout');
  });
});
