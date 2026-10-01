import { get, post } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  FreeLookCancellationView,
  InstalmentStatus,
  Page,
  PaymentRunView,
  PayoutInstalmentView,
  ProofOfLifeMethod,
} from './types';

/**
 * Benefit payouts (product step 2): money the insurer pays OUT while the life assured is still
 * alive -- a maturity, a money-back plan's survival benefits, an income stream, a premium return.
 *
 * None of these endpoints hard-requires `Idempotency-Key` (they are not in
 * `lib/idempotency.ts`'s REQUIRED list), but every mutation here still sends one: the key is minted
 * once per user intent by the store, so a retry after a network timeout cannot become a second
 * approval. The backend's own defence is a state machine -- a second approve of the same payout is
 * refused as "not REVIEWED" -- but relying on that alone would mean the UI's retry depends on the
 * server's error text rather than on the guarantee the header exists for.
 */

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

export function listPolicyPayouts(policyNumber: string): Promise<PayoutInstalmentView[]> {
  return get<PayoutInstalmentView[]>(`/policies/${encodeURIComponent(policyNumber)}/payouts`);
}

export function getPayout(instalmentId: string): Promise<PayoutInstalmentView> {
  return get<PayoutInstalmentView>(`/payouts/${encodeURIComponent(instalmentId)}`);
}

export interface PayoutSearchParams {
  /** ONE status, or omitted for every status -- the register takes a single filter, like the rest. */
  status?: InstalmentStatus;
  page?: number;
  pageSize?: number;
}

/** The payouts register. Finance's queue: the rows from which money is actually paid. */
export async function searchPayouts(params: PayoutSearchParams = {}): Promise<Page<PayoutInstalmentView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: PayoutInstalmentView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/payouts', {
    params: {
      ...(params.status ? { status: params.status } : {}),
      page,
      pageSize,
    },
  });

  return {
    items: body.items ?? [],
    page: {
      page: body.page?.page ?? page,
      pageSize: body.page?.pageSize ?? pageSize,
      totalElements: body.page?.totalElements ?? 0,
    },
  };
}

export interface ReviewPayoutBody {
  payeeRef: string;
  proofOfLifeMethod: ProofOfLifeMethod | null;
  proofOfLifeDocumentId: string | null;
}

export function reviewPayout(
  instalmentId: string,
  body: ReviewPayoutBody,
  attempt: MutationAttempt,
): Promise<PayoutInstalmentView> {
  return post<PayoutInstalmentView>(`/payouts/${encodeURIComponent(instalmentId)}/review`, body, {
    headers: attempt.headers(),
  });
}

/** 202: the payout is REQUESTED from the rail, not paid. The screen must say so. */
export function approvePayout(instalmentId: string, attempt: MutationAttempt): Promise<PayoutInstalmentView> {
  return post<PayoutInstalmentView>(`/payouts/${encodeURIComponent(instalmentId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

/**
 * Only a payout whose disbursement genuinely FAILED. An IN_DOUBT one stays APPROVED and waits for
 * a person to reconcile it against the provider's statement -- retrying it could pay twice.
 */
export function retryPayout(instalmentId: string, attempt: MutationAttempt): Promise<PayoutInstalmentView> {
  return post<PayoutInstalmentView>(`/payouts/${encodeURIComponent(instalmentId)}/retry`, undefined, {
    headers: attempt.headers(),
  });
}

/** A bare array: one run per tenant per day, so the list stays short for years. */
export function listPaymentRuns(): Promise<PaymentRunView[]> {
  return get<PaymentRunView[]>('/payment-runs');
}

export function getPaymentRun(paymentRunId: string): Promise<PaymentRunView> {
  return get<PaymentRunView>(`/payment-runs/${encodeURIComponent(paymentRunId)}`);
}

export function listRunInstalments(paymentRunId: string): Promise<PayoutInstalmentView[]> {
  return get<PayoutInstalmentView[]>(`/payment-runs/${encodeURIComponent(paymentRunId)}/instalments`);
}

export function approvePaymentRun(paymentRunId: string, attempt: MutationAttempt): Promise<PaymentRunView> {
  return post<PaymentRunView>(`/payment-runs/${encodeURIComponent(paymentRunId)}/approve`, undefined, {
    headers: attempt.headers(),
  });
}

export interface ProofOfLifeBody {
  proofOfLifeMethod: ProofOfLifeMethod;
  proofOfLifeDocumentId: string | null;
}

/** 204 on success -- there is no body to read back. */
export function recordProofOfLife(
  streamId: string,
  body: ProofOfLifeBody,
  attempt: MutationAttempt,
): Promise<void> {
  return post<void>(`/payout-streams/${encodeURIComponent(streamId)}/proof-of-life`, body, {
    headers: attempt.headers(),
  });
}

/**
 * The policy's most recent free-look cancellation, or null when it has none.
 *
 * The endpoint answers 204 rather than 404 for "never cancelled", because that is not an error.
 * Axios hands a 204 back as an empty string, so the empty case is normalised to null HERE rather
 * than leaving every caller to remember that `'' as FreeLookCancellationView` is falsy-ish.
 */
export async function findFreeLook(policyNumber: string): Promise<FreeLookCancellationView | null> {
  const body = await get<FreeLookCancellationView | '' | null>(
    `/policies/${encodeURIComponent(policyNumber)}/free-look-cancellation`,
  );
  return body ? body : null;
}

export interface FreeLookDeductionBody {
  description: string;
  /** A decimal STRING with at most two places -- the server refuses a JSON number. */
  amount: string;
  documentId: string | null;
}

export interface FreeLookBody {
  payeeRef: string;
  deductions: FreeLookDeductionBody[];
}

export function requestFreeLook(
  policyNumber: string,
  body: FreeLookBody,
  attempt: MutationAttempt,
): Promise<FreeLookCancellationView> {
  return post<FreeLookCancellationView>(
    `/policies/${encodeURIComponent(policyNumber)}/free-look-cancellation`,
    body,
    { headers: attempt.headers() },
  );
}

export function approveFreeLook(
  cancellationId: string,
  attempt: MutationAttempt,
): Promise<FreeLookCancellationView> {
  return post<FreeLookCancellationView>(
    `/free-look-cancellations/${encodeURIComponent(cancellationId)}/approve`,
    undefined,
    { headers: attempt.headers() },
  );
}
