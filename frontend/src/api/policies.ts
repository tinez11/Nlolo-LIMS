import { get, post, put } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  BeneficiaryInput,
  BeneficiaryOfView,
  CoverageStatusView,
  InvoiceView,
  LoanRepaymentRequest,
  LoanView,
  ManualIssueRequest,
  OriginateLoanRequest,
  Page,
  PaymentRequest,
  PolicyStatus,
  PolicyView,
  SuspendPolicyRequest,
  WaiverRequest,
} from './types';

/**
 * Policy read surface, hand-written over the generated types.
 *
 * Hand-written rather than generated because no spec declares an `operationId`, so
 * a client generator would invent names for all 68 operations -- and because the
 * Axios layer here owns the error normalization and idempotency interceptors.
 */

export interface PolicySearchParams {
  status?: PolicyStatus;
  policyholderPartyId?: string;
  q?: string;
  page?: number;
  pageSize?: number;
}

/** Server caps pageSize at 100 regardless of what is sent. */
export const MAX_PAGE_SIZE = 100;
export const DEFAULT_PAGE_SIZE = 20;

/**
 * `GET /policies` -- one of only four paged endpoints on the platform.
 *
 * The spec's response envelope is an anonymous inline object with no `required`, so
 * `items` and `page` are both optional on the wire. Normalized here to a total
 * `Page<T>` so every caller does not repeat the same defaulting.
 */
