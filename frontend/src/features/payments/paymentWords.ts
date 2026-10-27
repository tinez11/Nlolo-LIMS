import type { PaymentRecord } from '@/api/payments';
import { humanizeStatus } from '@/lib/status';

/**
 * What a payment is for, in the words a finance officer uses (2026-10-27). The codes are payment's own
 * (disbursement_instruction / payment_transaction purpose CHECKs); a code this list does not know yet is humanised
 * rather than hidden.
 */
const PURPOSES: Record<string, string> = {
  PREMIUM: 'Premium',
  ACCOUNT_TOP_UP: 'Savings top-up',
  UL_TOP_UP: 'Unit-linked top-up',
  LOAN_DISBURSEMENT: 'Policy loan',
  CLAIM_SETTLEMENT: 'Claim settlement',
  COMMISSION_PAYOUT: 'Agent commission',
  SURRENDER_PAYOUT: 'Surrender',
  MATURITY_PAYOUT: 'Maturity',
  DIVIDEND_PAYOUT: 'Dividend',
  SURVIVAL_BENEFIT_PAYOUT: 'Survival benefit',
  INCOME_PAYOUT: 'Income instalment',
  PREMIUM_RETURN_PAYOUT: 'Premium return',
  FREE_LOOK_REFUND: 'Free-look refund',
  WITHDRAWAL_PAYOUT: 'Account withdrawal',
  DEPOSIT_MATURITY_PAYOUT: 'Deposit maturity',
  ANNUITY_PAYOUT: 'Annuity',
  COMMUTATION_PAYOUT: 'Commutation',
  PRICE_CORRECTION_PAYOUT: 'Price correction',
  LAPSE_SURRENDER_PAYOUT: 'Lapse surrender',
  TOP_UP_REFUND: 'Top-up refund',
  TERM_ACCOUNT_MATURITY_PAYOUT: 'Term account maturity',
};

const METHODS: Record<string, string> = {
  MOBILE_MONEY: 'Mobile money',
  EFT: 'Bank transfer',
  CASH: 'Cash',
  BANK_DEPOSIT: 'Bank deposit',
  CHEQUE: 'Cheque',
  LIPA_NUMBER: 'Lipa number',
  TANQR: 'TANQR',
};

/** The payout instalments benefitpayout owns: their sourceRef is the instalment, which has its own page. */
const INSTALMENT_PURPOSES = new Set([
  'MATURITY_PAYOUT', 'SURVIVAL_BENEFIT_PAYOUT', 'INCOME_PAYOUT', 'PREMIUM_RETURN_PAYOUT', 'ANNUITY_PAYOUT',
  'COMMUTATION_PAYOUT',
]);

export function purposeWords(purpose: string): string {
  return PURPOSES[purpose] ?? humanizeStatus(purpose);
}

export function methodWords(method: string): string {
  return METHODS[method] ?? humanizeStatus(method);
}

/**
 * The page that explains a payment: the claim it settles, the instalment it pays, otherwise its policy. Null when
 * the platform cannot say -- an older row with no policy recorded, or an agent's commission.
 */
export function paymentLink(p: PaymentRecord): string | null {
  // A receipt recorded by hand has its own page: what came in, who recorded it, and its confirmation.
  if (p.direction === 'IN' && p.method !== 'MOBILE_MONEY') return `/staff/receipts/${encodeURIComponent(p.id)}`;
  if (p.purpose === 'CLAIM_SETTLEMENT') return `/staff/claims/${encodeURIComponent(p.sourceRef)}`;
  if (INSTALMENT_PURPOSES.has(p.purpose)) return `/staff/payouts/${encodeURIComponent(p.sourceRef)}`;
  if (p.policyNumber) return `/staff/policies/${encodeURIComponent(p.policyNumber)}`;
  return null;
}
