import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  AgentView,
  CommissionAccrualView,
  CommissionPlanView,
  CommissionStatementView,
  CreateCommissionPlanRequest,
  OnboardAgentRequest,
  RequestPayoutRequest,
} from './types';

/**
 * Distribution (agents/commissions) read/write surface.
 *
 * There is no `GET /agents` list or search endpoint anywhere on this platform
 * -- an agent is reachable only by an id you already hold: the response of
 * onboarding it, or a policy's own `agentOfRecordId` (surfaced on
 * `PolicyView`, unlike underwriting's `underwritingCaseId` which the wire DTO
 * drops entirely -- confirmed by reading `PolicyResponseDto` directly).
 */

/** `POST /agents` -- staff FINANCE_OFFICER/ADMIN only. Idempotency-Key is
 *  hard-required (a 400 without it) -- see lib/idempotency.ts's REQUIRED list. */
export function onboardAgent(
  request: OnboardAgentRequest,
  attempt: MutationAttempt,
): Promise<AgentView> {
  return post<AgentView>('/agents', request, { headers: attempt.headers() });
}

export function getAgent(agentId: string): Promise<AgentView> {
  return get<AgentView>(`/agents/${encodeURIComponent(agentId)}`);
}

/**
 * `GET /agents/me` -- agents-realm only. Resolves the caller's own agentId
 * from its `party_id` claim -- there is no `agentId` claim and no `GET
 * /agents` list, so this is the only way an agent discovers its own record.
 */
export function getOwnAgent(): Promise<AgentView> {
  return get<AgentView>('/agents/me');
}

/**
 * `POST /agents/{n}/suspend` -- staff FINANCE_OFFICER/ADMIN only.
 * `AgentProfile.setLicenseStatus` existed with no caller anywhere on the
 * platform until this endpoint. 409s unless the agent is currently ACTIVE.
 */
export function suspendAgent(agentId: string): Promise<AgentView> {
  return post<AgentView>(`/agents/${encodeURIComponent(agentId)}/suspend`);
}

/** `POST /agents/{n}/reactivate` -- staff FINANCE_OFFICER/ADMIN only. 409s
 *  unless the agent is currently SUSPENDED (an EXPIRED agent is not
 *  reactivated through this endpoint -- expiry is calendar-driven). */
export function reactivateAgent(agentId: string): Promise<AgentView> {
  return post<AgentView>(`/agents/${encodeURIComponent(agentId)}/reactivate`);
}

/**
 * Resolves the agent's own assigned plan if it has one, otherwise the
 * product's ACTIVE plan. A genuine 404 means neither applies yet -- not an
 * error state, the caller's cue to offer creating one.
 */
export function getApplicablePlan(agentId: string, productId: string): Promise<CommissionPlanView> {
  return get<CommissionPlanView>(`/agents/${encodeURIComponent(agentId)}/commission-plan`, {
    params: { productId },
  });
}

/** `POST /commission-plans` -- staff FINANCE_OFFICER/ADMIN only. Hangs off a
 *  PRODUCT, not an agent (Di1's own aggregate boundary) -- no agentId in the
 *  path or body. */
export function createCommissionPlan(
  request: CreateCommissionPlanRequest,
): Promise<CommissionPlanView> {
  return post<CommissionPlanView>('/commission-plans', request);
}

/** `period` is `YYYY-MM`. A bare array -- no pager, same shape as products'
 *  catalog listing. */
export function listCommissionStatements(
  agentId: string,
  period?: string,
): Promise<CommissionStatementView[]> {
  return get<CommissionStatementView[]>(
    `/agents/${encodeURIComponent(agentId)}/commission-statements`,
    period ? { params: { period } } : undefined,
  );
}

export function listAccruals(agentId: string, statementId: string): Promise<CommissionAccrualView[]> {
  return get<CommissionAccrualView[]>(
    `/agents/${encodeURIComponent(agentId)}/commission-statements/${encodeURIComponent(statementId)}/accruals`,
  );
}

/**
 * `POST .../payout` -- staff FINANCE_OFFICER/ADMIN only. Idempotency-Key is
 * hard-required. Only valid from CLOSED or PAYOUT_FAILED -- a statement still
 * OPEN (the monthly close sweep has not run yet) genuinely 409s, not a
 * client-side guess at the rule.
 */
export function requestPayout(
  agentId: string,
  statementId: string,
  request: RequestPayoutRequest,
  attempt: MutationAttempt,
): Promise<void> {
  return post<void>(
    `/agents/${encodeURIComponent(agentId)}/commission-statements/${encodeURIComponent(statementId)}/payout`,
    request,
    { headers: attempt.headers() },
  );
}
