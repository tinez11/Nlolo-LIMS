import { create } from 'zustand';
import {
  addSchemeMember,
  getCoverageStatus,
  getGroupScheme,
  getPolicy,
  amendFreeCoverLimit,
  issueGroupScheme,
  issuePolicy,
  listInvoices,
  listLoans,
  listSchemeMembers,
  originateLoan,
  recordLoanRepayment,
  reinstatePolicy,
  makePaidUp,
  getSurrenderValue,
  getSurrenderRequest,
  requestSurrender,
  approveSurrender,
  replaceBeneficiaries,
  requestPaymentForInvoice,
  resumePolicy,
  searchMaturing,
  searchPolicies,
  type MaturingSearchParams,
  suspendPolicy,
  waiveInvoice,
  type MemberListParams,
  type PolicySearchParams,
} from '@/api/policies';
import type {
  BeneficiaryInput,
  CoverageStatusView,
  GroupMemberInput,
  GroupSchemeView,
  InvoiceView,
  IssueGroupSchemeRequest,
  LoanRepaymentRequest,
  LoanView,
  ManualIssueRequest,
  OriginateLoanRequest,
  Page,
  PaymentRequest,
  PolicyMemberView,
  PolicyView,
  SurrenderQuote,
  SurrenderRequestView,
  SuspendPolicyRequest,
  WaiverRequest,
} from '@/api/types';
import type { MutationAttempt } from '@/lib/idempotency';
import { idle, success, track, type Resource } from './createResourceSlice';

