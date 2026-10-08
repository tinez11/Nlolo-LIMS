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
      temporaryPassword: 'Kp7mWx3qRt9a',
    });
    render(<PortalAccessPanel partyId="p-1" />);

    expect(await screen.findByText('Not invited')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Invite to portal' }));

    expect(await screen.findByText('Kp7mWx3qRt9a')).toBeInTheDocument();
    expect(screen.getByText(/shown once/)).toBeInTheDocument();
    expect(await screen.findByText('Invited — not signed in yet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New one-time password' })).toBeInTheDocument();
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