export async function searchPolicies(params: PolicySearchParams = {}): Promise<Page<PolicyView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: PolicyView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/policies', {
    params: {
      ...(params.status ? { status: params.status } : {}),
      ...(params.policyholderPartyId ? { policyholderPartyId: params.policyholderPartyId } : {}),
      ...(params.q ? { q: params.q } : {}),
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

export function getPolicy(policyNumber: string): Promise<PolicyView> {
  return get<PolicyView>(`/policies/${encodeURIComponent(policyNumber)}`);
}

export function getCoverageStatus(
  policyNumber: string,
  asOf?: string,
): Promise<CoverageStatusView> {
  return get<CoverageStatusView>(
    `/policies/${encodeURIComponent(policyNumber)}/coverage-status`,
    asOf ? { params: { asOf } } : undefined,
  );
}

/** `GET /policies/{n}/invoices` -- a bare unpaged array, so the whole set arrives. */
export function listInvoices(policyNumber: string): Promise<InvoiceView[]> {
  return get<InvoiceView[]>(`/policies/${encodeURIComponent(policyNumber)}/invoices`);
}

/** `GET /policies/{n}/loans` -- also a bare unpaged array. */
export function listLoans(policyNumber: string): Promise<LoanView[]> {
  return get<LoanView[]>(`/policies/${encodeURIComponent(policyNumber)}/loans`);
}

/**
 * `POST /policies/{n}/loans` -- staff/agent/customer. Returns **202** with the
 * loan body: the reservation against cash value is confirmed synchronously, but
 * disbursement is handed to `payment` and completes asynchronously, so the loan
 * that comes back is normally `DISBURSEMENT_REQUESTED` rather than `DISBURSED`.
 *
 * Rejections worth surfacing verbatim rather than rewording: 409 when the
 * requested amount exceeds available loan value (cash value net of existing
 * encumbrance and live reservations), and 409 when the policy is not in force.
 *
 * No `Idempotency-Key`: this path accepts the header but does not enforce it,
 * and `lib/idempotency.ts` asserts exactly that -- so minting an attempt here
 * would imply a guarantee the backend does not currently make.
 */
export function originateLoan(
  policyNumber: string,
  request: OriginateLoanRequest,
): Promise<LoanView> {
  return post<LoanView>(`/policies/${encodeURIComponent(policyNumber)}/loans`, request);
}

/**
 * `POST /loans/{loanId}/repayments` -- staff/agent/customer. Also 202 with the
 * loan body, and the returned `outstandingBalance` is already net of this
 * repayment (the balance is folded from the loan's own append-only ledger, so
 * it is authoritative the moment the call returns).
 *
 * A repayment that clears the balance moves the loan straight to `SETTLED`;
 * partial repayments leave it `REPAYING` and are individually accepted, as many
 * times as needed.
 */
export function recordLoanRepayment(
  loanId: string,
  request: LoanRepaymentRequest,
): Promise<LoanView> {
  return post<LoanView>(`/loans/${encodeURIComponent(loanId)}/repayments`, request);
}

/**
 * `POST /invoices/{invoiceId}/waiver` -- staff only. Returns 200 with no body
 * (verified against the real spec), so the only way to see the effect is to
 * refetch the invoice list. No `Idempotency-Key` here at all -- unlike its
 * neighbor below, waiving is not itself a money movement toward a payment
 * rail, so there is nothing for the key to dedupe against.
 */
export function waiveInvoice(invoiceId: string, request: WaiverRequest): Promise<void> {
  return post<void>(`/invoices/${encodeURIComponent(invoiceId)}/waiver`, request);
}

/**
 * `POST /invoices/{invoiceId}/payment-request` -- staff/customer/agent.
 * `Idempotency-Key` is hard-required (a 400 without it) -- see
 * lib/idempotency.ts's REQUIRED list. Returns 202 with no body; billing's own
 * `PaymentEventListener` moves the invoice toward PARTIALLY_PAID/PAID
 * asynchronously once payment confirms, so a refetch right after this call
 * may still show the pre-collection status for a moment.
 */
export function requestPaymentForInvoice(
  invoiceId: string,
  request: PaymentRequest,
  attempt: MutationAttempt,
): Promise<void> {
  return post<void>(`/invoices/${encodeURIComponent(invoiceId)}/payment-request`, request, {
    headers: attempt.headers(),
  });
}

/**
 * `PUT /policies/{n}/beneficiaries` -- replaces the whole beneficiary set.
 *
 * No `Idempotency-Key` on this endpoint at all (unlike the six hard-required
 * endpoints elsewhere): the controller declares no such header, and the operation
 * replaces the full set idempotently by construction (same body twice produces the
 * same end state), so there is nothing here for a key to protect against.
 *
 * Returns void: the spec declares this a `200` with no response body
 * (`ResponseEntity.ok().build()` server-side). Callers refetch the policy detail to
 * see the new beneficiaries.
 */
export function replaceBeneficiaries(
  policyNumber: string,
  beneficiaries: BeneficiaryInput[],
): Promise<void> {
  return put<void>(`/policies/${encodeURIComponent(policyNumber)}/beneficiaries`, beneficiaries);
}

/**
 * `POST /policies/manual-issue` -- the staff exception path that issues a policy
 * outside the normal underwriting-decision pipeline. No `Idempotency-Key` at all
 * on this endpoint (confirmed against both the controller signature and the
 * spec): a second identical submission genuinely creates a second policy, so the
 * UI's own submit-button disabling while in flight is the only guard against a
 * double-click, not a server-side idempotency mechanism.
 */
export function issuePolicy(request: ManualIssueRequest): Promise<PolicyView> {
  return post<PolicyView>('/policies/manual-issue', request);
}

/**
 * `POST /policies/{n}/suspend` -- staff only. Rejected with a real 409 for an
 * ineligible product category (POLICY_SUSPENSION_ELIGIBLE_CATEGORIES seeds only
 * GROUP_LIFE) or a policy that isn't currently ACTIVE.
 */
export function suspendPolicy(policyNumber: string, request: SuspendPolicyRequest): Promise<PolicyView> {
  return post<PolicyView>(`/policies/${encodeURIComponent(policyNumber)}/suspend`, request);
}

/** `POST /policies/{n}/resume` -- staff only. 409s unless the policy is SUSPENDED. */
export function resumePolicy(policyNumber: string): Promise<PolicyView> {
  return post<PolicyView>(`/policies/${encodeURIComponent(policyNumber)}/resume`);
}

/**
 * `POST /policies/{n}/reinstate` -- staff only. 409s unless the policy is LAPSED,
 * and again if it lapsed longer ago than refdata's TZ_REINSTATEMENT_WINDOW_MONTHS.
 */
export function reinstatePolicy(policyNumber: string): Promise<PolicyView> {
  return post<PolicyView>(`/policies/${encodeURIComponent(policyNumber)}/reinstate`);
}

/**
 * `GET /beneficiaries?partyId=` -- "which policies pay out to this person".
 *
 * The reverse of a policy's own beneficiary list, and previously unanswerable:
 * beneficiary rows were only ever readable by policy number, so a person's
 * exposure as a beneficiary was stored and unreachable.
 *
 * Its own resource rather than `/policies/beneficiaries`, which would sit under
 * `GET /policies/{policyNumber}` and depend on a routing precedence rule.
 *
 * Only ACTIVE rows: replacing a policy's beneficiaries deactivates the old ones
 * rather than deleting them, and that history is a different question. An
 * agents-realm caller may ask only about a client it registered -- a 403
 * otherwise, unlike the underwriting list's empty page.
 */
export function beneficiaryOf(partyId: string): Promise<BeneficiaryOfView[]> {
  return get<BeneficiaryOfView[]>('/beneficiaries', { params: { partyId } });
}
