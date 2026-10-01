import { get, post, put } from '@/lib/http';
import type { MutationAttempt } from '@/lib/idempotency';
import type {
  BeneficiaryInput,
  BeneficiaryOfView,
  CoverageStatusView,
  GroupMemberInput,
  GroupSchemeView,
  InvoiceView,
  PremiumCreditView,
  IssueGroupSchemeRequest,
  MemberStatus,
  PolicyMemberView,
  LoanRepaymentRequest,
  LoanView,
  ManualIssueRequest,
  OriginateLoanRequest,
  Page,
  PaymentRequest,
  PolicyStatus,
  PolicyView,
  SurrenderQuote,
  SurrenderRequestView,
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
  /**
   * Policies this party is connected to in ANY recorded capacity -- as the policyholder, as
   * the life assured, or as an active named beneficiary. A DIFFERENT question from
   * policyholderPartyId, and the one the claims desk asks: a claimant is frequently not the
   * owner, and on a death claim is usually a beneficiary of a policy on somebody else's life.
   *
   * The server does not return which leg matched, and does not need to -- policyholderPartyId,
   * lifeAssuredPartyId and beneficiaries are all already on PolicyView, so the capacity is
   * derivable client-side (see policyCapacities).
   */
  relatedPartyId?: string;
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
      ...(params.relatedPartyId ? { relatedPartyId: params.relatedPartyId } : {}),
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

export interface MaturingSearchParams {
  /** Inclusive, `YYYY-MM-DD`. The server refuses a `to` before its `from` with a 400. */
  from: string;
  to: string;
  page?: number;
  pageSize?: number;
}

/**
 * `GET /policies/maturing` -- finance's cash planning list (product step 2).
 *
 * In-force policies only, maturity date then policy number. It returns no TOTAL, deliberately: a
 * sum across currencies is a number with no meaning and this platform does not convert, so nothing
 * here invents one either.
 */
export async function searchMaturing(params: MaturingSearchParams): Promise<Page<PolicyView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: PolicyView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/policies/maturing', {
    params: { from: params.from, to: params.to, page, pageSize },
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

/**
 * `POST /group-schemes/{n}/agent-of-record` -- FINANCE_OFFICER/ADMIN. Who earns commission on a
 * scheme from now on; accruals already booked stay where they are. Null makes it direct.
 */
export function changeSchemeAgentOfRecord(
  policyNumber: string,
  agentOfRecordId: string | null,
  reason: string,
): Promise<PolicyView> {
  return post<PolicyView>(`/group-schemes/${encodeURIComponent(policyNumber)}/agent-of-record`, {
    agentOfRecordId,
    reason,
  });
}

/**
 * `GET /policies/{n}/credits` -- premium credited back to members who left early, oldest first.
 * The other half of an invoice: without it a lender was shown 13,800 due when 9,600 was.
 */
export function listPolicyCredits(policyNumber: string): Promise<PremiumCreditView[]> {
  return get<PremiumCreditView[]>(`/policies/${encodeURIComponent(policyNumber)}/credits`);
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
 * `POST /policies/{n}/paid-up` -- staff only. The customer stops paying and keeps reduced
 * cover. 409 unless the policy is a savings product in a convertible status with value.
 */
export function makePaidUp(policyNumber: string): Promise<PolicyView> {
  return post<PolicyView>(`/policies/${encodeURIComponent(policyNumber)}/paid-up`);
}

/** `GET /policies/{n}/surrender-value` -- what a surrender would pay today. Does not commit. */
export function getSurrenderValue(policyNumber: string): Promise<SurrenderQuote> {
  return get<SurrenderQuote>(`/policies/${encodeURIComponent(policyNumber)}/surrender-value`);
}

/** `GET /policies/{n}/surrender-request` -- the latest request, or nothing (204). */
export function getSurrenderRequest(policyNumber: string): Promise<SurrenderRequestView | ''> {
  return get<SurrenderRequestView | ''>(`/policies/${encodeURIComponent(policyNumber)}/surrender-request`);
}

/** `POST /policies/{n}/surrender` -- records a REQUESTED surrender. Does NOT stop cover. */
export function requestSurrender(policyNumber: string, payeeRef: string): Promise<SurrenderRequestView> {
  return post<SurrenderRequestView>(`/policies/${encodeURIComponent(policyNumber)}/surrender`, { payeeRef });
}

/** `POST /surrender-requests/{id}/approve` -- finance only, and never the requester. Cover stops. */
export function approveSurrender(surrenderRequestId: string): Promise<SurrenderRequestView> {
  return post<SurrenderRequestView>(`/surrender-requests/${encodeURIComponent(surrenderRequestId)}/approve`);
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

// --- Group business ---------------------------------------------------------
//
// `/group-schemes` is its own resource, not a branch under `/policies`. A scheme
// read as a policy answers "one contract, 500 lives, sum assured X" -- true, and
// useless to somebody administering the schedule. Staff only, all four.

/**
 * `POST /group-schemes` -- issues the master policy, the scheme, its grades and
 * its opening schedule in one call.
 *
 * There is no `sumAssured` to send: a scheme's sum assured IS the total of its
 * members' cover, derived server-side from `openingSchedule`. That is also why a
 * scheme cannot be issued empty -- 409, not a scheme with nothing in it.
 *
 * No `Idempotency-Key`: the endpoint declares none, so a second submission
 * genuinely creates a second scheme. The form's own in-flight disabling is the
 * only guard against a double-click, exactly as with `issuePolicy`.
 */
/**
 * `PUT /group-schemes/{policyNumber}/free-cover-limit` -- staff UNDERWRITER only.
 *
 * The ONLY term on a scheme that can be amended. The interest method, the repayment frequency
 * and the premium rate are write-once by design: members were valued and CHARGED against them,
 * so restating any of those rewrites history. A free cover limit only decides how much of a
 * benefit is covered today, and cover is effective-dated -- so moving it writes new rows from
 * today rather than altering what was true yesterday.
 *
 * 409 when the new limit would reduce somebody's cover, naming how many, or when it is the
 * limit the scheme already has.
 *
 * `fclAmount` null means no limit at all, which is never the same as zero.
 */
export function amendFreeCoverLimit(
  policyNumber: string,
  fclAmount: string | null,
  reason: string,
): Promise<GroupSchemeView> {
  return put<GroupSchemeView>(
    `/group-schemes/${encodeURIComponent(policyNumber)}/free-cover-limit`,
    { ...(fclAmount ? { fclAmount } : {}), reason },
  );
}
export function issueGroupScheme(request: IssueGroupSchemeRequest): Promise<GroupSchemeView> {
  return post<GroupSchemeView>('/group-schemes', request);
}

/**
 * `GET /group-schemes/{n}` -- configuration plus derived totals.
 *
 * 409, not 404, when the policy exists but is an individual policy. The two are
 * different answers and the caller should not conflate them: one is a dead end,
 * the other is a policy to go and look at.
 */
export function getGroupScheme(policyNumber: string): Promise<GroupSchemeView> {
  return get<GroupSchemeView>(`/group-schemes/${encodeURIComponent(policyNumber)}`);
}

/** Server caps member pages at 200. */
export const MAX_MEMBER_PAGE_SIZE = 200;
export const DEFAULT_MEMBER_PAGE_SIZE = 25;

export interface MemberListParams {
  /** Omit for every member including those who have left. */
  status?: MemberStatus;
  /**
   * Free-text, case-insensitive substring match against the MEMBER'S NAME. Omit for no
   * search; ANDed with `status` rather than replacing it.
   *
   * Resolved server-side through the party module -- a member row carries a party id and
   * no name -- so this is a real filter with a real total, not a pass over the rows in
   * hand. That matters on the screen it serves: a 500-life schedule is exactly the case
   * where filtering one page would answer the wrong question.
   */
  q?: string;
  page?: number;
  pageSize?: number;
}

/**
 * `GET /group-schemes/{n}/members/{memberId}` -- one member, built exactly as a roll
 * row. For a screen holding only the member id a claim carries: finance's transfer
 * queue, saying whose death a payment settles.
 */
export function getSchemeMember(policyNumber: string, policyMemberId: string): Promise<PolicyMemberView> {
  return get<PolicyMemberView>(
    `/group-schemes/${encodeURIComponent(policyNumber)}/members/${encodeURIComponent(policyMemberId)}`,
  );
}

/**
 * `GET /group-schemes/{n}/members` -- one page of the schedule, each row carrying
 * the benefit in force for that member today.
 *
 * The sort is fixed server-side and total (join date, then member id). It is not a
 * caller choice on purpose: a bulk schedule gives every row the same join date, and
 * paging an order with ties can show one member twice while omitting another.
 *
 * Same defensive normalization as `searchPolicies`: the envelope's `items` and
 * `page` are both optional on the wire.
 */
export async function listSchemeMembers(
  policyNumber: string,
  params: MemberListParams = {},
): Promise<Page<PolicyMemberView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_MEMBER_PAGE_SIZE, MAX_MEMBER_PAGE_SIZE);

  const body = await get<{
    items?: PolicyMemberView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>(`/group-schemes/${encodeURIComponent(policyNumber)}/members`, {
    params: {
      ...(params.status ? { status: params.status } : {}),
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

/**
 * `POST /group-schemes/{n}/members` -- adds one life.
 *
 * Rejections worth showing verbatim rather than rewording, because each names a
 * fix: already an active member, a join date in the future or before the scheme
 * commenced, and inputs that do not match the scheme's benefit basis (a salary on
 * a flat scheme, a grade the scheme does not have).
 *
 * The scheme's total moves as a result, so callers refetch the scheme after this.
 */
export function addSchemeMember(
  policyNumber: string,
  member: GroupMemberInput,
): Promise<PolicyMemberView> {
  return post<PolicyMemberView>(
    `/group-schemes/${encodeURIComponent(policyNumber)}/members`,
    member,
  );
}