/**
 * The `policy` domain store.
 *
 * One store per backend module, mirroring the Modulith boundaries, so a screen's
 * data source is obvious. Detail resources are keyed by policy number rather than
 * held in a single slot, so opening a second policy does not blank the first while
 * it loads.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface PolicyState {
  list: Resource<Page<PolicyView>>;
  /** Finance's cash planning list: what matures in a window (product step 2). */
  maturing: Resource<Page<PolicyView>>;
  /**
   * Policies a given party is connected to in any capacity, keyed by that PARTY id.
   *
   * Keyed rather than a single slot for the reason the client register learned the hard way
   * (PLAN.md 14.9): a claims clerk who picks the wrong claimant, then corrects it, must not
   * see the first claimant's policies rendered under the second one's name while the second
   * request is still in flight. Choosing a policy off that list would file the claim against
   * the wrong contract, and nothing on screen would look wrong.
   */
  claimantPolicies: Keyed<Page<PolicyView>>;
  detail: Keyed<PolicyView>;
  coverage: Keyed<CoverageStatusView>;
  invoices: Keyed<InvoiceView[]>;
  loans: Keyed<LoanView[]>;
  // Deliberately separate from `detail`: a failed SAVE must not corrupt or discard
  // the last known-good read of the policy, and the two have unrelated shapes
  // (this one carries no useful `data`, just whether a save is in flight or failed).
  savingBeneficiaries: Keyed<true>;
  // A single slot, not keyed: issuance creates a NEW policy, so there is no
  // existing policyNumber to key against yet -- same shape as claims' `registering`.
  issuing: Resource<PolicyView>;
  // Both keyed by invoiceId: each targets an EXISTING invoice, and a failed
  // action on one must not corrupt another's state.
  waivingInvoice: Keyed<true>;
  requestingPayment: Keyed<true>;
  // Origination is keyed by policyNumber, not by loan id: the loan does not exist
  // yet, and a policy can only have one origination in flight from this screen.
  originatingLoan: Keyed<true>;
  // Repayment is keyed by loanId -- it targets an existing loan, and a failure on
  // one loan must not disturb another's form on the same policy.
  repayingLoan: Keyed<true>;
  // Each keyed by policyNumber, separately from `detail` and from each other --
  // same shape as claims' submittingAssessment/decidingSettlement/reopening: three
  // distinct lifecycle actions on the same entity, each its own tracked mutation.
  suspending: Keyed<PolicyView>;
  resuming: Keyed<PolicyView>;
  reinstating: Keyed<PolicyView>;
  // Product step 1's two value actions, each its own tracked mutation for the reason above.
  // `surrenderQuote` and `surrenderRequest` are reads the surrender panel needs before it can
  // say what a surrender would pay, or offer an approval.
  makingPaidUp: Keyed<PolicyView>;
  surrenderQuote: Keyed<SurrenderQuote>;
  surrenderRequest: Keyed<SurrenderRequestView | null>;
  requestingSurrender: Keyed<SurrenderRequestView>;
  approvingSurrender: Keyed<SurrenderRequestView>;

  // Group business. `scheme` and `members` are keyed by policy number, like
  // `detail` -- the scheme page holds both at once and they load independently,
  // so a slow member page must not blank the summary above it.
  scheme: Keyed<GroupSchemeView>;
  members: Keyed<Page<PolicyMemberView>>;
  // A single slot: issuing a scheme creates a NEW policy number, so there is
  // nothing to key against yet -- same shape as `issuing`.
  issuingScheme: Resource<GroupSchemeView>;
  // Keyed by policy number, not by member: the member does not exist yet, and a
  // scheme can only have one add in flight from this screen.
  addingMember: Keyed<PolicyMemberView>;

  loadList: (params: PolicySearchParams) => Promise<void>;
  loadMaturing: (params: MaturingSearchParams) => Promise<void>;
  loadClaimantPolicies: (partyId: string, q?: string) => Promise<void>;
  loadDetail: (policyNumber: string) => Promise<void>;
  loadCoverage: (policyNumber: string, asOf?: string) => Promise<void>;
  loadInvoices: (policyNumber: string) => Promise<void>;
  loadLoans: (policyNumber: string) => Promise<void>;
  saveBeneficiaries: (policyNumber: string, beneficiaries: BeneficiaryInput[]) => Promise<void>;
  /** Clears a stale save error before a fresh edit attempt -- see the call site. */
  resetSaveBeneficiaries: (policyNumber: string) => void;
  issuePolicy: (request: ManualIssueRequest) => Promise<void>;
  /** Clears a stale issuance error before a fresh attempt -- see RegisterClaimPage's
   *  identical need for why this exists from the start rather than being added later. */
  resetIssuePolicy: () => void;
  waiveInvoice: (policyNumber: string, invoiceId: string, request: WaiverRequest) => Promise<void>;
  resetWaiveInvoice: (invoiceId: string) => void;
  requestPaymentForInvoice: (
    policyNumber: string,
    invoiceId: string,
    request: PaymentRequest,
    attempt: MutationAttempt,
  ) => Promise<void>;
  resetRequestPaymentForInvoice: (invoiceId: string) => void;
  originateLoan: (policyNumber: string, request: OriginateLoanRequest) => Promise<void>;
  resetOriginateLoan: (policyNumber: string) => void;
  recordLoanRepayment: (
    policyNumber: string,
    loanId: string,
    request: LoanRepaymentRequest,
  ) => Promise<void>;
  resetRecordLoanRepayment: (loanId: string) => void;
  suspendPolicy: (policyNumber: string, request: SuspendPolicyRequest) => Promise<void>;
  resetSuspendPolicy: (policyNumber: string) => void;
  resumePolicy: (policyNumber: string) => Promise<void>;
  resetResumePolicy: (policyNumber: string) => void;
  reinstatePolicy: (policyNumber: string) => Promise<void>;
  resetReinstatePolicy: (policyNumber: string) => void;

  makePaidUp: (policyNumber: string) => Promise<void>;
  loadSurrenderQuote: (policyNumber: string) => Promise<void>;
  loadSurrenderRequest: (policyNumber: string) => Promise<void>;
  requestSurrender: (policyNumber: string, payeeRef: string) => Promise<void>;
  approveSurrender: (policyNumber: string, surrenderRequestId: string) => Promise<void>;

  loadScheme: (policyNumber: string) => Promise<void>;
  loadMembers: (policyNumber: string, params?: MemberListParams) => Promise<void>;
  issueGroupScheme: (request: IssueGroupSchemeRequest) => Promise<void>;
  amendFreeCoverLimit: (policyNumber: string, fclAmount: string | null, reason: string) => Promise<void>;
  resetIssueGroupScheme: () => void;
  addSchemeMember: (policyNumber: string, member: GroupMemberInput) => Promise<void>;
  resetAddSchemeMember: (policyNumber: string) => void;
}

