import { describe, expect, it } from 'vitest';
import { linkFor } from './reportLinks';

describe('linkFor', () => {
  it('opens a policy by the number in its cell', () => {
    expect(linkFor('POLICY', 'policyNumber', { policyNumber: 'POL-1' })).toBe('/staff/policies/POL-1');
  });

  it('opens a client by the hidden id, never by the name in the cell', () => {
    expect(linkFor('CLIENT', 'client', { client: 'Asha Juma', _clientId: 'party-1' })).toBe('/staff/parties/party-1');
    expect(linkFor('CLIENT', 'client', { client: 'Asha Juma' })).toBeNull();
  });

  it('opens an agent by the hidden id', () => {
    expect(linkFor('AGENT', 'agent', { agent: 'Juma Senior (AG-1)', _agentId: 'agent-1' })).toBe('/staff/agents/agent-1');
  });

  it('leaves a total row, an empty cell and a plain column unlinked', () => {
    expect(linkFor('POLICY', 'policy', { policy: 'Total' })).toBeNull();
    expect(linkFor('POLICY', 'policyNumber', { policyNumber: '' })).toBeNull();
    expect(linkFor(null, 'policyNumber', { policyNumber: 'POL-1' })).toBeNull();
  });
});
