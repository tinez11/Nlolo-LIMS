import { create } from 'zustand';
import {
  getCoverageStatus,
  getPolicy,
  listInvoices,
  listLoans,
  searchPolicies,
  type PolicySearchParams,
} from '@/api/policies';
import type { CoverageStatusView, InvoiceView, LoanView, Page, PolicyView } from '@/api/types';
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

  loadList: (params: PolicySearchParams) => Promise<void>;
  loadDetail: (policyNumber: string) => Promise<void>;
  loadCoverage: (policyNumber: string) => Promise<void>;
  loadInvoices: (policyNumber: string) => Promise<void>;
  loadLoans: (policyNumber: string) => Promise<void>;
}

export const usePolicyStore = create<PolicyState>((set, getState) => ({
  list: idle(),
  detail: {},
  coverage: {},
  invoices: {},
  loans: {},

  loadList: (params) =>
    track(
      getState().list,
      (next) => set({ list: next }),
      () => searchPolicies(params),
    ),

  loadDetail: (policyNumber) =>
    track(
      getState().detail[policyNumber] ?? idle<PolicyView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [policyNumber]: next } })),
      () => getPolicy(policyNumber),
    ),

  loadCoverage: (policyNumber) =>
    track(
      getState().coverage[policyNumber] ?? idle<CoverageStatusView>(),
      (next) => set((s) => ({ coverage: { ...s.coverage, [policyNumber]: next } })),
      () => getCoverageStatus(policyNumber),
    ),

  loadInvoices: (policyNumber) =>
    track(
      getState().invoices[policyNumber] ?? idle<InvoiceView[]>(),
      (next) => set((s) => ({ invoices: { ...s.invoices, [policyNumber]: next } })),
      () => listInvoices(policyNumber),
    ),

  loadLoans: (policyNumber) =>
    track(
      getState().loans[policyNumber] ?? idle<LoanView[]>(),
      (next) => set((s) => ({ loans: { ...s.loans, [policyNumber]: next } })),
      () => listLoans(policyNumber),
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
