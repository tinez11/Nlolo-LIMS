import type { AgentView } from '@/api/types';
import { isPositive, trimZeros } from './creditLifeAgreementForm';

export type CommissionStanding =
  | { kind: 'earning'; ratePercent: string }
  | { kind: 'no-agent' }
  | { kind: 'not-the-lender' }
  | { kind: 'no-commission' };

/**
 * Whether this scheme will earn commission on its next file, and if not, the FIRST thing in the
 * way. Commission that silently came to zero was the defect: 67 of 72 schemes had no agent, the
 * rest had the wrong one, and no credit-life product had a plan -- all of it invisible.
 *
 * The percentage is the yearly agreement's (2026-11-01): every file is priced on an agreement and
 * its lender is paid the commission it states, so no plan or agent rate is read. Null when no
 * agreement covers today.
 */
export function commissionStanding(input: {
  agentOfRecordId: string | null | undefined;
  lenderAgent: AgentView | null;
  agreedCommissionPercent: string | null;
}): CommissionStanding {
  if (!input.agentOfRecordId) return { kind: 'no-agent' };
  if (!input.lenderAgent || input.lenderAgent.agentId !== input.agentOfRecordId) {
    return { kind: 'not-the-lender' };
  }
  const agreed = input.agreedCommissionPercent;
  return agreed && isPositive(agreed)
    ? { kind: 'earning', ratePercent: `${trimZeros(agreed)}%` }
    : { kind: 'no-commission' };
}
