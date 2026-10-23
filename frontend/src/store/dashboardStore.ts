import { create } from 'zustand';
import {
  getAgentDashboard,
  getClaimsDashboard,
  getFinanceDashboard,
  getUnderwritingDashboard,
  type AgentDashboard,
  type ClaimsDashboard,
  type FinanceDashboard,
  type MonthRange,
  type UnderwritingDashboard,
} from '@/api/dashboard';
import { idle, track, type Resource } from './createResourceSlice';

export type DashboardKind = 'underwriting' | 'claims' | 'finance' | 'agent';

/**
 * One slot per dashboard rather than one shared slot: a person holding two roles switches tabs, and the tab they
 * leave must not hand its figures to the tab they open while the next read is in flight.
 *
 * A range change re-reads the same slot. `track` drops a read that a newer one overtook, so clicking quickly through
 * the ranges never lands on an older answer.
 */
interface DashboardState {
  underwriting: Resource<UnderwritingDashboard>;
  claims: Resource<ClaimsDashboard>;
  finance: Resource<FinanceDashboard>;
  agent: Resource<AgentDashboard>;
  load: (kind: DashboardKind, range?: MonthRange) => Promise<void>;
}

export const useDashboardStore = create<DashboardState>((set, getState) => ({
  underwriting: idle(),
  claims: idle(),
  finance: idle(),
  agent: idle(),

  load: (kind, range) => {
    switch (kind) {
      case 'underwriting':
        return track('dashboard.underwriting', getState().underwriting, (next) => set({ underwriting: next }),
          () => getUnderwritingDashboard(range));
      case 'claims':
        return track('dashboard.claims', getState().claims, (next) => set({ claims: next }),
          () => getClaimsDashboard(range));
      case 'finance':
        return track('dashboard.finance', getState().finance, (next) => set({ finance: next }),
          () => getFinanceDashboard(range));
      case 'agent':
        return track('dashboard.agent', getState().agent, (next) => set({ agent: next }),
          () => getAgentDashboard(range));
    }
  },
}));

export const selectUnderwriting = (s: DashboardState) => s.underwriting;
export const selectClaims = (s: DashboardState) => s.claims;
export const selectFinance = (s: DashboardState) => s.finance;
export const selectAgent = (s: DashboardState) => s.agent;
