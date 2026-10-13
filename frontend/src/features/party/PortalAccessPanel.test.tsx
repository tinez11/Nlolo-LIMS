import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as portalApi from '@/api/portal';
import type { PortalAccessView } from '@/api/portal';
import { PortalAccessPanel } from './PortalAccessPanel';

vi.mock('@/api/portal');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 't' } }) }));
let roles: string[] = [];
vi.mock('@/auth/claims', async (original) => ({
  ...(await original<typeof import('@/auth/claims')>()),
  readIdentity: () => ({ roles, subject: 's', partyId: null, tenantId: null, preferredUsername: null, name: null, email: null }),
}));

const access = (over: Partial<PortalAccessView>): PortalAccessView => ({
  partyId: 'p-1', status: 'NOT_INVITED', username: null, delivery: null, invitedBy: null, invitedAt: null,
  activatedAt: null, revokedBy: null, revokedAt: null, ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  roles = ['CUSTOMER_SERVICE_REP'];
});

describe('PortalAccessPanel', () => {
  it('invites a phone-only client and shows the one-time password once', async () => {
    vi.mocked(portalApi.getPortalAccess)
      .mockResolvedValueOnce(access({}))
      .mockResolvedValue(access({ status: 'INVITED', username: '+255715000001', delivery: 'TEMPORARY_PASSWORD',
        invitedBy: 'Rose Service', invitedAt: '2026-10-08T10:00:00Z' }));
    vi.mocked(portalApi.invitePortal).mockResolvedValue({
      access: access({ status: 'INVITED', username: '+255715000001', delivery: 'TEMPORARY_PASSWORD' }),
      temporaryPassword: 'Kp7mWx3qRt9a', smsSentTo: null, notice: null,
    });
    render(<PortalAccessPanel partyId="p-1" />);

    expect(await screen.findByText('Not invited')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Invite to portal' }));

    expect(await screen.findByText('Kp7mWx3qRt9a')).toBeInTheDocument();
    expect(screen.getByText(/shown once/)).toBeInTheDocument();
    expect(await screen.findByText('Invited — not signed in yet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New one-time password' })).toBeInTheDocument();
  });

  it('reports an SMS invite without showing any password', async () => {
    vi.mocked(portalApi.getPortalAccess)
      .mockResolvedValueOnce(access({}))
      .mockResolvedValue(access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }));
    vi.mocked(portalApi.invitePortal).mockResolvedValue({
      access: access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }),
      temporaryPassword: null, smsSentTo: '+255715000001', notice: null,
    });
    render(<PortalAccessPanel partyId="p-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Invite to portal' }));

    expect(await screen.findByText('One-time password sent by SMS to +255715000001')).toBeInTheDocument();
    expect(screen.queryByText(/shown once/)).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Send a new one-time password' })).toBeInTheDocument();
  });

  it('shows the password with the reason when the SMS failed', async () => {
    vi.mocked(portalApi.getPortalAccess)
      .mockResolvedValueOnce(access({}))
      .mockResolvedValue(access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }));
    vi.mocked(portalApi.invitePortal).mockResolvedValue({
      access: access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }),
      temporaryPassword: 'Kp7mWx3qRt9a', smsSentTo: null,
      notice: 'The SMS was not sent (gateway down). Give the client this password yourself.',
    });
    render(<PortalAccessPanel partyId="p-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Invite to portal' }));

    expect(await screen.findByText('Kp7mWx3qRt9a')).toBeInTheDocument();
    expect(screen.getByText('The SMS was not sent (gateway down). Give the client this password yourself.'))
      .toBeInTheDocument();
  });

  it('asks before revoking', async () => {
    vi.mocked(portalApi.getPortalAccess).mockResolvedValue(access({ status: 'ACTIVE', username: 'a@b.tz', delivery: 'EMAIL_LINK' }));
    vi.mocked(portalApi.revokePortal).mockResolvedValue(access({ status: 'REVOKED' }));
    render(<PortalAccessPanel partyId="p-1" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Revoke access' }));
    expect(portalApi.revokePortal).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm revoke' }));
    expect(portalApi.revokePortal).toHaveBeenCalledWith('p-1');
  });

  it('shows the access to other staff without the buttons', async () => {
    roles = ['UNDERWRITER'];
    vi.mocked(portalApi.getPortalAccess).mockResolvedValue(access({}));
    render(<PortalAccessPanel partyId="p-1" />);
    expect(await screen.findByText(/Customer service or an administrator can invite/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
