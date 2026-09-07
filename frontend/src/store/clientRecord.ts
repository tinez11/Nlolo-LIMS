import { create } from 'zustand';
import { listCases } from '@/api/underwriting';
import { searchClaims } from '@/api/claims';
import { beneficiaryOf } from '@/api/policies';
import { listSchemeMembers, searchPolicies } from '@/api/policies';
import { listAgents } from '@/api/distribution';
import type {
  AgentView,
  BeneficiaryOfView,
  ClaimView,
  Page,
  PolicyMemberView,
  PolicyView,
  UnderwritingCaseView,
} from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * Everything the client record shows ABOUT one person, keyed by party id.
 *
 * A store of its own rather than five new slots spread across policyStore,
 * claimStore, underwritingStore and distributionStore. Those stores are keyed by
 * their own entity -- a policy number, a claim id, a case id -- and these are all
 * keyed by the PERSON the answer is about. Threading a party-keyed slot into each
 * of them would put four unrelated slices in four files under one screen's
 * lifecycle, and each would sit next to a same-named single-slot list it must never
 * be confused with: `policyStore.list` IS the Policies screen, and writing this
 * page's results into it would blank that screen behind the reader's back.
 *
 * The panels load in PARALLEL and each keeps its own status, so one slow or failing
 * read cannot blank the six beside it -- an underwriting service that is down should
 * cost the reader that panel, not the client's contact details.
 *
 * Every read here is scoped server-side for an agents-realm caller, and the scopes
 * differ in ways a reader can see:
 *   - policies and claims are scoped to the agent's HIERARCHY TEAM's book, so a
 *     client they registered whose policy another agent wrote shows an empty panel;
 *   - underwriting cases are scoped to applicants they REGISTERED, which is the
 *     same set this register is built on;
 *   - beneficiary-of 403s outright for a client they did not register.
 * That is why the policies panel is titled "Your Policies" on the agent screen: the
 * absence means "not in your book", not "none exist".
 */

type Keyed<T> = Record<string, Resource<T>>;

/** One page is the whole panel: a client with more than this many is a scroll, not a pager. */
const PANEL_PAGE_SIZE = 50;

/**
 * How many lives the group-schemes panel previews inline before deferring to the
 * schedule itself. Enough to prove the schedule is populated and to recognise a name;
 * a 500-life roll is not a thing to read on somebody else's record page.
 */
export const SCHEME_MEMBER_PREVIEW = 5;

interface ClientRecordState {
  policies: Keyed<Page<PolicyView>>;
  claims: Keyed<Page<ClaimView>>;
  cases: Keyed<Page<UnderwritingCaseView>>;
  beneficiaryOf: Keyed<BeneficiaryOfView[]>;
  agentRecords: Keyed<Page<AgentView>>;
  /**
   * The opening lives of this client's ONE group scheme, keyed by party id.
   *
   * Its own slot rather than reusing `policyStore.members`, which the scheme page owns:
   * that slot is keyed by policy number and NOT by the requested page size, so a
   * five-row preview written into it would leave the scheme page briefly showing five
   * of five hundred with a pager agreeing.
   */
  schemeMembers: Keyed<Page<PolicyMemberView>>;

  loadPolicies: (partyId: string) => Promise<void>;
  loadClaims: (partyId: string) => Promise<void>;
  loadCases: (partyId: string) => Promise<void>;
  loadBeneficiaryOf: (partyId: string) => Promise<void>;
  loadAgentRecords: (partyId: string) => Promise<void>;
  loadSchemeMembers: (partyId: string, policyNumber: string) => Promise<void>;
}

export const useClientRecordStore = create<ClientRecordState>((set, getState) => ({
  policies: {},
  claims: {},
  cases: {},
  beneficiaryOf: {},
  agentRecords: {},
  schemeMembers: {},

  loadPolicies: (partyId) =>
    track(
      `client.policies.${partyId}`,
      getState().policies[partyId] ?? idle<Page<PolicyView>>(),
      (next) => set((s) => ({ policies: { ...s.policies, [partyId]: next } })),
      () => searchPolicies({ policyholderPartyId: partyId, pageSize: PANEL_PAGE_SIZE }),
    ),

  loadClaims: (partyId) =>
    track(
      `client.claims.${partyId}`,
      getState().claims[partyId] ?? idle<Page<ClaimView>>(),
      (next) => set((s) => ({ claims: { ...s.claims, [partyId]: next } })),
      () => searchClaims({ claimantPartyId: partyId, pageSize: PANEL_PAGE_SIZE }),
    ),

  loadCases: (partyId) =>
    track(
      `client.cases.${partyId}`,
      getState().cases[partyId] ?? idle<Page<UnderwritingCaseView>>(),
      (next) => set((s) => ({ cases: { ...s.cases, [partyId]: next } })),
      () => listCases({ applicantPartyId: partyId, pageSize: PANEL_PAGE_SIZE }),
    ),

  loadBeneficiaryOf: (partyId) =>
    track(
      `client.beneficiaryOf.${partyId}`,
      getState().beneficiaryOf[partyId] ?? idle<BeneficiaryOfView[]>(),
      (next) => set((s) => ({ beneficiaryOf: { ...s.beneficiaryOf, [partyId]: next } })),
      () => beneficiaryOf(partyId),
    ),

  loadAgentRecords: (partyId) =>
    track(
      `client.agentRecords.${partyId}`,
      getState().agentRecords[partyId] ?? idle<Page<AgentView>>(),
      (next) => set((s) => ({ agentRecords: { ...s.agentRecords, [partyId]: next } })),
      () => listAgents({ partyId, pageSize: PANEL_PAGE_SIZE }),
    ),

  /*
   * Keyed by PARTY, not by policy, because that is the question being asked: "who is
   * covered under this client's scheme". Only ever called for a client with exactly one
   * group scheme -- with two, there is no single roll to preview and the panel lists the
   * schemes instead.
   */
  loadSchemeMembers: (partyId, policyNumber) =>
    track(
      `client.schemeMembers.${partyId}`,
      getState().schemeMembers[partyId] ?? idle<Page<PolicyMemberView>>(),
      (next) => set((s) => ({ schemeMembers: { ...s.schemeMembers, [partyId]: next } })),
      () => listSchemeMembers(policyNumber, { pageSize: SCHEME_MEMBER_PREVIEW }),
    ),
}));

export const selectClientPolicies = (partyId: string) => (s: ClientRecordState) =>
  s.policies[partyId] ?? idle<Page<PolicyView>>();
export const selectClientClaims = (partyId: string) => (s: ClientRecordState) =>
  s.claims[partyId] ?? idle<Page<ClaimView>>();
export const selectClientCases = (partyId: string) => (s: ClientRecordState) =>
  s.cases[partyId] ?? idle<Page<UnderwritingCaseView>>();
export const selectClientBeneficiaryOf = (partyId: string) => (s: ClientRecordState) =>
  s.beneficiaryOf[partyId] ?? idle<BeneficiaryOfView[]>();
export const selectClientAgentRecords = (partyId: string) => (s: ClientRecordState) =>
  s.agentRecords[partyId] ?? idle<Page<AgentView>>();
export const selectClientSchemeMembers = (partyId: string) => (s: ClientRecordState) =>
  s.schemeMembers[partyId] ?? idle<Page<PolicyMemberView>>();
