import { staffRoles, type readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import type { DashboardKind } from '@/store/dashboardStore';

/**
 * Which dashboards this identity gets, in the business order the sidebar follows (new business, claims, finance).
 *
 * Each condition is the SAME expression as its endpoint's @PreAuthorize in DashboardController:
 *   underwriting  hasRole('UNDERWRITER')
 *   claims        hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER')
 *   finance       hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
 *   agent         hasRole('REALM_AGENTS')
 * Change one and the other must change with it.
 */
export function dashboardsFor(realm: Realm, identity: ReturnType<typeof readIdentity>): DashboardKind[] {
  if (realm === 'agents') return ['agent'];
  if (realm !== 'staff') return [];
  const roles = staffRoles(identity);
  const kinds: DashboardKind[] = [];
  if (roles.UNDERWRITER) kinds.push('underwriting');
  if (roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER) kinds.push('claims');
  if (roles.FINANCE_OFFICER || roles.ADMIN) kinds.push('finance');
  return kinds;
}

export const DASHBOARD_LABEL: Record<DashboardKind, string> = {
  underwriting: 'Underwriting',
  claims: 'Claims',
  finance: 'Finance',
  agent: 'My business',
};
