import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  AgentView,
  CommissionAccrualView,
  CommissionPlanView,
  CommissionStatementView,
  CreateCommissionPlanRequest,
  LicenseStatus,
  OnboardAgentRequest,
  Page,
  RequestPayoutRequest,
} from './types';

/**
 * Distribution (agents/commissions) read/write surface.
 *
 * `GET /agents` (added in M13) is a real paged list, staff-only. Before it, an
 * agent was reachable only by an id you already held: the response of onboarding
 * it, or a policy's own `agentOfRecordId` (surfaced on `PolicyView`, unlike
 * underwriting's `underwritingCaseId` which the wire DTO drops entirely --
 * confirmed by reading `PolicyResponseDto` directly).
 */

export interface AgentSearchParams {
  /** Case-insensitive substring match against LICENCE NUMBER. */
  q?: string;
  status?: LicenseStatus;
  /**
   * "Is this client also an agent." Supplying it IGNORES `q` and `status`
   * server-side -- looking up one party is a different question from searching the
   * register, and combining them would let a caller believe it had searched.
   */
  partyId?: string;
  page?: number;
  pageSize?: number;
}

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

/**
 * `GET /agents` -- staff only, tenant-scoped, paged.
 *
 * `q` matches the licence number, NOT a name: an agent has no name in the
 * distribution context. The person's name lives in `party`, reached through
 * `partyId`, so a name column here would need a second call per row.
 */
export async function listAgents(params: AgentSearchParams = {}): Promise<Page<AgentView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: AgentView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/agents', {
    params: {
      ...(params.q ? { q: params.q } : {}),
      ...(params.status ? { status: params.status } : {}),
      ...(params.partyId ? { partyId: params.partyId } : {}),
      page,
      pageSize,
    },
  });

  return {
    items: body.items ?? [],
    page: {
      page: body.page?.page ?? page,
      pageSize: body.page?.pageSize ?? pageSize,
      totalElements: body.page?.totalElements ?? 0,
    },
  };
}

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
 * from its `party_id` claim -- there is no `agentId` claim, and the `GET /agents`
 * list is staff-only, so this remains the only way an agent discovers its own
 * record.
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
