import type { ApprovalRow } from '@/api/approvals';

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
  'payout-review': (r) => `/staff/payouts/${id(r)}`,
  'payout-approval': (r) => `/staff/payouts/${id(r)}`,
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
  'accounting-policy': () => '/staff/accounting-policies',
  'period-reopen': () => '/staff/periods',
};

export function approvalLink(kind: string, row: ApprovalRow): string | null {
  return LINKS[kind]?.(row) ?? null;
}
