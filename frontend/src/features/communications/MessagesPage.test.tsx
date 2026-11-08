import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/api/communications';
import type { NotificationDispatchView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { renderScreen as render } from '@/test/renderScreen';
import { MessagesPage } from './MessagesPage';

vi.mock('@/api/communications');
vi.mock('@/components/PartyName', () => ({ PartyName: () => <span>Asha Juma</span> }));

const failed: NotificationDispatchView = {
  dispatchId: 'd-1', partyId: 'p-1', policyNumber: 'POL-3E440FE9', templateKey: 'COVER_STARTED', channel: 'SMS',
  status: 'FAILED', failureReason: 'SMS gateway refused the message: REJECTED', createdAt: '2026-11-08T08:00:00Z',
  body: 'Bima POL-3E440FE9 imeanza.',
} as NotificationDispatchView;

/** 2026-11-08, the user: at 10,000 customers the outbox is a million rows a year. */
describe('MessagesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.getChannels).mockResolvedValue([{ channel: 'SMS', mode: 'LIVE' }, { channel: 'EMAIL', mode: 'OFF' }]);
    vi.mocked(api.getDispatchSummary).mockResolvedValue({
      sinceDays: 7,
      counts: [{ channel: 'SMS', status: 'SENT', count: 1240 }, { channel: 'SMS', status: 'FAILED', count: 12 }],
      reasons: [{ channel: 'SMS', status: 'FAILED', reason: 'SMS gateway refused the message: REJECTED', count: 12 }],
      byMessage: [{ templateKey: 'PAYMENT_RECEIVED', count: 1100 }, { templateKey: 'COVER_STARTED', count: 152 }],
    });
    vi.mocked(api.searchDispatches).mockResolvedValue([failed]);
  });

  it('opens on the last week with a summary, and shows a message’s exact text and why it failed', async () => {
    grantAccess('communications.view');
    const user = userEvent.setup();
    render(<MessagesPage />, { route: '/staff/notifications/messages' });

    expect(await screen.findByText('1,240')).toBeInTheDocument();
    expect(screen.getByText(/Email:/).parentElement).toHaveTextContent('not in use — customers are reached by SMS');
    expect(api.searchDispatches).toHaveBeenCalledWith(expect.objectContaining({ sinceDays: 7, limit: 200 }));

    await user.click(await screen.findByRole('button', { name: /Cover started/ }));
    expect(screen.getByText('Bima POL-3E440FE9 imeanza.')).toBeInTheDocument();
    expect(screen.getByText(/Why it failed:/).parentElement).toHaveTextContent('REJECTED');
    expect(screen.queryByRole('button', { name: 'Send again' })).not.toBeInTheDocument();
  });

  it('lets an administrator send a failed message again', async () => {
    grantAccess('communications.view', 'communications.templates');
    vi.mocked(api.resendDispatch).mockResolvedValue({ ...failed, dispatchId: 'd-2', status: 'SENT', failureReason: null } as never);
    const user = userEvent.setup();
    render(<MessagesPage />, { route: '/staff/notifications/messages' });

    await user.click(await screen.findByRole('button', { name: /Cover started/ }));
    await user.click(screen.getByRole('button', { name: 'Send again' }));
    await waitFor(() => expect(api.resendDispatch).toHaveBeenCalledWith('d-1'));
    expect(await screen.findByText(/Sent again:/)).toBeInTheDocument();
  });
});
