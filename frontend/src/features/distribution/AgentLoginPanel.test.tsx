import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as distributionApi from '@/api/distribution';
import type { AgentLoginView } from '@/api/distribution';
import { grantRoles } from '@/test/access';
import { AgentLoginPanel } from './AgentLoginPanel';

vi.mock('@/api/distribution');
vi.mock('@/api/access');

const login = (over: Partial<AgentLoginView>): AgentLoginView => ({
  agentId: 'a-1', status: 'NONE', username: null, delivery: null, createdBy: null, createdAt: null,
  passwordSentAt: null, closedBy: null, closedAt: null, ...over,
});
const open = login({ status: 'OPEN', username: '+255712345681', delivery: 'SMS_PASSWORD', createdBy: 'finance-one',
  createdAt: '2026-10-28T08:00:00Z', passwordSentAt: '2026-10-28T08:00:00Z' });

beforeEach(() => {
  vi.clearAllMocks();
  grantRoles('FINANCE_OFFICER');
});

describe('AgentLoginPanel', () => {
  it('says an agent with no login cannot sign in, and gives one by text', async () => {
    vi.mocked(distributionApi.getAgentLogin).mockResolvedValueOnce(login({})).mockResolvedValue(open);
    vi.mocked(distributionApi.giveAgentLogin).mockResolvedValue({
      login: open, temporaryPassword: null, smsSentTo: '+255712345681', notice: null,
    });
    render(<AgentLoginPanel agentId="a-1" />);

    expect(await screen.findByText('No login yet — this agent cannot sign in')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Give a login' }));

    expect(await screen.findByText('One-time password sent by text to +255712345681.')).toBeInTheDocument();
    expect(await screen.findByText('Can sign in to the agent console')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send a new password' })).toBeInTheDocument();
    expect(distributionApi.giveAgentLogin).toHaveBeenCalledWith('a-1');
  });

  it('shows the one-time password once when it could not be texted', async () => {
    vi.mocked(distributionApi.getAgentLogin).mockResolvedValueOnce(login({})).mockResolvedValue(open);
    vi.mocked(distributionApi.giveAgentLogin).mockResolvedValue({
      login: open, temporaryPassword: 'Kp7mWx3qRt9a', smsSentTo: null,
      notice: 'The text was not sent (gateway down). Give the agent this password yourself.',
    });
    render(<AgentLoginPanel agentId="a-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Give a login' }));
    expect(await screen.findByText('Kp7mWx3qRt9a')).toBeInTheDocument();
  });

  it('closes a login only after a second click that says it signs them out', async () => {
    vi.mocked(distributionApi.getAgentLogin).mockResolvedValue(open);
    vi.mocked(distributionApi.closeAgentLogin).mockResolvedValue(login({ status: 'CLOSED' }));
    render(<AgentLoginPanel agentId="a-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Close the login' }));
    expect(distributionApi.closeAgentLogin).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm: close and sign them out' }));
    expect(distributionApi.closeAgentLogin).toHaveBeenCalledWith('a-1');
  });

  it('offers nothing to someone who cannot manage agents', async () => {
    grantRoles('UNDERWRITER');
    vi.mocked(distributionApi.getAgentLogin).mockResolvedValue(login({}));
    render(<AgentLoginPanel agentId="a-1" />);
    expect(await screen.findByText('Someone who manages agents can give this agent a login.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Give a login' })).not.toBeInTheDocument();
  });
});
