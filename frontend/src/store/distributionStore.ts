import { create } from 'zustand';
import {
  createCommissionPlan,
  getAgent,
  getApplicablePlan,
  listAccruals,
  listCommissionStatements,
  onboardAgent,
  reactivateAgent,
  requestPayout,
  suspendAgent,
} from '@/api/distribution';
import type {
  AgentView,
  CommissionAccrualView,
  CommissionPlanView,
  CommissionStatementView,
  CreateCommissionPlanRequest,
  OnboardAgentRequest,
  RequestPayoutRequest,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, success, track, type Resource } from './createResourceSlice';

/**
 * The `distribution` domain store.
 */

type Keyed<T> = Record<string, Resource<T>>;

const planKey = (agentId: string, productId: string) => `${agentId}:${productId}`;
const statementKey = (agentId: string, statementId: string) => `${agentId}:${statementId}`;

interface DistributionState {
  // A single slot, not keyed: onboarding makes a NEW agent, so there is no
  // existing id to key against yet -- same shape as underwriting's `opening`.
  onboarding: Resource<AgentView>;
  agents: Keyed<AgentView>;
  // Keyed by `${agentId}:${productId}` -- a plan is resolved per (agent,
  // product) pair, never by its own id (no `GET /commission-plans/{id}`).
  plans: Keyed<CommissionPlanView>;
  // A single slot: a plan hangs off a PRODUCT, not an agent (Di1's own
  // aggregate boundary) -- there is no existing agent-scoped slot to key
  // creation against.
  creatingPlan: Resource<CommissionPlanView>;
  statements: Keyed<CommissionStatementView[]>;
  accruals: Keyed<CommissionAccrualView[]>;
  requestingPayout: Keyed<true>;
  // Each keyed by agentId, separately from `agents` and from each other --
  // same shape as policyStore's suspending/resuming/reinstating.
  suspendingAgent: Keyed<AgentView>;
  reactivatingAgent: Keyed<AgentView>;

  onboardAgent: (request: OnboardAgentRequest, attempt: MutationAttempt) => Promise<void>;
  resetOnboardAgent: () => void;
  loadAgent: (agentId: string) => Promise<void>;
  suspendAgent: (agentId: string) => Promise<void>;
  resetSuspendAgent: (agentId: string) => void;
  reactivateAgent: (agentId: string) => Promise<void>;
  resetReactivateAgent: (agentId: string) => void;
  loadApplicablePlan: (agentId: string, productId: string) => Promise<void>;
  createCommissionPlan: (
    agentId: string,
    request: CreateCommissionPlanRequest,
  ) => Promise<void>;
  resetCreateCommissionPlan: () => void;
  loadStatements: (agentId: string, period?: string) => Promise<void>;
  loadAccruals: (agentId: string, statementId: string) => Promise<void>;
  requestPayout: (
    agentId: string,
    statementId: string,
    request: RequestPayoutRequest,
    attempt: MutationAttempt,
  ) => Promise<void>;
  resetRequestPayout: (agentId: string, statementId: string) => void;
}

