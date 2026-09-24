import { get, post } from '@/lib/http';
import type { AwaitingEftView, DisbursementView } from './types';

/**
 * The EFT rail's human half.
 *
 * Every other payout on this platform completes without anybody looking at it: the mobile-money
 * aggregator calls the backend back and the row moves itself. An EFT has no callback because it
 * has no integration — a finance officer signs in to the bank, makes the transfer, and returns to
 * record that they did. That is deliberate rather than unfinished: a credit-life claim pays a
 * lender millions of shillings, and that must not travel through this platform's mobile-money
 * mock.
 *
 * So these two calls are the only thing standing between a settled claim and money that never
 * moves. The backend has had them since plan 4; nothing in the console called them, which meant a
 * credit-life claim could be approved, valued and instructed, and then stop.
 */

/** `GET /disbursements/awaiting-execution` — FINANCE_OFFICER only. Oldest first, server-side. */
export function listAwaitingEftExecution(): Promise<AwaitingEftView[]> {
  return get<AwaitingEftView[]>('/disbursements/awaiting-execution');
}

/**
 * `GET /disbursements?purpose=CLAIM_SETTLEMENT&sourceRef={claimId}` — CLAIMS_ASSESSOR,
 * CLAIMS_MANAGER or FINANCE_OFFICER. Newest first.
 *
 * What became of a claim's payout. A claim at SETTLEMENT_REQUESTED used to say nothing more, and
 * on credit life that meant a claims manager looking at a claim that was quietly waiting on a
 * finance officer they could not see and a screen they could not open.
 */
export function listClaimPayouts(claimId: string): Promise<DisbursementView[]> {
  return get<DisbursementView[]>('/disbursements', {
    params: { purpose: 'CLAIM_SETTLEMENT', sourceRef: claimId },
  });
}

/**
 * `POST /disbursements/{id}/eft-execution` — FINANCE_OFFICER only.
 *
 * <b>This is the act, not a note about it.</b> It publishes `payment.DisbursementCompleted`,
 * which settles the claim, exits the borrower from cover and books the claims expense to the
 * general ledger. Recording a transfer that did not happen is therefore not a typo to correct
 * later; it is the platform believing a lender has been paid.
 *
 * 409 with `DISBURSEMENT_NOT_AWAITING_EXECUTION` when the row is not awaiting anybody — most
 * often a mobile-money payout the gateway owns and no person may hand-complete.
 */
export function markEftExecuted(disbursementId: string, bankReference: string): Promise<void> {
  return post<void>(`/disbursements/${encodeURIComponent(disbursementId)}/eft-execution`, {
    bankReference,
  });
}
