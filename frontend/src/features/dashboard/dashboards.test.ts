import { describe, expect, it } from 'vitest';
import type { readIdentity } from '@/auth/claims';
import { grantRoles } from '@/test/access';
import { dashboardsFor } from './dashboards';

/** Signs in holding these built-in roles; dashboards follow permissions now (2026-10-26), not the token. */
const as = (...roles: string[]) => {
  grantRoles(...roles.filter((r) => !r.startsWith('REALM_')));
  return { roles } as unknown as ReturnType<typeof readIdentity>;
};

/**
 * Each tab mirrors its endpoint's own permission exactly. A tab the endpoint refuses would be a 403 dressed as a
 * page; a tab missing for a role the endpoint admits would hide a dashboard somebody is entitled to.
 */
describe('dashboardsFor', () => {
  it('gives each staff role its own dashboard, in the business order', () => {
    expect(dashboardsFor('staff', as('REALM_STAFF', 'UNDERWRITER'))).toEqual(['underwriting']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'CLAIMS_ASSESSOR'))).toEqual(['claims']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'CLAIMS_MANAGER'))).toEqual(['claims']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'FINANCE_OFFICER'))).toEqual(['finance']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'ADMIN'))).toEqual(['finance']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'UNDERWRITER', 'FINANCE_OFFICER', 'CLAIMS_MANAGER')))
      .toEqual(['underwriting', 'claims', 'finance']);
  });

  /** A customer service rep holds none of these roles, and is given no empty dashboard. */
  it('gives a role with nothing to show no dashboard', () => {
    expect(dashboardsFor('staff', as('REALM_STAFF', 'CUSTOMER_SERVICE_REP'))).toEqual([]);
  });

  it('gives every agent the agent dashboard', () => {
    expect(dashboardsFor('agents', as('REALM_AGENTS'))).toEqual(['agent']);
  });
});
