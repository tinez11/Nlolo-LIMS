import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as distributionApi from '@/api/distribution';
import * as partyApi from '@/api/party';
import type { AgentView, PartyView } from '@/api/types';
import { AgentName, __clearAgentNameCache } from './AgentName';
import { __clearPartyNameCache } from './PartyName';

vi.mock('@/api/distribution');
vi.mock('@/api/party');

/**
 * Resolving a distribution agent id to the agent's name.
 *
 * ## What this is defending
 *
 * `agentOfRecordId` was printed raw everywhere it appeared -- a policy's rail,
 * the policy drawer, the field receipts register -- and `PartyName` could not fix
 * it, because an agent id is not a party id. An agent has no name of its own in
 * the distribution module at all: it has a licence number and a `partyId`, and
 * the name is on the party record. So this makes two hops.
 *
 * Which means two ways to be wrong. Passing an agent id to the party lookup would
 * 404 and fall back to printing the id, looking exactly like no lookup at all --
 * so the first test asserts WHICH id went where. And `GET /agents/{id}` is closed
 * to the customers realm and force-scoped in the agents realm, so a 403 has to
 * degrade quietly rather than blank the field.
 */

const AGENT_ID = '9b3fdd70-69d2-4e4f-a0eb-67c9fe883921';
const PARTY_ID = 'd9937444-3873-4336-9cb7-addb486f3e1b';

const seniorAgent: AgentView = {
  agentId: AGENT_ID,
  partyId: PARTY_ID,
  licenseNumber: 'LIC-0001',
  licenseStatus: 'ACTIVE',
  licenseExpiryDate: '2030-01-01',
};

const agentParty: PartyView = {
  partyId: PARTY_ID,
  partyType: 'INDIVIDUAL',
  kycStatus: 'VERIFIED',
  displayName: 'Juma Senior',
};

beforeEach(() => {
  vi.clearAllMocks();
  __clearAgentNameCache();
  __clearPartyNameCache();
});

describe('AgentName', () => {
  it('resolves agent -> party -> name, passing each id to its own lookup', async () => {
    vi.mocked(distributionApi.getAgent).mockResolvedValue(seniorAgent);
    vi.mocked(partyApi.getParty).mockResolvedValue(agentParty);

    render(<AgentName agentId={AGENT_ID} />);

    expect(await screen.findByText('Juma Senior')).toBeVisible();
    expect(screen.getByText('LIC-0001')).toBeVisible();
    // The hop that would otherwise fail silently: the party lookup must be given
    // the PARTY id off the profile, never the agent id it started from.
    expect(distributionApi.getAgent).toHaveBeenCalledWith(AGENT_ID);
    expect(partyApi.getParty).toHaveBeenCalledWith(PARTY_ID);
  });

  it('drops the licence number where space is tight', async () => {
    vi.mocked(distributionApi.getAgent).mockResolvedValue(seniorAgent);
    vi.mocked(partyApi.getParty).mockResolvedValue(agentParty);

    render(<AgentName agentId={AGENT_ID} withLicense={false} />);

    expect(await screen.findByText('Juma Senior')).toBeVisible();
    expect(screen.queryByText('LIC-0001')).not.toBeInTheDocument();
  });

  it('falls back to the licence number when the profile carries no party', async () => {
    // The cast is the point: `partyId` is REQUIRED and non-nullable in the OpenAPI
    // contract, so this state is a contract violation the type system will not express.
    // The branch exists anyway -- without it a violated contract renders an empty field
    // where a licence number was available, which is worse than either honest answer.
    vi.mocked(distributionApi.getAgent).mockResolvedValue({
      ...seniorAgent,
      partyId: undefined,
    } as unknown as AgentView);

    render(<AgentName agentId={AGENT_ID} />);

    expect(await screen.findByText('LIC-0001')).toBeVisible();
    expect(partyApi.getParty).not.toHaveBeenCalled();
  });

  it('falls back to the raw id when the agent lookup is refused', async () => {
    vi.mocked(distributionApi.getAgent).mockRejectedValue(new Error('403'));

    render(<AgentName agentId={AGENT_ID} />);

    expect(screen.getByText(AGENT_ID)).toBeVisible();
    await waitFor(() => expect(distributionApi.getAgent).toHaveBeenCalledTimes(1));
    expect(screen.getByText(AGENT_ID)).toBeVisible();
  });

  it('costs one pair of requests for a register of rows from one agent', async () => {
    vi.mocked(distributionApi.getAgent).mockResolvedValue(seniorAgent);
    vi.mocked(partyApi.getParty).mockResolvedValue(agentParty);

    render(
      <>
        {Array.from({ length: 20 }, (_, i) => (
          <AgentName key={i} agentId={AGENT_ID} />
        ))}
      </>,
    );

    await waitFor(() => expect(screen.getAllByText('Juma Senior')).toHaveLength(20));
    expect(distributionApi.getAgent).toHaveBeenCalledTimes(1);
    expect(partyApi.getParty).toHaveBeenCalledTimes(1);
  });
});
