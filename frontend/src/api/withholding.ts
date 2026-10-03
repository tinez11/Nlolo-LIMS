import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type { WithholdingRuleView } from './types';

/**
 * Tax-withholding rules (product step 5): proposed by one finance user, approved by another. Proposing
 * sends the store-minted Idempotency-Key, which the server reads -- a retry returns the same rule.
 */

export function listWithholdingRules(): Promise<WithholdingRuleView[]> {
  return get<WithholdingRuleView[]>('/withholding-rules');
}

export interface WithholdingRuleBody {
  payoutKinds: string[];
  ratePercent: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  legalReference: string;
}

export function proposeWithholdingRule(body: WithholdingRuleBody, attempt: MutationAttempt): Promise<WithholdingRuleView> {
  return post<WithholdingRuleView>('/withholding-rules', body, { headers: attempt.headers() });
}

export function approveWithholdingRule(ruleId: string): Promise<WithholdingRuleView> {
  return post<WithholdingRuleView>(`/withholding-rules/${encodeURIComponent(ruleId)}/approve`);
}

export function withdrawWithholdingRule(ruleId: string): Promise<WithholdingRuleView> {
  return post<WithholdingRuleView>(`/withholding-rules/${encodeURIComponent(ruleId)}/withdraw`);
}
