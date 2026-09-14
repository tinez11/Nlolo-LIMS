import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as distributionApi from '@/api/distribution';
import * as partyApi from '@/api/party';
import type { AgentView, PartyView } from '@/api/types';
import { __clearAgentNameCache } from './AgentName';
import { __clearPartyNameCache } from './PartyName';
import { AgentPicker } from './AgentPicker';

vi.mock('@/api/distribution');
vi.mock('@/api/party');

const JUMA_AGENT_ID = 'a96776cb-198e-4298-ac43-1c2b459448a5';
const JUMA_PARTY_ID = '80681a28-abe4-4646-80ca-209b1ee95886';

const jumaSenior: AgentView = {
  agentId: JUMA_AGENT_ID,
  partyId: JUMA_PARTY_ID,
  licenseNumber: 'LIC-SENIOR-001',
  licenseStatus: 'ACTIVE',
  licenseExpiryDate: '2030-01-01',
};

const jumaParty: PartyView = {
  partyId: JUMA_PARTY_ID,
  partyType: 'INDIVIDUAL',
  kycStatus: 'VERIFIED',
  displayName: 'Juma Senior',
};

beforeEach(() => {
  vi.clearAllMocks();
  // Both name resolvers cache at module level, so without this a spec inherits
  // whatever the previous one resolved.
  __clearAgentNameCache();
  __clearPartyNameCache();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AgentPicker', () => {
  it('does not search below the minimum query length', async () => {
    const listSpy = vi.spyOn(distributionApi, 'listAgents');
    const user = userEvent.setup();
    render(<AgentPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search agents by name or licence/i }));
    await user.type(screen.getByPlaceholderText('Type a name or licence number'), 'J');

    expect(screen.getByText('Type a name or licence number to search')).toBeInTheDocument();
    expect(listSpy).not.toHaveBeenCalled();
  });

  /**
   * The whole point of the control. `q` matching a NAME is a backend capability this
   * picker depends on -- before it, `q` matched licence numbers only and searching for a
   * person by name returned nothing.
   */
  it('searches by name and lists the agent under their person name, not their id', async () => {
    vi.spyOn(distributionApi, 'listAgents').mockResolvedValue({
      items: [jumaSenior],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    vi.spyOn(partyApi, 'getParty').mockResolvedValue(jumaParty);
    const user = userEvent.setup();
    render(<AgentPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search agents by name or licence/i }));
    await user.type(screen.getByPlaceholderText('Type a name or licence number'), 'Juma');

    await waitFor(() => expect(screen.getByText('Juma Senior')).toBeInTheDocument(), {
      timeout: 2000,
    });
    expect(distributionApi.listAgents).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'Juma', pageSize: 10 }),
    );
    // The licence is shown beside the name: two agents can share a name, and the
    // licence is what an operator checks against their own paperwork.
    expect(screen.getByText('LIC-SENIOR-001')).toBeInTheDocument();
  });

  it('reports the agent id, not the party id, when one is chosen', async () => {
    vi.spyOn(distributionApi, 'listAgents').mockResolvedValue({
      items: [jumaSenior],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    vi.spyOn(partyApi, 'getParty').mockResolvedValue(jumaParty);
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<AgentPicker value={null} onChange={onChange} />);

    await user.click(screen.getByRole('button', { name: /search agents by name or licence/i }));
    await user.type(screen.getByPlaceholderText('Type a name or licence number'), 'Juma');
    await waitFor(() => expect(screen.getByText('Juma Senior')).toBeInTheDocument(), {
      timeout: 2000,
    });
    await user.click(screen.getByText('Juma Senior'));

    // An agent id and a party id are different things, and passing the wrong one
    // is exactly the kind of mistake this control exists to stop.
    expect(onChange).toHaveBeenCalledWith(JUMA_AGENT_ID, jumaSenior);
  });

  it('resolves a pasted uuid through the direct id lookup, never the text search', async () => {
    // A uuid can never match a name or a licence number, so a text search would
    // return nothing. Proving getAgent is used and listAgents is not is the only
    // way to distinguish the two paths.
    vi.spyOn(distributionApi, 'getAgent').mockResolvedValue(jumaSenior);
    const listSpy = vi.spyOn(distributionApi, 'listAgents');
    vi.spyOn(partyApi, 'getParty').mockResolvedValue(jumaParty);
    const user = userEvent.setup();
    render(<AgentPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search agents by name or licence/i }));
    // PASTED, not typed, and that is what makes this assertion trustworthy rather than
    // load-dependent. `user.type` enters 36 characters one at a time, and every prefix of a
    // uuid is not a uuid -- so on a busy machine the 300ms debounce can elapse mid-uuid and
    // fire a text search for a partial id, failing the `not.toHaveBeenCalled` below for a
    // reason that has nothing to do with the behaviour under test. Pasting is also what a
    // person actually does with an id.
    await user.click(screen.getByPlaceholderText('Type a name or licence number'));
    await user.paste(JUMA_AGENT_ID);

    await waitFor(() => expect(screen.getByText('Juma Senior')).toBeInTheDocument(), {
      timeout: 2000,
    });
    expect(distributionApi.getAgent).toHaveBeenCalledWith(JUMA_AGENT_ID);
    expect(listSpy).not.toHaveBeenCalled();
  });

  it('says nothing matched rather than failing when a search finds no agent', async () => {
    vi.spyOn(distributionApi, 'listAgents').mockResolvedValue({
      items: [],
      page: { page: 0, pageSize: 10, totalElements: 0 },
    });
    const user = userEvent.setup();
    render(<AgentPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search agents by name or licence/i }));
    await user.type(screen.getByPlaceholderText('Type a name or licence number'), 'Nobody');

    await waitFor(() => expect(screen.getByText(/no agents match/i)).toBeInTheDocument(), {
      timeout: 2000,
    });
  });

  it('renders an already-selected agent by name, so an existing value is never a bare uuid', async () => {
    vi.spyOn(distributionApi, 'getAgent').mockResolvedValue(jumaSenior);
    vi.spyOn(partyApi, 'getParty').mockResolvedValue(jumaParty);

    render(<AgentPicker value={JUMA_AGENT_ID} onChange={vi.fn()} />);

    await waitFor(() => expect(screen.getByText('Juma Senior')).toBeInTheDocument());
  });
});
