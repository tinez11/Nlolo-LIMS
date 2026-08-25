import { create } from 'zustand';
import { getParty, submitKyc, uploadKycEvidence } from '@/api/party';
import type { KycEvidenceUploadResponse, KycStatus, PartyView } from '@/api/types';
import { idle, track, type Resource } from './createResourceSlice';

/**
 * The `party` domain store. There is no list/search anywhere on this platform --
 * every party is reached by drilling in from an id already on screen, so this
 * store is entirely keyed-by-partyId, like distribution's agent store.
 */

type Keyed<T> = Record<string, Resource<T>>;

interface PartyState {
  detail: Keyed<PartyView>;
  // Keyed by partyId, separately from `detail` and from each other -- same
  // shape as policyStore's suspending/resuming/reinstating: two distinct
  // mutations against the same entity.
  uploadingKycEvidence: Keyed<KycEvidenceUploadResponse>;
  submittingKyc: Keyed<true>;

  loadParty: (partyId: string) => Promise<void>;
  uploadKycEvidence: (partyId: string, file: File) => Promise<void>;
  resetUploadKycEvidence: (partyId: string) => void;
  submitKyc: (partyId: string, status: KycStatus, evidenceDocumentRef: string) => Promise<void>;
  resetSubmitKyc: (partyId: string) => void;
}

export const usePartyStore = create<PartyState>((set, getState) => ({
  detail: {},
  uploadingKycEvidence: {},
  submittingKyc: {},

  loadParty: (partyId) =>
    track(
      `party.detail.${partyId}`,
      getState().detail[partyId] ?? idle<PartyView>(),
      (next) => set((s) => ({ detail: { ...s.detail, [partyId]: next } })),
      () => getParty(partyId),
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
}));

/** Selectors, so components never index a possibly-absent key by hand. */
export const selectParty = (partyId: string) => (s: PartyState) => s.detail[partyId] ?? idle<PartyView>();
export const selectUploadingKycEvidence = (partyId: string) => (s: PartyState) =>
  s.uploadingKycEvidence[partyId] ?? idle<KycEvidenceUploadResponse>();
export const selectSubmittingKyc = (partyId: string) => (s: PartyState) =>
  s.submittingKyc[partyId] ?? idle<true>();
