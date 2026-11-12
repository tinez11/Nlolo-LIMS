import { canAny, can } from '@/auth/access';
import type { readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import type { DashboardKind } from '@/store/dashboardStore';

/**
 * Which dashboards this identity gets, in the business order the sidebar follows (new business, claims, finance).
 *
 * Each condition is the SAME permission as its endpoint's @PreAuthorize in DashboardController (2026-10-26):
 *   overview      overview.view (2026-11-12, analytics plan 5; first: it is the whole business)
 *   underwriting  underwriting.assess or underwriting.decide
 *   claims        claims.assess or claims.settle
 *   finance       ledger.view
 *   agent         hasRole('REALM_AGENTS')
 * Change one and the other must change with it.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function dashboardsFor(realm: Realm, _identity?: ReturnType<typeof readIdentity>): DashboardKind[] {
  if (realm === 'agents') return ['agent'];
  if (realm !== 'staff') return [];
  const kinds: DashboardKind[] = [];
  if (can('overview.view')) kinds.push('overview');
  if (canAny('underwriting.assess', 'underwriting.decide')) kinds.push('underwriting');
  if (canAny('claims.assess', 'claims.settle')) kinds.push('claims');
  if (can('ledger.view')) kinds.push('finance');
  return kinds;
}

export const DASHBOARD_LABEL: Record<DashboardKind, string> = {
  overview: 'Overview',
  underwriting: 'Underwriting',
  claims: 'Claims',
  finance: 'Finance',
  agent: 'My business',
};
