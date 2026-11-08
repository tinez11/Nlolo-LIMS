import type { ApprovalRow } from '@/api/approvals';
import { fromList } from '@/lib/returnCrumbs';

const policy = (row: ApprovalRow, tab?: string) =>
  row.policyNumber
    ? `/staff/policies/${encodeURIComponent(row.policyNumber)}${tab ? `?tab=${tab}` : ''}`
    : null;
const id = (row: ApprovalRow) => encodeURIComponent(row.reference);

/**
 * Where a waiting row is acted on: the page that already owns the act, so the inbox never grows a second way to
 * approve anything. Null for a kind this console does not know yet -- the row is listed, unlinked, rather than
 * dropped.
 */
const LINKS: Record<string, (row: ApprovalRow) => string | null> = {
  'bank-transfer': () => '/staff/bank-transfers',
  receipt: (r) => `/staff/receipts/${id(r)}?from=approvals`,
  // The row names the deposit, whose page is where its reversal is confirmed (money movement step 5).
  'deposit-reversal': (r) => `/staff/receipts/${id(r)}?from=approvals`,
  'payout-review': (r) => `/staff/payouts/${id(r)}`,
  'payout-approval': (r) => `/staff/payouts/${id(r)}`,
  // Money movement step 4: a loan's payout is released on the policy's Loans tab.
  'loan-payout': (r) => policy(r, 'loans'),
  // A withdrawal from the policy's balance is approved, or given its payee, on the Billing tab's Balance panel.
  'balance-withdrawal': (r) => policy(r, 'billing'),
  'balance-withdrawal-payee': (r) => policy(r, 'billing'),
  // A credit-life lender's premium refund is approved beside the credit, on the scheme's invoices.
  'lender-refund': (r) => policy(r, 'billing'),
  // The row's reference is agentId/statementId (DistributionApprovalSources); the agent's page releases it.
  'commission-payout': (r) => `/staff/agents/${encodeURIComponent(r.reference.split('/')[0] ?? '')}`,
  'payment-run': (r) => `/staff/payment-runs/${id(r)}`,
  'free-look': (r) => policy(r),
  surrender: (r) => policy(r),
  'account-withdrawal': (r) => policy(r, 'account'),
  'account-adjustment': (r) => policy(r, 'account'),
  'deposit-payee': (r) => policy(r, 'account'),
  'term-account-payee': (r) => policy(r, 'account'),
  'unit-withdrawal': (r) => policy(r, 'units'),
  'field-receipt': () => '/staff/field-receipts',
  'price-adjustment': () => '/staff/funds',
  'fund-price': () => '/staff/funds',
  'rate-declaration': (r) => `/staff/products/${id(r)}`,
  'bonus-declaration': (r) => `/staff/products/${id(r)}`,
  'withholding-rule': () => '/staff/withholding-rules',
  'manual-journal': (r) => `/staff/manual-journals/${id(r)}`,
  'expense-allocation': (r) => `/staff/ifrs17-engine/allocations/${id(r)}`,
  'engine-run': (r) => `/staff/ifrs17-engine/runs/${id(r)}`,
  'year-end-close': (r) => `/staff/year-end/closes/${id(r)}`,
  'reinsurance-statement': () => '/staff/reinsurance-statements',
  // A settlement opens its statement, where it is confirmed (2026-11-08); the row's id is the statement's.
  'reinsurance-settlement': (r) => `/staff/reinsurance-statements/${id(r)}`,
  'accounting-policy': () => '/staff/accounting-policies',
  'period-reopen': () => '/staff/periods',
};

/** Opened from the inbox, so the record leads back to it (2026-10-27). */
export function approvalLink(kind: string, row: ApprovalRow): string | null {
  const to = LINKS[kind]?.(row) ?? null;
  return to === null || to.includes('from=') ? to : fromList(to, 'approvals');
}
