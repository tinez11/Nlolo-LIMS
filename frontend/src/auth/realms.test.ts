import { describe, expect, it } from 'vitest';
import { ISSUER, realmForPath, workspaceHeaderFor, workspacesOf } from './realms';

describe('one realm', () => {
  it('has one issuer', () => {
    expect(ISSUER).toMatch(/\/realms\/lifeplatform$/);
  });
});

describe('workspacesOf', () => {
  it('reads the workspace roles, in console order, ignoring job roles', () => {
    expect(workspacesOf(['customer', 'ADMIN', 'agent', 'offline_access'])).toEqual(['agents', 'customers']);
  });

  it('is empty for an account with no workspace', () => {
    expect(workspacesOf(['UNDERWRITER'])).toEqual([]);
  });
});

describe('realmForPath / workspaceHeaderFor', () => {
  it('reads the workspace from the first path segment', () => {
    expect(realmForPath('/agents/book')).toBe('agents');
    expect(workspaceHeaderFor('/customers')).toBe('customer');
    expect(workspaceHeaderFor('/staff/policies/P-1')).toBe('staff');
  });

  it('has none outside a workspace', () => {
    expect(realmForPath('/')).toBeNull();
    expect(workspaceHeaderFor('/quote/abc')).toBeNull();
    expect(realmForPath('/staffing')).toBeNull();
  });
});
