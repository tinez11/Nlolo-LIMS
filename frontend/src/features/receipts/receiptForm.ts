import { z } from 'zod';
import type { ReceiptMethod, RecordReceipt } from '@/api/receipts';
import { todayIso } from '@/lib/dates';

/**
 * Each way a premium can come in by hand (2026-10-27), with the reference its own paperwork carries. The reference is
 * what stops one slip being recorded twice, so its label says exactly which number to copy.
 */
export const RECEIPT_METHODS: { value: ReceiptMethod; label: string; reference: string; hint: string }[] = [
  { value: 'BANK_DEPOSIT', label: 'Bank deposit', reference: 'Bank slip or transfer reference',
    hint: 'Paid into the insurer’s bank account at a branch or by transfer.' },
  { value: 'CASH', label: 'Cash', reference: 'Cash receipt number',
    hint: 'Notes and coins, at a branch or to an agent. Posted to the premium bank account it is banked into.' },
  { value: 'CHEQUE', label: 'Cheque', reference: 'Cheque number',
    hint: 'Record it once the cheque has cleared.' },
  { value: 'LIPA_NUMBER', label: 'Lipa number', reference: 'Transaction ID from the confirmation message',
    hint: 'Paid to the insurer’s mobile-money merchant till.' },
  { value: 'TANQR', label: 'TANQR', reference: 'TANQR reference from the payer’s app',
    hint: 'Paid by scanning the insurer’s TANQR code.' },
];

/**
 * A mobile-money request (2026-10-27): asked of the payer's phone rather than recorded by hand, and -- with no live
 * integration yet -- confirmed by finance like every other receipt once the money shows on the statement.
 */
export const REQUESTED_MOBILE_MONEY = {
  value: 'MOBILE_MONEY',
  label: 'Mobile money',
  reference: 'Mobile-money reference',
  hint: 'Requested from the payer’s phone. Confirm it once the money shows on the mobile-money statement.',
} as const;

export function methodOf(value: string): { value: string; label: string; reference: string; hint: string } | undefined {
  return value === REQUESTED_MOBILE_MONEY.value ? REQUESTED_MOBILE_MONEY : RECEIPT_METHODS.find((m) => m.value === value);
}

/** What a deposit pays (2026-11-07): the policy's premiums, a loan, or the savings account. */
/** FUNDS (2026-11-07): a unit-linked policy's top-up, buying units. */
export type PaysFor = 'PREMIUM' | 'LOAN' | 'SAVINGS' | 'FUNDS';

/**
 * The one Deposit page (2026-11-07, the user: one place, every method, and the method decides what happens behind).
 * Mobile money asks the payer's phone and needs only the number (and, but for a premium, the amount -- a premium
 * request asks for what the premium owes); any other method records money already received, with its reference and
 * day. Mirrors Receipts.record, the top-up endpoints and the two payment-request endpoints.
 */
export const receiptFormSchema = z.object({
  policyNumber: z.string().trim().min(1, 'Enter the policy number'),
  paysFor: z.enum(['PREMIUM', 'LOAN', 'SAVINGS', 'FUNDS']),
  loanId: z.string(),
  method: z.enum(['MOBILE_MONEY', 'CASH', 'BANK_DEPOSIT', 'CHEQUE', 'LIPA_NUMBER', 'TANQR'], {
    message: 'Choose how it is being paid',
  }),
  reference: z.string().trim().max(100),
  amount: z.string().trim(),
  receivedOn: z.string(),
  payerRef: z.string().trim().max(255),
  note: z.string().trim().max(500),
  fieldReceiptId: z.string(),
}).superRefine((v, ctx) => {
  const issue = (path: string, message: string) => ctx.addIssue({ code: 'custom', path: [path], message });
  if (v.paysFor === 'LOAN' && !v.loanId) issue('loanId', 'Choose the loan this repays');
  const mobile = v.method === 'MOBILE_MONEY';
  if (mobile && !v.payerRef) issue('payerRef', 'Enter the phone number to ask');
  // A premium request asks for what the premium owes; every other deposit says its amount.
  if (!(mobile && v.paysFor === 'PREMIUM')) {
    if (!/^\d+(\.\d{1,2})?$/.test(v.amount)) issue('amount', 'An amount like 120000 or 120000.50');
    else if (Number(v.amount) <= 0) issue('amount', 'More than zero');
  }
  if (!mobile) {
    if (!v.reference) issue('reference', 'Enter the reference, so the same money cannot be recorded twice');
    if (!v.receivedOn) issue('receivedOn', 'The day the money was received');
    else if (v.receivedOn > todayIso()) issue('receivedOn', 'It cannot be later than today');
  }
});

export type ReceiptFormValues = z.infer<typeof receiptFormSchema>;

export function blankReceiptForm(prefill: Partial<ReceiptFormValues> = {}): ReceiptFormValues {
  return {
    policyNumber: '',
    paysFor: 'PREMIUM',
    loanId: '',
    method: 'BANK_DEPOSIT',
    reference: '',
    amount: '',
    receivedOn: todayIso(),
    payerRef: '',
    note: '',
    fieldReceiptId: '',
    ...prefill,
  };
}

/** Money already received, for a premium or a loan: the receipt road. Never called for mobile money or savings. */
export function toApiRequest(values: ReceiptFormValues, currency: string): RecordReceipt {
  if (values.method === 'MOBILE_MONEY') throw new Error('Mobile money is asked of the phone, not recorded');
  return {
    // A premium deposit goes onto the policy's balance, which pays what is due oldest first (money movement step 5).
    policyNumber: values.paysFor === 'PREMIUM' ? values.policyNumber.trim().toUpperCase() : null,
    loanId: values.paysFor === 'LOAN' ? values.loanId : null,
    method: values.method,
    reference: values.reference.trim(),
    amount: { amount: Number(values.amount).toFixed(2), currencyCode: currency },
    receivedOn: values.receivedOn,
    payerRef: values.payerRef.trim() || null,
    note: values.note.trim() || null,
    fieldReceiptId: values.fieldReceiptId || null,
  };
}

/**
 * Where a receipt was reached from, so its pages lead back there (2026-10-27): the agent's field receipts, the policy
 * it pays, or the payments register. Before this both receipt pages pointed at Payments whatever page you had come
 * from -- a client's policy, most often.
 */
/** A deposit's page title by how the money came in: "Cheque deposit"; a bank deposit is simply "Bank deposit". */
export function depositTitle(methodLabel: string): string {
  return /deposit$/i.test(methodLabel) ? methodLabel : `${methodLabel} deposit`;
}

export function receiptOrigin(from: string | null, policyNumber: string | null | undefined) {
  const lists: Record<string, { label: string; to: string }> = {
    'field-receipts': { label: 'Agent cash', to: '/staff/field-receipts' },
    payments: { label: 'Deposits', to: '/staff/payments' },
    approvals: { label: 'Approvals', to: '/staff/approvals' },
  };
  const list = from ? lists[from] : undefined;
  if (list) return { crumbs: [list], back: list.to };
  if (policyNumber) {
    const policy = `/staff/policies/${encodeURIComponent(policyNumber)}?tab=billing`;
    return { crumbs: [{ label: 'Policies', to: '/staff/policies' }, { label: policyNumber, to: policy }], back: policy };
  }
  return { crumbs: [{ label: 'Deposits', to: '/staff/payments' }], back: '/staff/payments' };
}
