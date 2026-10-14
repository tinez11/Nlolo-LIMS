import { get } from '@/lib/http';
import type { components } from '@/types/api/dashboard';

/**
 * The role dashboards (2026-10-14). One read per dashboard, composed server-side from the modules that own each
 * figure -- the browser never fans out to the underlying searches or adds anything up. A figure that could not be
 * read is null and renders "Not available"; it is never 0.
 */

export type UnderwritingDashboard = components['schemas']['UnderwritingDashboardView'];
export type ClaimsDashboard = components['schemas']['ClaimsDashboardView'];
export type FinanceDashboard = components['schemas']['FinanceDashboardView'];
export type AgentDashboard = components['schemas']['AgentDashboardView'];
export type ActivityItem = components['schemas']['ActivityItem'];

export function getUnderwritingDashboard(): Promise<UnderwritingDashboard> {
  return get<UnderwritingDashboard>('/dashboards/underwriting');
}

export function getClaimsDashboard(): Promise<ClaimsDashboard> {
  return get<ClaimsDashboard>('/dashboards/claims');
}

export function getFinanceDashboard(): Promise<FinanceDashboard> {
  return get<FinanceDashboard>('/dashboards/finance');
}

export function getAgentDashboard(): Promise<AgentDashboard> {
  return get<AgentDashboard>('/dashboards/agent');
}
