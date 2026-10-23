import { get } from '@/lib/http';
import type { components } from '@/types/api/dashboard';

/**
 * The role dashboards (2026-10-14). One read per dashboard, composed server-side from the modules that own each
 * figure -- the browser never fans out to the underlying searches or adds anything up. A figure that could not be
 * read is null and renders "Not available"; it is never 0.
 *
 * Since 2026-10-23 each read takes a range of months. The server answers with the months it covered, the same months
 * a year earlier, and every total, share and average over them.
 */

export type UnderwritingDashboard = components['schemas']['UnderwritingDashboardView'];
export type ClaimsDashboard = components['schemas']['ClaimsDashboardView'];
export type FinanceDashboard = components['schemas']['FinanceDashboardView'];
export type AgentDashboard = components['schemas']['AgentDashboardView'];
export type ActivityItem = components['schemas']['ActivityItem'];

// Phase 2's aggregates. Each is the dashboard's own shape, not the owning module's -- see the view records.
export type DecisionMonth = components['schemas']['DecisionMonth'];
export type SettlementMonth = components['schemas']['SettlementMonth'];
export type DecisionSeriesPoint = components['schemas']['DecisionSeriesPoint'];
export type ReceivablesAging = components['schemas']['ReceivablesAging'];
export type Period = components['schemas']['Period'];
export type Share = components['schemas']['Share'];
export type CountPoint = components['schemas']['CountPoint'];
export type DaysPoint = components['schemas']['DaysPoint'];
export type MonthPoint = components['schemas']['MonthPoint'];

/** Two months, "YYYY-MM", inclusive. Both absent asks for the year to date. */
export interface MonthRange {
  from?: string;
  to?: string;
}

const params = (range?: MonthRange) => ({ params: { from: range?.from, to: range?.to } });

export function getUnderwritingDashboard(range?: MonthRange): Promise<UnderwritingDashboard> {
  return get<UnderwritingDashboard>('/dashboards/underwriting', params(range));
}

export function getClaimsDashboard(range?: MonthRange): Promise<ClaimsDashboard> {
  return get<ClaimsDashboard>('/dashboards/claims', params(range));
}

export function getFinanceDashboard(range?: MonthRange): Promise<FinanceDashboard> {
  return get<FinanceDashboard>('/dashboards/finance', params(range));
}

export function getAgentDashboard(range?: MonthRange): Promise<AgentDashboard> {
  return get<AgentDashboard>('/dashboards/agent', params(range));
}
