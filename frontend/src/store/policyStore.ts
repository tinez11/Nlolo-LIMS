import { create } from 'zustand';
import {
  getCoverageStatus,
  getPolicy,
  listInvoices,
  listLoans,
  replaceBeneficiaries,
  searchPolicies,
  type PolicySearchParams,
} from '@/api/policies';
import type {
  BeneficiaryInput,
  CoverageStatusView,
  InvoiceView,
  LoanView,
  Page,
  PolicyView,
} from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

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

  loadList: (params: PolicySearchParams) => Promise<void>;
  loadDetail: (policyNumber: string) => Promise<void>;
  loadCoverage: (policyNumber: string) => Promise<void>;
  loadInvoices: (policyNumber: string) => Promise<void>;
  loadLoans: (policyNumber: string) => Promise<void>;
  saveBeneficiaries: (policyNumber: string, beneficiaries: BeneficiaryInput[]) => Promise<void>;
}

export const usePolicyStore = create<PolicyState>((set, getState) => ({
  list: idle(),
  detail: {},
  coverage: {},
  invoices: {},
  loans: {},
  savingBeneficiaries: {},

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
