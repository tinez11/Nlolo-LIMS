import { create } from 'zustand';
import {
  getCoverageStatus,
  getPolicy,
  issuePolicy,
  listInvoices,
  listLoans,
  reinstatePolicy,
  replaceBeneficiaries,
  requestPaymentForInvoice,
  resumePolicy,
  searchPolicies,
  suspendPolicy,
  waiveInvoice,
  type PolicySearchParams,
} from '@/api/policies';
import type {
  BeneficiaryInput,
  CoverageStatusView,
  InvoiceView,
  LoanView,
  ManualIssueRequest,
  Page,
  PaymentRequest,
  PolicyView,
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
  // Each keyed by policyNumber, separately from `detail` and from each other --
  // same shape as claims' submittingAssessment/decidingSettlement/reopening: three
  // distinct lifecycle actions on the same entity, each its own tracked mutation.
  suspending: Keyed<PolicyView>;
  resuming: Keyed<PolicyView>;
  reinstating: Keyed<PolicyView>;

  loadList: (params: PolicySearchParams) => Promise<void>;
  loadDetail: (policyNumber: string) => Promise<void>;
  loadCoverage: (policyNumber: string) => Promise<void>;
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
  suspendPolicy: (policyNumber: string, request: SuspendPolicyRequest) => Promise<void>;
  resetSuspendPolicy: (policyNumber: string) => void;
  resumePolicy: (policyNumber: string) => Promise<void>;
  resetResumePolicy: (policyNumber: string) => void;
  reinstatePolicy: (policyNumber: string) => Promise<void>;
  resetReinstatePolicy: (policyNumber: string) => void;
}

export const usePolicyStore = create<PolicyState>((set, getState) => ({
  list: idle(),
  detail: {},
  coverage: {},
  invoices: {},
  loans: {},
  savingBeneficiaries: {},
  issuing: idle(),
  waivingInvoice: {},
  requestingPayment: {},
  suspending: {},
  resuming: {},
  reinstating: {},

  // Every `track` call below is keyed so a slower, superseded request can never
  // overwrite a faster, newer one -- e.g. clicking through status filter chips
  // quickly, where network timing has no relationship to click order. The list key
  // is constant regardless of which filter was requested: it is the same on-screen
  // table either way, and only the most recently REQUESTED filter should win.
  loadList: (params) =>
    track(
      'policy.list',
      getState().list,
      (next) => set({ list: next }),
      () => searchPolicies(params),
    ),

  loadDetail: (policyNumber) =>
    track(
      `policy.detail.${policyNumber}`,
      getState().detail[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [policyNumber]: next } })),
      () => getPolicy(policyNumber),
    ),

  loadCoverage: (policyNumber) =>
    track(
      `policy.coverage.${policyNumber}`,
      getState().coverage[policyNumber] ?? idle<CoverageStatusView>(),
      (next) => set((s) => ({ coverage: { ...s.coverage, [policyNumber]: next } })),
      () => getCoverageStatus(policyNumber),
    ),

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
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectDetail = (policyNumber: string) => (s: PolicyState) =>
  s.detail[policyNumber] ?? idle<PolicyView>();
export const selectCoverage = (policyNumber: string) => (s: PolicyState) =>
  s.coverage[policyNumber] ?? idle<CoverageStatusView>();
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
export const selectWaivingInvoice = (invoiceId: string) => (s: PolicyState) =>
  s.waivingInvoice[invoiceId] ?? idle<true>();
export const selectRequestingPayment = (invoiceId: string) => (s: PolicyState) =>
  s.requestingPayment[invoiceId] ?? idle<true>();