export const usePolicyStore = create<PolicyState>((set, getState) => ({
  list: idle(),
  maturing: idle(),
  claimantPolicies: {},
  detail: {},
  coverage: {},
  invoices: {},
  loans: {},
  savingBeneficiaries: {},
  issuing: idle(),
  waivingInvoice: {},
  requestingPayment: {},
  originatingLoan: {},
  repayingLoan: {},
  suspending: {},
  resuming: {},
  reinstating: {},
  makingPaidUp: {},
  surrenderQuote: {},
  surrenderRequest: {},
  requestingSurrender: {},
  approvingSurrender: {},
  scheme: {},
  members: {},
  issuingScheme: idle(),
  addingMember: {},

  // Every `track` call below is keyed so a slower, superseded request can never
  // overwrite a faster, newer one -- e.g. clicking through status filter chips
  // quickly, where network timing has no relationship to click order. The list key
  // is constant regardless of which filter was requested: it is the same on-screen
  // table either way, and only the most recently REQUESTED filter should win.
  loadClaimantPolicies: (partyId, q) => {
    // Keyed by (party, filter) rather than by party alone, the same reasoning `coverage` uses
    // for (policy, asOf): two different filters are two different answers, and sharing one
    // slot would render the results of one query under the heading of another.
    const key = claimantPoliciesKey(partyId, q);
    return track(
      `policy.claimantPolicies.${key}`,
      getState().claimantPolicies[key],
      (next) => set((s) => ({ claimantPolicies: { ...s.claimantPolicies, [key]: next } })),
      /*
       * `q` goes to the SERVER, and that is the whole point of accepting it here.
       *
       * pageSize 100 is the server's own cap, so a claimant with more connections than that
       * gets a truncated first page -- and because the sort is `createdAt DESC`, what falls
       * off the end is their OLDEST policy, which on a life book is the one most likely to be
       * claimed against. Filtering the already-loaded page client-side would therefore search
       * exactly the wrong 100. Measured, not theorised: the seeded dev policyholder has over
       * 100 policies and the one a test wanted was not among the newest.
       */
      () =>
        searchPolicies({
          relatedPartyId: partyId,
          pageSize: 100,
          ...(q && q.trim() ? { q: q.trim() } : {}),
        }),
    );
  },

  loadList: (params) =>
    track(
      'policy.list',
      getState().list,
      (next) => set({ list: next }),
      () => searchPolicies(params),
    ),

  loadMaturing: (params) =>
    track(
      'policy.maturing',
      getState().maturing,
      (next) => set({ maturing: next }),
      () => searchMaturing(params),
    ),

  loadDetail: (policyNumber) =>
    track(
      `policy.detail.${policyNumber}`,
      getState().detail[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [policyNumber]: next } })),
      () => getPolicy(policyNumber),
    ),

  loadCoverage: (policyNumber, asOf) => {
    const key = coverageKey(policyNumber, asOf);
    return track(
      `policy.coverage.${key}`,
      getState().coverage[key] ?? idle<CoverageStatusView>(),
      (next) => set((s) => ({ coverage: { ...s.coverage, [key]: next } })),
      () => getCoverageStatus(policyNumber, asOf),
    );
  },

  loadInvoices: (policyNumber) =>
    track(
      `policy.invoices.${policyNumber}`,
      getState().invoices[policyNumber] ?? idle<InvoiceView[]>(),
      (next) => set((s) => ({ invoices: { ...s.invoices, [policyNumber]: next } })),
      () => listInvoices(policyNumber),
    ),

  loadLoans: (policyNumber) =>
    track(
      `policy.loans.${policyNumber}`,
      getState().loans[policyNumber] ?? idle<LoanView[]>(),
      (next) => set((s) => ({ loans: { ...s.loans, [policyNumber]: next } })),
      () => listLoans(policyNumber),
    ),

  // A distinct key from `policy.detail.${policyNumber}` -- the save and the
  // subsequent refresh are two independent tracked operations, so a slow refresh
  // triggered by an OLDER save cannot be confused with one triggered by a newer one.
  saveBeneficiaries: (policyNumber, beneficiaries) =>
    track(
      `policy.saveBeneficiaries.${policyNumber}`,
      getState().savingBeneficiaries[policyNumber] ?? idle<true>(),
      (next) => set((s) => ({ savingBeneficiaries: { ...s.savingBeneficiaries, [policyNumber]: next } })),
      // Explicit Promise<true> return type: without it, TypeScript widens the
      // literal `return true` to `boolean` because the function body has more than
      // one statement, which then fails to satisfy Resource<true>.
      async (): Promise<true> => {
        await replaceBeneficiaries(policyNumber, beneficiaries);
        // The PUT returns no body, so the only way to show the new set is to refetch.
        // Awaited so a caller that closes the edit form on success never renders the
        // stale pre-save detail for one frame.
        await getState().loadDetail(policyNumber);
        return true;
      },
    ),

  // This resource is keyed by policy number and outlives the edit form's own
  // mount/unmount -- it has to, so a failure is still visible if the form is
  // torn down and rebuilt mid-flight. But that means an OLD failure never
  // clears itself on its own: reopening the form after a rejected save was
  // found (via a real e2e failure, not inspection) to immediately resurface
  // the previous error banner before the user has done anything wrong this
  // time. Called once when editing begins, not on every render.
  resetSaveBeneficiaries: (policyNumber) =>
    set((s) => {
      if (!(policyNumber in s.savingBeneficiaries)) return s;
      const { [policyNumber]: _discard, ...rest } = s.savingBeneficiaries;
      return { savingBeneficiaries: rest };
    }),

  issuePolicy: (request) =>
    track(
      'policy.issue',
      getState().issuing,
      (next) => set({ issuing: next }),
      () => issuePolicy(request),
    ),

  resetIssuePolicy: () => set({ issuing: idle() }),

  waiveInvoice: (policyNumber, invoiceId, request) =>
    track(
      `policy.waiveInvoice.${invoiceId}`,
      getState().waivingInvoice[invoiceId] ?? idle<true>(),
      (next) => set((s) => ({ waivingInvoice: { ...s.waivingInvoice, [invoiceId]: next } })),
      // Explicit Promise<true>: see productStore.publishVersion for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await waiveInvoice(invoiceId, request);
        // The waiver POST returns no body at all -- refetch to see the
        // invoice's real new status (WAIVED).
        await getState().loadInvoices(policyNumber);
        return true;
      },
    ),

  resetWaiveInvoice: (invoiceId) =>
    set((s) => {
      if (!(invoiceId in s.waivingInvoice)) return s;
      const { [invoiceId]: _discard, ...rest } = s.waivingInvoice;
      return { waivingInvoice: rest };
    }),

  requestPaymentForInvoice: (policyNumber, invoiceId, request, attempt) =>
    track(
      `policy.requestPayment.${invoiceId}`,
      getState().requestingPayment[invoiceId] ?? idle<true>(),
      (next) => set((s) => ({ requestingPayment: { ...s.requestingPayment, [invoiceId]: next } })),
      async (): Promise<true> => {
        await requestPaymentForInvoice(invoiceId, request, attempt);
        // 202 with no body -- collection itself completes asynchronously, but
        // refetch anyway so a fast confirm already reflected server-side shows.
        await getState().loadInvoices(policyNumber);
        return true;
      },
    ),

  resetRequestPaymentForInvoice: (invoiceId) =>
    set((s) => {
      if (!(invoiceId in s.requestingPayment)) return s;
      const { [invoiceId]: _discard, ...rest } = s.requestingPayment;
      return { requestingPayment: rest };
    }),

  originateLoan: (policyNumber, request) =>
    track(
      `policy.originateLoan.${policyNumber}`,
      getState().originatingLoan[policyNumber] ?? idle<true>(),
      (next) => set((s) => ({ originatingLoan: { ...s.originatingLoan, [policyNumber]: next } })),
      async (): Promise<true> => {
        await originateLoan(policyNumber, request);
        // The 202 DOES carry the new loan, but the list is refetched rather than
        // appended: origination also moves the policy's cash-value encumbrance,
        // and a refetch keeps the table consistent with the server instead of
        // reconstructing it client-side from a single row.
        await getState().loadLoans(policyNumber);
        return true;
      },
    ),

  resetOriginateLoan: (policyNumber) =>
    set((s) => {
      if (!(policyNumber in s.originatingLoan)) return s;
      const { [policyNumber]: _discard, ...rest } = s.originatingLoan;
      return { originatingLoan: rest };
    }),

  recordLoanRepayment: (policyNumber, loanId, request) =>
    track(
      `policy.recordLoanRepayment.${loanId}`,
      getState().repayingLoan[loanId] ?? idle<true>(),
      (next) => set((s) => ({ repayingLoan: { ...s.repayingLoan, [loanId]: next } })),
      async (): Promise<true> => {
        await recordLoanRepayment(loanId, request);
        // Refetch, not patch: the returned balance is authoritative, but a
        // repayment that clears the balance also flips the loan to SETTLED, and
        // the row on screen must show both together.
        await getState().loadLoans(policyNumber);
        return true;
      },
    ),

  resetRecordLoanRepayment: (loanId) =>
    set((s) => {
      if (!(loanId in s.repayingLoan)) return s;
      const { [loanId]: _discard, ...rest } = s.repayingLoan;
      return { repayingLoan: rest };
    }),

  // Each of the three below returns the updated PolicyView directly, but `detail`
  // is refreshed from it too -- so a caller reading `detail` (the coverage panel,
  // the status badge in the header) sees the new status without a manual reload.
  suspendPolicy: (policyNumber, request) =>
    track(
      `policy.suspend.${policyNumber}`,
      getState().suspending[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ suspending: { ...s.suspending, [policyNumber]: next } })),
      async () => {
        const updated = await suspendPolicy(policyNumber, request);
        set((s) => ({ detail: { ...s.detail, [policyNumber]: success(updated) } }));
        return updated;
      },
    ),

  resetSuspendPolicy: (policyNumber) =>
    set((s) => {
      if (!(policyNumber in s.suspending)) return s;
      const { [policyNumber]: _discard, ...rest } = s.suspending;
      return { suspending: rest };
    }),

  resumePolicy: (policyNumber) =>
    track(
      `policy.resume.${policyNumber}`,
      getState().resuming[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ resuming: { ...s.resuming, [policyNumber]: next } })),
      async () => {
        const updated = await resumePolicy(policyNumber);
        set((s) => ({ detail: { ...s.detail, [policyNumber]: success(updated) } }));
        return updated;
      },
    ),

  resetResumePolicy: (policyNumber) =>
    set((s) => {
      if (!(policyNumber in s.resuming)) return s;
      const { [policyNumber]: _discard, ...rest } = s.resuming;
      return { resuming: rest };
    }),

  reinstatePolicy: (policyNumber) =>
    track(
      `policy.reinstate.${policyNumber}`,
      getState().reinstating[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ reinstating: { ...s.reinstating, [policyNumber]: next } })),
      async () => {
        const updated = await reinstatePolicy(policyNumber);
        set((s) => ({ detail: { ...s.detail, [policyNumber]: success(updated) } }));
        return updated;
      },
    ),

  resetReinstatePolicy: (policyNumber) =>
    set((s) => {
      if (!(policyNumber in s.reinstating)) return s;
      const { [policyNumber]: _discard, ...rest } = s.reinstating;
      return { reinstating: rest };
    }),

  makePaidUp: (policyNumber) =>
    track(
      `policy.paidUp.${policyNumber}`,
      getState().makingPaidUp[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ makingPaidUp: { ...s.makingPaidUp, [policyNumber]: next } })),
      async () => {
        const updated = await makePaidUp(policyNumber);
        set((s) => ({ detail: { ...s.detail, [policyNumber]: success(updated) } }));
        return updated;
      },
    ),

  loadSurrenderQuote: (policyNumber) =>
    track(
      `policy.surrenderQuote.${policyNumber}`,
      getState().surrenderQuote[policyNumber] ?? idle<SurrenderQuote>(),
      (next) => set((s) => ({ surrenderQuote: { ...s.surrenderQuote, [policyNumber]: next } })),
      () => getSurrenderValue(policyNumber),
    ),

  loadSurrenderRequest: (policyNumber) =>
    track(
      `policy.surrenderRequest.${policyNumber}`,
      getState().surrenderRequest[policyNumber] ?? idle<SurrenderRequestView | null>(),
      (next) => set((s) => ({ surrenderRequest: { ...s.surrenderRequest, [policyNumber]: next } })),
      // 204 when the policy has never had one; the http helper gives back an empty body, and
      // null says "asked, and there is none" rather than "not asked yet".
      async () => (await getSurrenderRequest(policyNumber)) || null,
    ),

  requestSurrender: (policyNumber, payeeRef) =>
    track(
      `policy.requestSurrender.${policyNumber}`,
      getState().requestingSurrender[policyNumber] ?? idle<SurrenderRequestView>(),
      (next) => set((s) => ({ requestingSurrender: { ...s.requestingSurrender, [policyNumber]: next } })),
      async () => {
        const request = await requestSurrender(policyNumber, payeeRef);
        // The panel renders from the loaded request, so the new one lands there too -- otherwise
        // it would show nothing until a reload.
        set((s) => ({ surrenderRequest: { ...s.surrenderRequest, [policyNumber]: success(request) } }));
        return request;
      },
    ),

  approveSurrender: (policyNumber, surrenderRequestId) =>
    track(
      `policy.approveSurrender.${policyNumber}`,
      getState().approvingSurrender[policyNumber] ?? idle<SurrenderRequestView>(),
      (next) => set((s) => ({ approvingSurrender: { ...s.approvingSurrender, [policyNumber]: next } })),
      async () => {
        const approved = await approveSurrender(surrenderRequestId);
        set((s) => ({ surrenderRequest: { ...s.surrenderRequest, [policyNumber]: success(approved) } }));
        // Cover stopped: the policy is SURRENDERED now, and the badge above must say so.
        await getState().loadDetail(policyNumber);
        return approved;
      },
    ),

  loadScheme: (policyNumber) =>
    track(
      `policy.scheme.${policyNumber}`,
      getState().scheme[policyNumber] ?? idle<GroupSchemeView>(),
      (next) => set((s) => ({ scheme: { ...s.scheme, [policyNumber]: next } })),
      () => getGroupScheme(policyNumber),
    ),

  // Keyed by policy number alone, NOT by the filter or page. The key is what
  // sequences superseded requests, and paging or switching the status filter
  // quickly is exactly the case that needs sequencing: it is the same table
  // either way, and only the most recently REQUESTED view should win. Same
  // reasoning as `policy.list`.
  loadMembers: (policyNumber, params = {}) =>
    track(
      `policy.members.${policyNumber}`,
      getState().members[policyNumber] ?? idle<Page<PolicyMemberView>>(),
      (next) => set((s) => ({ members: { ...s.members, [policyNumber]: next } })),
      () => listSchemeMembers(policyNumber, params),
    ),

  issueGroupScheme: (request) =>
    track(
      'policy.issueGroupScheme',
      getState().issuingScheme,
      (next) => set({ issuingScheme: next }),
      () => issueGroupScheme(request),
    ),

  resetIssueGroupScheme: () => set({ issuingScheme: idle() }),

  amendFreeCoverLimit: (policyNumber, fclAmount, reason) =>
    track(
      `policy.amendFreeCoverLimit.${policyNumber}`,
      getState().scheme[policyNumber] ?? idle<GroupSchemeView>(),
      (next) => set((st) => ({ scheme: { ...st.scheme, [policyNumber]: next } })),
      // The amended scheme IS the new scheme resource: the response carries the restated
      // total and the new count above the limit, so tracking it into the same slot means
      // the record rail redraws from the answer rather than from a refetch that could race.
      () => amendFreeCoverLimit(policyNumber, fclAmount, reason),
    ),

  addSchemeMember: (policyNumber, member) =>
    track(
      `policy.addSchemeMember.${policyNumber}`,
      getState().addingMember[policyNumber] ?? idle<PolicyMemberView>(),
      (next) => set((s) => ({ addingMember: { ...s.addingMember, [policyNumber]: next } })),
      async () => {
        const added = await addSchemeMember(policyNumber, member);
        // Adding a life moves the scheme's total AND the master policy's sum
        // assured -- the server restates both in the same transaction. Refetching
        // all three is what stops the summary above the table from contradicting
        // the row that was just inserted into it. Awaited so a caller that closes
        // the form on success never renders the stale totals for a frame.
        await Promise.all([
          getState().loadScheme(policyNumber),
          getState().loadMembers(policyNumber),
          getState().loadDetail(policyNumber),
        ]);
        return added;
      },
    ),

  resetAddSchemeMember: (policyNumber) =>
    set((s) => {
      if (!(policyNumber in s.addingMember)) return s;
      const { [policyNumber]: _discard, ...rest } = s.addingMember;
      return { addingMember: rest };
    }),
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectDetail = (policyNumber: string) => (s: PolicyState) =>
  s.detail[policyNumber] ?? idle<PolicyView>();
