import { get, post } from '@/lib/http';
import type {
  KycEvidenceUploadResponse,
  KycStatus,
  Page,
  PartyView,
  RegisterCorporateRequest,
  RegisterIndividualRequest,
} from './types';

/**
 * Party read/write surface, hand-written for the same reasons as api/policies.ts.
 *
 * Most of a party is still only reachable by drilling in from an id already on
 * screen (a policy's policyholderPartyId, a claim's claimantPartyId, an
 * underwriting case's applicantPartyId, an agent's own partyId) -- but
 * `searchParties` below is the one exception: a real list/search endpoint,
 * added specifically because a party PENDING KYC with nothing yet referencing
 * it (a fresh self-service or agent-assisted registration) was otherwise
 * invisible to staff, with no way to find it to review at all.
 */

/** `GET /parties/{partyId}` -- staff/agents/own-customer only. */
export function getParty(partyId: string): Promise<PartyView> {
  return get<PartyView>(`/parties/${encodeURIComponent(partyId)}`);
}

export interface PartySearchParams {
  kycStatus?: KycStatus;
  q?: string;
  page?: number;
  pageSize?: number;
}

export const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

/**
 * `GET /parties` -- staff filter freely (an unrestricted KYC review queue);
 * an agents-realm caller is force-scoped server-side to parties IT
 * registered, never client-supplied. Same anonymous-envelope normalization
 * as `api/policies.ts#searchPolicies`.
 */
export async function searchParties(params: PartySearchParams = {}): Promise<Page<PartyView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: PartyView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/parties', {
    params: {
      ...(params.kycStatus ? { kycStatus: params.kycStatus } : {}),
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

/**
 * `POST /parties/individuals` -- customer self-service, agent-assisted, or
 * (as of the staff-portal review) staff-assisted. Returns the new party
 * PENDING KYC -- registration and verification are always two separate steps.
 */
export function registerIndividual(request: RegisterIndividualRequest): Promise<PartyView> {
  return post<PartyView>('/parties/individuals', request);
}

/** `POST /parties/corporates` -- agent or staff only, never self-service. */
export function registerCorporate(request: RegisterCorporateRequest): Promise<PartyView> {
  return post<PartyView>('/parties/corporates', request);
}