export const useDistributionStore = create<DistributionState>((set, getState) => ({
  onboarding: idle(),
  agents: {},
  plans: {},
  creatingPlan: idle(),
  statements: {},
  accruals: {},
  requestingPayout: {},
  suspendingAgent: {},
  reactivatingAgent: {},

  onboardAgent: (request, attempt) =>
    track(
      'distribution.onboard',
      getState().onboarding,
      (next) => set({ onboarding: next }),
      () => onboardAgent(request, attempt),
    ),

  resetOnboardAgent: () => set({ onboarding: idle() }),

  loadAgent: (agentId) =>
    track(
      `distribution.agent.${agentId}`,
      getState().agents[agentId] ?? idle<AgentView>(),
      (next) => set((s) => ({ agents: { ...s.agents, [agentId]: next } })),
      () => getAgent(agentId),
    ),

  suspendAgent: (agentId) =>
    track(
      `distribution.suspendAgent.${agentId}`,
      getState().suspendingAgent[agentId] ?? idle<AgentView>(),
      (next) => set((s) => ({ suspendingAgent: { ...s.suspendingAgent, [agentId]: next } })),
      async () => {
        const updated = await suspendAgent(agentId);
        set((s) => ({ agents: { ...s.agents, [agentId]: success(updated) } }));
        return updated;
      },
    ),

  resetSuspendAgent: (agentId) =>
    set((s) => {
      if (!(agentId in s.suspendingAgent)) return s;
      const { [agentId]: _discard, ...rest } = s.suspendingAgent;
      return { suspendingAgent: rest };
    }),

  reactivateAgent: (agentId) =>
    track(
      `distribution.reactivateAgent.${agentId}`,
      getState().reactivatingAgent[agentId] ?? idle<AgentView>(),
      (next) => set((s) => ({ reactivatingAgent: { ...s.reactivatingAgent, [agentId]: next } })),
      async () => {
        const updated = await reactivateAgent(agentId);
        set((s) => ({ agents: { ...s.agents, [agentId]: success(updated) } }));
        return updated;
      },
    ),

  resetReactivateAgent: (agentId) =>
    set((s) => {
      if (!(agentId in s.reactivatingAgent)) return s;
      const { [agentId]: _discard, ...rest } = s.reactivatingAgent;
      return { reactivatingAgent: rest };
    }),

  loadApplicablePlan: (agentId, productId) =>
    track(
      `distribution.plan.${planKey(agentId, productId)}`,
      getState().plans[planKey(agentId, productId)] ?? idle<CommissionPlanView>(),
      (next) =>
        set((s) => ({ plans: { ...s.plans, [planKey(agentId, productId)]: next } })),
      () => getApplicablePlan(agentId, productId),
    ),

  // `agentId` is context for which plan-lookup slot to refresh, not part of
  // the request itself -- createCommissionPlan has no agentId in its path or
  // body (a plan hangs off a product, not an agent).
  createCommissionPlan: (agentId, request) =>
    track(
      'distribution.createPlan',
      getState().creatingPlan,
      (next) => set({ creatingPlan: next }),
      async () => {
        const view = await createCommissionPlan(request);
        // The response IS the newly-created plan, already exactly what
        // loadApplicablePlan would return for this (agent, product) pair --
        // write it straight in rather than firing a redundant GET.
        set((s) => ({
          plans: { ...s.plans, [planKey(agentId, request.productId)]: success(view) },
        }));
        return view;
      },
    ),

  resetCreateCommissionPlan: () => set({ creatingPlan: idle() }),

  loadStatements: (agentId, period) =>
    track(
      `distribution.statements.${agentId}.${period ?? ''}`,
      getState().statements[agentId] ?? idle<CommissionStatementView[]>(),
      (next) => set((s) => ({ statements: { ...s.statements, [agentId]: next } })),
      () => listCommissionStatements(agentId, period),
    ),

  loadAccruals: (agentId, statementId) =>
    track(
      `distribution.accruals.${statementKey(agentId, statementId)}`,
      getState().accruals[statementKey(agentId, statementId)] ?? idle<CommissionAccrualView[]>(),
      (next) =>
        set((s) => ({
          accruals: { ...s.accruals, [statementKey(agentId, statementId)]: next },
        })),
      () => listAccruals(agentId, statementId),
    ),

  requestPayout: (agentId, statementId, request, attempt) =>
    track(
      `distribution.payout.${statementKey(agentId, statementId)}`,
      getState().requestingPayout[statementKey(agentId, statementId)] ?? idle<true>(),
      (next) =>
        set((s) => ({
          requestingPayout: { ...s.requestingPayout, [statementKey(agentId, statementId)]: next },
        })),
      // Explicit Promise<true>: see productStore.publishVersion for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await requestPayout(agentId, statementId, request, attempt);
        // 202 with no body -- the statement's real new status (PAYOUT_REQUESTED)
        // is only visible via a fresh GET.
        await getState().loadStatements(agentId);
        return true;
      },
    ),

  resetRequestPayout: (agentId, statementId) =>
    set((s) => {
      const key = statementKey(agentId, statementId);
      if (!(key in s.requestingPayout)) return s;
      const { [key]: _discard, ...rest } = s.requestingPayout;
      return { requestingPayout: rest };
    }),
}));

export const selectAgent = (agentId: string) => (s: DistributionState) =>
  s.agents[agentId] ?? idle<AgentView>();
export const selectApplicablePlan = (agentId: string, productId: string) => (s: DistributionState) =>
  s.plans[planKey(agentId, productId)] ?? idle<CommissionPlanView>();
export const selectStatements = (agentId: string) => (s: DistributionState) =>
  s.statements[agentId] ?? idle<CommissionStatementView[]>();
export const selectAccruals = (agentId: string, statementId: string) => (s: DistributionState) =>
  s.accruals[statementKey(agentId, statementId)] ?? idle<CommissionAccrualView[]>();
export const selectRequestingPayout = (agentId: string, statementId: string) => (s: DistributionState) =>
  s.requestingPayout[statementKey(agentId, statementId)] ?? idle<true>();
export const selectSuspendingAgent = (agentId: string) => (s: DistributionState) =>
  s.suspendingAgent[agentId] ?? idle<AgentView>();
export const selectReactivatingAgent = (agentId: string) => (s: DistributionState) =>
  s.reactivatingAgent[agentId] ?? idle<AgentView>();
