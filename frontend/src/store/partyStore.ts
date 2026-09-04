import { create } from 'zustand';
import {
  getParty,
  listPartyDocuments,
  registerCorporate,
  registerIndividual,
  searchParties,
  submitKyc,
  uploadKycEvidence,
  type PartyArea,
  type PartySearchParams,
} from '@/api/party';
import type {
  KycEvidenceUploadResponse,
  KycStatus,
  Page,
  PartyDetailView,
  PartyDocumentView,
  PartyView,
  RegisterCorporateRequest,
  RegisterIndividualRequest,
} from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `party` domain store. Most of it is keyed-by-partyId (every party except
 * via `list` is reached by drilling in from an id already on screen), same
 * shape as distribution's agent store.
 */

type Keyed<T> = Record<string, Resource<T>>;

/**
 * Which register the rows belong to. `all` is the unsplit list -- the agents realm's
 * own book, and the retired staff path -- and is a real third table rather than a
 * synonym for either area.
 */
export type PartyListKey = PartyArea | 'all';

interface PartyState {
  /*
   * Keyed by REGISTER AREA, not a single slot.
   *
   * It was one slot with a constant track key, on the stated reasoning that it was
   * "the same on-screen table either way, so only the most recently requested filter
   * should win a race". That was true while Clients was one register and stopped being
   * true the moment it became two: individuals and corporates/groups are two different
   * tables, and one slot let the previous area's rows render under the new area's
   * heading and caption for as long as the next request was in flight. Twenty rows
   * reading "Individual" appeared under a table captioned "Corporate and group
   * clients" -- which is precisely the confusion splitting the register exists to end.
   *
   * Still NOT keyed by kycStatus or q: within one area those are the same table, and
   * the newest request should still win.
   */
  list: Keyed<Page<PartyView>>;
  // PartyDetailView, not PartyView: `GET /parties/{id}` returns the full record
  // while the list stays four fields, so these two are deliberately different
  // types for the same entity.
  detail: Keyed<PartyDetailView>;
  // Its own slot rather than folded into `detail`: the client page's panels load
  // in parallel, so a slow or failing documents read must not blank the identity
  // panel next to it.
  documents: Keyed<PartyDocumentView[]>;
  // Keyed by partyId, separately from `detail` and from each other -- same
  // shape as policyStore's suspending/resuming/reinstating: two distinct
  // mutations against the same entity.
  uploadingKycEvidence: Keyed<KycEvidenceUploadResponse>;
  submittingKyc: Keyed<true>;
  // Single slots, not keyed: each creates a NEW party, so there is no
  // existing id to key against yet -- same shape as distribution's
  // `onboarding` and underwriting's `opening`. Separate slots (rather than
  // one shared "registering") so switching between the Individual/Corporate
  // forms never carries the other form's error or in-flight state.
  registeringIndividual: Resource<PartyView>;
  registeringCorporate: Resource<PartyView>;

  loadList: (area: PartyListKey, params: PartySearchParams) => Promise<void>;
  loadParty: (partyId: string) => Promise<void>;
  loadPartyDocuments: (partyId: string) => Promise<void>;
  uploadKycEvidence: (partyId: string, file: File) => Promise<void>;
  resetUploadKycEvidence: (partyId: string) => void;
  submitKyc: (partyId: string, status: KycStatus, evidenceDocumentRef: string) => Promise<void>;
  resetSubmitKyc: (partyId: string) => void;
  registerIndividual: (request: RegisterIndividualRequest) => Promise<void>;
  resetRegisterIndividual: () => void;
  registerCorporate: (request: RegisterCorporateRequest) => Promise<void>;
  resetRegisterCorporate: () => void;
}

