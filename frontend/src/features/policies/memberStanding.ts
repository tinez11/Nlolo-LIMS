import type { PolicyMemberView } from '@/api/types';
import { formatDate } from '@/lib/dates';

/**
 * Why a member left cover, in words -- `policy/api/ExitReason`.
 *
 * The roll said EXITED and nothing else, while the backend held why and when. On a credit-life
 * book that is the difference between a loan repaid (a premium refund owed) and a borrower who
 * died (a claim paid), and nobody could tell them apart without opening a file.
 */
export function exitReasonLabel(reason: PolicyMemberView['exitReason']): string | null {
  switch (reason) {
    case 'CLAIM_SETTLED':
      return 'Death claim paid';
    case 'SETTLED_EARLY':
      return 'Loan repaid early';
    case 'REFINANCED':
      return 'Loan refinanced';
    case 'WRITTEN_OFF':
      return 'Loan written off';
    case 'CANCELLED':
      return 'Loan cancelled';
    default:
      return null;
  }
}

/** "Death claim paid · left 23 Sep 2026", or just the date when no reason was recorded. */
export function exitSummary(member: Pick<PolicyMemberView, 'exitReason' | 'leftOn'>): string | null {
  const reason = exitReasonLabel(member.exitReason);
  const left = member.leftOn ? `left ${formatDate(member.leftOn)}` : null;
  return [reason, left].filter(Boolean).join(' · ') || null;
}
