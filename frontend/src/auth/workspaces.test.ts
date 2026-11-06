import { describe, expect, it } from 'vitest';
import { availableWorkspaces } from './workspaces';

describe('availableWorkspaces', () => {
  it('offers only workspaces the account holds AND the console has screens for', () => {
    // regulators has no screens yet (screens.tsx), so a regulator is offered nothing
    expect(availableWorkspaces(['regulator', 'TIRA_READ_ONLY'])).toEqual([]);
  });

  it('offers agent and policyholder to an account holding both', () => {
    expect(availableWorkspaces(['agent', 'customer'])).toEqual(['agents', 'customers']);
  });

  it('offers staff alone to a staff account, whatever its job roles', () => {
    expect(availableWorkspaces(['staff', 'ADMIN', 'UNDERWRITER'])).toEqual(['staff']);
  });
});
