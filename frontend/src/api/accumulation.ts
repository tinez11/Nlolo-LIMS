import { get, post } from '@/lib/http';
import type { ApiError } from '@/lib/apiError';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  AccountView,
  AdjustmentView,
  ClosingQuoteView,
  RateDeclarationView,
  StatementRecordView,
  StatementView,
  TopUpView,
  TransferInView,
  WithdrawalView,
} from './types';

/**
 * Savings accounts (product step 3): the ledger behind an ACCOUNT-basis policy, the money moving on
 * it, its statements, and the rates declared for its product.
 *
 * Every mutation sends the store-minted `Idempotency-Key`, as `api/benefitPayouts.ts` explains: the
 * key belongs to the user intent, so a retry after a timeout cannot become a second withdrawal.
 */

const account = (policyNumber: string) => `/policies/${encodeURIComponent(policyNumber)}/account`;

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

/**
 * The account, or null for a policy valued by a cash-value scale -- every policy sold before this
 * step. The 404 is normalised HERE, because "this policy has no account" is an answer, not an error:
 * the policy page uses it to decide whether to show the Account tab at all.
 */
export async function getAccount(policyNumber: string): Promise<AccountView | null> {
  try {
    return await get<AccountView>(account(policyNumber));
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

/** What closing today would pay, before any surrender charge. */
export function getClosingQuote(policyNumber: string): Promise<ClosingQuoteView> {
  return get<ClosingQuoteView>(`${account(policyNumber)}/quote`);
}

export function listWithdrawals(policyNumber: string): Promise<WithdrawalView[]> {
  return get<WithdrawalView[]>(`${account(policyNumber)}/withdrawals`);
}

export function listTopUps(policyNumber: string): Promise<TopUpView[]> {
  return get<TopUpView[]>(`${account(policyNumber)}/top-ups`);
}

export function listTransfersIn(policyNumber: string): Promise<TransferInView[]> {
  return get<TransferInView[]>(`${account(policyNumber)}/transfers-in`);
}

export function listAdjustments(policyNumber: string): Promise<AdjustmentView[]> {
  return get<AdjustmentView[]>(`${account(policyNumber)}/adjustments`);
}

export function getStatement(policyNumber: string, from: string, to: string): Promise<StatementView> {
  return get<StatementView>(`${account(policyNumber)}/statement`, { params: { from, to } });
}

export function listStatements(policyNumber: string): Promise<StatementRecordView[]> {
  return get<StatementRecordView[]>(`${account(policyNumber)}/statements`);
}

/**
 * The endpoint produces application/pdf ONLY, so the request must say it accepts that -- the client's
 * default JSON Accept header is answered with a 406, as credit life's CSV downloads also had to
 * handle.
 */
export function downloadStatementPdf(statementId: string): Promise<Blob> {
  return get<Blob>(`/account-statements/${encodeURIComponent(statementId)}/pdf`, {
    responseType: 'blob',
    headers: { Accept: 'application/pdf' },
  });
}

export interface WithdrawalBody {
  amount: string;
  payeeRef: string;
}

export function requestWithdrawal(policyNumber: string, body: WithdrawalBody, attempt: MutationAttempt): Promise<WithdrawalView> {
  return post<WithdrawalView>(`${account(policyNumber)}/withdrawals`, body, { headers: attempt.headers() });
}

/** 202: approved and REQUESTED from the rail, not paid. The screen must say so. */
export function approveWithdrawal(withdrawalId: string, attempt: MutationAttempt): Promise<WithdrawalView> {
  return post<WithdrawalView>(`/account-withdrawals/${encodeURIComponent(withdrawalId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

export interface TopUpBody {
  amount: string;
  payerRef: string;
}

/** 202: the collection is requested; it is credited only when payment confirms it. */
export function requestTopUp(policyNumber: string, body: TopUpBody, attempt: MutationAttempt): Promise<TopUpView> {
  return post<TopUpView>(`${account(policyNumber)}/top-ups`, body, { headers: attempt.headers() });
}

export interface TransferInBody {
  amount: string;
  sourceScheme: string;
  documentRef?: string;
}

export function recordTransferIn(policyNumber: string, body: TransferInBody, attempt: MutationAttempt): Promise<TransferInView> {
  return post<TransferInView>(`${account(policyNumber)}/transfers-in`, body, { headers: attempt.headers() });
}

export interface AdjustmentBody {
  /** Signed: a correction may take money out as well as put it in. */
  amount: string;
  reason: string;
}

export function proposeAdjustment(policyNumber: string, body: AdjustmentBody, attempt: MutationAttempt): Promise<AdjustmentView> {
  return post<AdjustmentView>(`${account(policyNumber)}/adjustments`, body, { headers: attempt.headers() });
}

export function approveAdjustment(adjustmentId: string, attempt: MutationAttempt): Promise<AdjustmentView> {
  return post<AdjustmentView>(`/account-adjustments/${encodeURIComponent(adjustmentId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

export function rejectAdjustment(adjustmentId: string, attempt: MutationAttempt): Promise<AdjustmentView> {
  return post<AdjustmentView>(`/account-adjustments/${encodeURIComponent(adjustmentId)}/reject`, undefined, {
    headers: attempt.headers(),
  });
}

export interface StatementPeriodBody {
  from: string;
  to: string;
}

/** Files the PDF -- or returns the existing filing when nothing has been posted since. */
export function generateStatement(policyNumber: string, body: StatementPeriodBody, attempt: MutationAttempt): Promise<StatementRecordView> {
  return post<StatementRecordView>(`${account(policyNumber)}/statements`, body, { headers: attempt.headers() });
}

const rates = (productId: string) => `/products/${encodeURIComponent(productId)}/rate-declarations`;

export function listRates(productId: string): Promise<RateDeclarationView[]> {
  return get<RateDeclarationView[]>(rates(productId));
}

export interface RateDeclarationBody {
  ratePercent: number;
  effectiveFrom: string;
}

export function proposeRate(productId: string, body: RateDeclarationBody, attempt: MutationAttempt): Promise<RateDeclarationView> {
  return post<RateDeclarationView>(rates(productId), body, { headers: attempt.headers() });
}

export function approveRate(declarationId: string, attempt: MutationAttempt): Promise<RateDeclarationView> {
  return post<RateDeclarationView>(`/rate-declarations/${encodeURIComponent(declarationId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

export function withdrawRate(declarationId: string, attempt: MutationAttempt): Promise<RateDeclarationView> {
  return post<RateDeclarationView>(`/rate-declarations/${encodeURIComponent(declarationId)}/withdraw`, undefined, {
    headers: attempt.headers(),
  });
}
