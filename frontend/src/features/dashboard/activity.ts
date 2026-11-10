import type { ActivityItem } from '@/api/dashboard';

/** Underwriting decision outcomes, in the decision panel's words. An outcome not listed reads plainly "Case decided". */
const OUTCOME: Record<string, string> = {
  ACCEPTED: 'accepted',
  LOADED: 'accepted with a loading',
  POSTPONED: 'postponed',
  DECLINED: 'declined',
};

/**
 * The register a payment is in -- Deposits for money in, All payments out for money out (money movement 6d) -- on the
 * event's policy when it names one: every state, so the payment is in the list.
 */
function register(refs: Record<string, string>, state: 'done' | 'attention', list = '/staff/payments'): string {
  return refs.policyNumber
    ? `${list}?state=all&policy=${encodeURIComponent(refs.policyNumber)}`
    : `${list}?state=${state}`;
}

/**
 * Words per event type, each with the reference it links by. Only events a dashboard actually shows are here; any
 * other type is humanised from its own name rather than dropped, so a new backend event appears instead of vanishing.
 */
const WORDS: Record<
  string,
  {
    text: (refs: Record<string, string>) => string;
    link?: (refs: Record<string, string>) => string | undefined;
  }
> = {
  'underwriting.UnderwritingDecisionMade': {
    text: (r) => (r.outcome && OUTCOME[r.outcome] ? `Case decided: ${OUTCOME[r.outcome]}` : 'Case decided'),
    link: (r) => (r.caseId ? `/staff/underwriting/${r.caseId}` : undefined),
  },
  'claims.ClaimRegistered': {
    text: () => 'Claim registered',
    link: (r) => (r.claimId ? `/staff/claims/${r.claimId}` : undefined),
  },
  'claims.ClaimAssessed': {
    text: () => 'Claim assessed',
    link: (r) => (r.claimId ? `/staff/claims/${r.claimId}` : undefined),
  },
  'claims.ClaimSettlementRequested': {
    text: () => 'Payout requested',
    link: (r) => (r.claimId ? `/staff/claims/${r.claimId}` : undefined),
  },
  'claims.ClaimApproved': {
    text: () => 'Claim approved',
    link: (r) => (r.claimId ? `/staff/claims/${r.claimId}` : undefined),
  },
  'claims.ClaimRejected': {
    text: () => 'Claim rejected',
    link: (r) => (r.claimId ? `/staff/claims/${r.claimId}` : undefined),
  },
  'claims.ClaimSettled': {
    text: () => 'Claim settled',
    link: (r) => (r.claimId ? `/staff/claims/${r.claimId}` : undefined),
  },
  'billing.PremiumCollected': {
    text: () => 'Premium collected',
    link: (r) => (r.policyNumber ? `/staff/policies/${r.policyNumber}` : undefined),
  },
  'billing.FieldReceiptCaptured': { text: () => 'Agent cash collected', link: () => '/staff/field-receipts' },
  'billing.FieldReceiptReconciled': { text: () => 'Agent cash matched', link: () => '/staff/field-receipts' },
  'billing.FieldReceiptReconciliationOverdue': {
    text: () => 'Agent cash past its matching deadline',
    link: () => '/staff/field-receipts?status=RECONCILIATION_OVERDUE',
  },
  'billing.InvoiceWaived': {
    text: () => 'Premium waived',
    link: (r) => (r.policyNumber ? `/staff/policies/${r.policyNumber}` : undefined),
  },
  'billing.PremiumOverdue': {
    text: () => 'Premium overdue',
    link: (r) => (r.policyNumber ? `/staff/policies/${r.policyNumber}` : undefined),
  },
  'billing.PolicyLapseRecommended': {
    text: () => 'Lapse recommended',
    link: (r) => (r.policyNumber ? `/staff/policies/${r.policyNumber}` : undefined),
  },
  'billing.PremiumRefundDue': {
    text: () => 'Refund due',
    link: (r) => (r.policyNumber ? `/staff/policies/${r.policyNumber}` : undefined),
  },
  // Money in and out opens the payments register (2026-10-27): on its policy where the event names one, otherwise on the
  // state the payment ended in.
  'payment.PaymentConfirmed': { text: () => 'Deposit confirmed', link: (r) => register(r, 'done') },
  'payment.PaymentFailed': { text: () => 'Deposit failed', link: (r) => register(r, 'attention') },
  'payment.DisbursementCompleted': { text: () => 'Payout completed', link: (r) => register(r, 'done', '/staff/payouts/all') },
  'payment.DisbursementFailed': { text: () => 'Payout failed', link: (r) => register(r, 'attention', '/staff/payouts/all') },
  'payment.EftDisbursementAwaitingExecution': {
    text: () => 'Bank transfer waiting to be made',
    link: () => '/staff/bank-transfers',
  },
  'payment.EftDisbursementExecuted': { text: () => 'Bank transfer made', link: () => '/staff/bank-transfers' },
  'finaccounting.ManualJournal': { text: () => 'Manual journal changed', link: () => '/staff/manual-journals' },
  'finaccounting.AutoReversal': {
    text: () => 'Journal reversed automatically',
    link: () => '/staff/manual-journals',
  },
};

/** "payment.SomethingNewHappened" -> "Something new happened". */
function humanise(eventType: string): string {
  const name = eventType
    .slice(eventType.indexOf('.') + 1)
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .toLowerCase();
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/**
 * One audit-log event in the console's words, and the screen it belongs to.
 *
 * Beside the feed rather than inside it, so the component file exports only components -- the same rule that put
 * `countValue` in `lib/counts`.
 */
export function describeActivity(item: ActivityItem): { text: string; to?: string } {
  const words = WORDS[item.eventType];
  if (!words) return { text: humanise(item.eventType) };
  const to = words.link?.(item.refs);
  return to ? { text: words.text(item.refs), to } : { text: words.text(item.refs) };
}