/**
 * Keyed by (PARTY id, filter) -- not by a policy number, and not by the party alone. The
 * filter is part of the key because it is sent to the server, so two filters are two
 * different result sets rather than one narrowed locally.
 */
export const claimantPoliciesKey = (partyId: string, q?: string) =>
  `${partyId}|${(q ?? '').trim().toUpperCase()}`;
export const selectClaimantPolicies = (partyId: string, q?: string) => (s: PolicyState) =>
  s.claimantPolicies[claimantPoliciesKey(partyId, q)] ?? idle<Page<PolicyView>>();
/**
 * Coverage is cached per (policy, asOf) pair, not per policy — so that two dates
 * are two cache entries rather than one stale answer.
 *
 * **Nothing passes `asOf` today, deliberately.** `coverage-status` accepts the
 * parameter, echoes it into the response, and ignores it: the query behind it is
 * `findByPolicyNumberAndActiveTrue`, and `policy.coverage` has no
 * `effective_from`/`effective_to` columns to bound. Two dates 25 years apart
 * return identical coverage. Sending it would only make a date-blind answer look
 * date-aware.
 *
 * The keying stays because it is the correct shape for the day the backend can
 * answer the question (recorded in the M13 design spec's open items), and
 * because without it the first caller to pass `asOf` gets a silently wrong
 * cached answer in the one place a wrong date is the whole defect.
 */
