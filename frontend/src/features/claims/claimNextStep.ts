import type { ClaimView } from '@/api/types';

/**
 * Who acts next on a claim, and whether that is the person reading it (2026-10-29, the user: a claim reading only
 * "Registered" left whoever registered it with no idea that somebody else -- an assessor, then a manager -- had to act).
 * One sentence, said at the top of the record, in the words of the roles that do the work.
 */
export function claimNextStep({
  claim,
  canAssess,
  canDecide,
  viewerAssessed,
}: {
  claim: Pick<ClaimView, 'status' | 'claimType'>;
  /** The viewer holds claims.assess. */
  canAssess: boolean;
  /** The viewer holds claims.settle. */
  canDecide: boolean;
  /** The viewer wrote one of this claim's assessments -- so the decision is someone else's. */
  viewerAssessed: boolean;
}): { tone: 'act' | 'wait' | 'done'; text: string } {
  switch (claim.status) {
    case 'REGISTERED':
    case 'REOPENED':
      if (claim.claimType === 'MATURITY' && claim.status === 'REGISTERED') {
        return canDecide
          ? { tone: 'act', text: 'A maturity claim needs no assessment: decide the settlement under Settlement.' }
          : { tone: 'wait', text: 'Waiting for a claims manager to decide the settlement. A maturity claim needs no assessment.' };
      }
      return canAssess
        ? { tone: 'act', text: 'Next: assess it under Assessment. A claims manager who did not assess it then decides.' }
        : {
            tone: 'wait',
            text: 'Waiting for a claims assessor to assess it, then for a claims manager to decide. Neither is something you can do on this claim.',
          };
    case 'UNDER_ASSESSMENT':
      if (viewerAssessed) {
        return { tone: 'wait', text: 'You assessed it, so another claims manager decides it. Nothing more is needed from you.' };
      }
      if (canDecide) return { tone: 'act', text: 'Assessed. Next: decide the settlement under Settlement.' };
      return {
        tone: 'wait',
        text: canAssess
          ? 'Assessed. Waiting for a claims manager to decide it; you can still add another assessment.'
          : 'Assessed. Waiting for a claims manager to decide it.',
      };
    case 'APPROVED':
    case 'SETTLEMENT_REQUESTED':
      return {
        tone: 'wait',
        text: 'Approved. The payment is on its way; a bank transfer or cheque waits for finance under Payments to make. See Payment.',
      };
    case 'SETTLED':
      return { tone: 'done', text: 'Settled: the claim has been paid.' };
    case 'REJECTED':
      return {
        tone: 'done',
        text: canDecide ? 'Rejected. You can reopen it under Reopen if new evidence arrives.' : 'Rejected. A claims manager can reopen it if new evidence arrives.',
      };
    default:
      return { tone: 'wait', text: '' };
  }
}
