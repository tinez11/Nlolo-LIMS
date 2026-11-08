import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as distributionApi from '@/api/distribution';
import { ReportsTo } from './AgentProfilePage';

vi.mock('@/api/distribution');

beforeEach(() => vi.clearAllMocks());

/** 2026-11-08, the user: "No drill-in yet — your supervisor's own profile is not reachable from here". */
describe('Reports to, on the agent’s own profile', () => {
  it('names the supervisor, with their licence and a phone to call', async () => {
    vi.mocked(distributionApi.getOwnSupervisor).mockResolvedValue({
      agentId: 'sup-1', displayName: 'Juma Senior', licenseNumber: 'LIC-0042', phoneNumber: '+255712000001',
    });
    render(<dl><ReportsTo supervisorId="sup-1" /></dl>);

    expect(await screen.findByText('Juma Senior')).toBeInTheDocument();
    expect(screen.getByText('LIC-0042')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '+255712000001' })).toHaveAttribute('href', 'tel:+255712000001');
    expect(screen.queryByText('sup-1')).not.toBeInTheDocument();
  });

  it('never falls back to the raw id when the name cannot be loaded', async () => {
    vi.mocked(distributionApi.getOwnSupervisor).mockRejectedValue(new Error('offline'));
    render(<dl><ReportsTo supervisorId="sup-1" /></dl>);

    expect(await screen.findByText('Their name could not be loaded just now')).toBeInTheDocument();
    expect(screen.queryByText('sup-1')).not.toBeInTheDocument();
  });
});