function coverageKey(policyNumber: string, asOf?: string): string {
  return asOf ? `${policyNumber}@${asOf}` : policyNumber;
}

export const selectCoverage =
  (policyNumber: string, asOf?: string) =>
  (s: PolicyState): ReturnType<typeof idle<CoverageStatusView>> =>
    s.coverage[coverageKey(policyNumber, asOf)] ?? idle<CoverageStatusView>();
export const selectInvoices = (policyNumber: string) => (s: PolicyState) =>
  s.invoices[policyNumber] ?? idle<InvoiceView[]>();
export const selectLoans = (policyNumber: string) => (s: PolicyState) =>
  s.loans[policyNumber] ?? idle<LoanView[]>();
export const selectSavingBeneficiaries = (policyNumber: string) => (s: PolicyState) =>
  s.savingBeneficiaries[policyNumber] ?? idle<true>();
export const selectSuspending = (policyNumber: string) => (s: PolicyState) =>
  s.suspending[policyNumber] ?? idle<PolicyView>();
export const selectResuming = (policyNumber: string) => (s: PolicyState) =>
  s.resuming[policyNumber] ?? idle<PolicyView>();
export const selectReinstating = (policyNumber: string) => (s: PolicyState) =>
  s.reinstating[policyNumber] ?? idle<PolicyView>();
