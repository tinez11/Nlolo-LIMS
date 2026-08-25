import { get, post } from '@/lib/http';
import type { KycEvidenceUploadResponse, KycStatus, PartyView } from './types';

/**
 * Party read/write surface, hand-written for the same reasons as api/policies.ts.
 *
 * There is no `GET /parties` list or search endpoint anywhere on the platform --
 * a party is only ever reachable by drilling in from an id already on screen
 * (a policy's policyholderPartyId, a claim's claimantPartyId, an underwriting
 * case's applicantPartyId, an agent's own partyId).
 */

/** `GET /parties/{partyId}` -- staff/agents/own-customer only. */
export function getParty(partyId: string): Promise<PartyView> {
  return get<PartyView>(`/parties/${encodeURIComponent(partyId)}`);
}

/**
 * `POST /parties/{partyId}/kyc-evidence` -- staff only. Uploads a file (ID scan,
 * proof of address) and returns its documentRef -- pass that to `submitKyc`
 * below to record a decision against it. Rejected with a real 400 for a
 * content type outside the platform's closed 4-value allowlist.
 */
export function uploadKycEvidence(partyId: string, file: File): Promise<KycEvidenceUploadResponse> {
  const form = new FormData();
  form.append('file', file);
  return post<KycEvidenceUploadResponse>(`/parties/${encodeURIComponent(partyId)}/kyc-evidence`, form);
}

/** `POST /parties/{partyId}/kyc` -- staff only. Returns 200 with no body, so the
 *  only way to see the effect is to refetch the party. */
export function submitKyc(
  partyId: string,
  status: KycStatus,
  evidenceDocumentRef: string,
): Promise<void> {
  return post<void>(`/parties/${encodeURIComponent(partyId)}/kyc`, { status, evidenceDocumentRef });
}