export const usePartyStore = create<PartyState>((set, getState) => ({
  list: {},
  detail: {},
  documents: {},
  uploadingKycEvidence: {},
  submittingKyc: {},
  registeringIndividual: idle(),
  registeringCorporate: idle(),

  // Keyed by area, then constant across that area's own filters: within one area a
  // kycStatus or q change is the same table and only the newest request should win,
  // but a switch BETWEEN areas is a different table and must not inherit the other's
  // rows. See the `list` field's own comment.
  loadList: (area, params) =>
    track(
      `party.list.${area}`,
      getState().list[area] ?? idle<Page<PartyView>>(),
      (next) => set((st) => ({ list: { ...st.list, [area]: next } })),
      () => searchParties(params),
    ),

  loadParty: (partyId) =>
    track(
      `party.detail.${partyId}`,
      getState().detail[partyId] ?? idle<PartyDetailView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [partyId]: next } })),
      () => getParty(partyId),
    ),

  loadPartyDocuments: (partyId) =>
    track(
      `party.documents.${partyId}`,
      getState().documents[partyId] ?? idle<PartyDocumentView[]>(),
      (next) => set((s) => ({ documents: { ...s.documents, [partyId]: next } })),
      () => listPartyDocuments(partyId),
    ),

  uploadKycEvidence: (partyId, file) =>
    track(
      `party.uploadKycEvidence.${partyId}`,
      getState().uploadingKycEvidence[partyId] ?? idle<KycEvidenceUploadResponse>(),
      (next) => set((s) => ({ uploadingKycEvidence: { ...s.uploadingKycEvidence, [partyId]: next } })),
      () => uploadKycEvidence(partyId, file),
    ),

  resetUploadKycEvidence: (partyId) =>
    set((s) => {
      if (!(partyId in s.uploadingKycEvidence)) return s;
      const { [partyId]: _discard, ...rest } = s.uploadingKycEvidence;
      return { uploadingKycEvidence: rest };
    }),

  submitKyc: (partyId, status, evidenceDocumentRef) =>
    track(
      `party.submitKyc.${partyId}`,
      getState().submittingKyc[partyId] ?? idle<true>(),
      (next) => set((s) => ({ submittingKyc: { ...s.submittingKyc, [partyId]: next } })),
      // Explicit Promise<true>: see policyStore.saveBeneficiaries for why the
      // annotation is required to stop TypeScript widening the literal to boolean.
      async (): Promise<true> => {
        await submitKyc(partyId, status, evidenceDocumentRef);
        // The POST returns no body -- refetch to see the real new kycStatus.
        await getState().loadParty(partyId);
        return true;
      },
    ),

  resetSubmitKyc: (partyId) =>
    set((s) => {
      if (!(partyId in s.submittingKyc)) return s;
      const { [partyId]: _discard, ...rest } = s.submittingKyc;
      return { submittingKyc: rest };
    }),

  registerIndividual: (request) =>
    track(
      'party.registerIndividual',
      getState().registeringIndividual,
      (next) => set({ registeringIndividual: next }),
      () => registerIndividual(request),
    ),

  resetRegisterIndividual: () => set({ registeringIndividual: idle() }),

  registerCorporate: (request) =>
    track(
      'party.registerCorporate',
      getState().registeringCorporate,
      (next) => set({ registeringCorporate: next }),
      () => registerCorporate(request),
    ),

  resetRegisterCorporate: () => set({ registeringCorporate: idle() }),
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectPartyList = (area: PartyListKey) => (s: PartyState) =>
  s.list[area] ?? idle<Page<PartyView>>();
export const selectParty = (partyId: string) => (s: PartyState) =>
  s.detail[partyId] ?? idle<PartyDetailView>();
export const selectPartyDocuments = (partyId: string) => (s: PartyState) =>
  s.documents[partyId] ?? idle<PartyDocumentView[]>();
export const selectUploadingKycEvidence = (partyId: string) => (s: PartyState) =>
  s.uploadingKycEvidence[partyId] ?? idle<KycEvidenceUploadResponse>();
export const selectSubmittingKyc = (partyId: string) => (s: PartyState) =>
  s.submittingKyc[partyId] ?? idle<true>();
export const selectRegisteringIndividual = (s: PartyState) => s.registeringIndividual;
export const selectRegisteringCorporate = (s: PartyState) => s.registeringCorporate;