export const selectMakingPaidUp = (policyNumber: string) => (s: PolicyState) =>
  s.makingPaidUp[policyNumber] ?? idle<PolicyView>();
export const selectSurrenderQuote = (policyNumber: string) => (s: PolicyState) =>
  s.surrenderQuote[policyNumber] ?? idle<SurrenderQuote>();
export const selectSurrenderRequest = (policyNumber: string) => (s: PolicyState) =>
  s.surrenderRequest[policyNumber] ?? idle<SurrenderRequestView | null>();
export const selectRequestingSurrender = (policyNumber: string) => (s: PolicyState) =>
  s.requestingSurrender[policyNumber] ?? idle<SurrenderRequestView>();
export const selectApprovingSurrender = (policyNumber: string) => (s: PolicyState) =>
  s.approvingSurrender[policyNumber] ?? idle<SurrenderRequestView>();
export const selectWaivingInvoice = (invoiceId: string) => (s: PolicyState) =>
  s.waivingInvoice[invoiceId] ?? idle<true>();
export const selectRequestingPayment = (invoiceId: string) => (s: PolicyState) =>
  s.requestingPayment[invoiceId] ?? idle<true>();
export const selectOriginatingLoan = (policyNumber: string) => (s: PolicyState) =>
  s.originatingLoan[policyNumber] ?? idle<true>();
export const selectRepayingLoan = (loanId: string) => (s: PolicyState) =>
  s.repayingLoan[loanId] ?? idle<true>();

export const selectScheme = (policyNumber: string) => (s: PolicyState) =>
  s.scheme[policyNumber] ?? idle<GroupSchemeView>();
export const selectMembers = (policyNumber: string) => (s: PolicyState) =>
  s.members[policyNumber] ?? idle<Page<PolicyMemberView>>();
export const selectIssuingScheme = (s: PolicyState) => s.issuingScheme;
export const selectAddingMember = (policyNumber: string) => (s: PolicyState) =>
  s.addingMember[policyNumber] ?? idle<PolicyMemberView>();
