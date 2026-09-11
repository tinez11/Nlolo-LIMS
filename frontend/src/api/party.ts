import { get, post, put } from '@/lib/http';
import type {
  AmendCorporateRequest,
  KycEvidenceUploadResponse,
  KycStatus,
  Page,
  PartyDetailView,
  PartyDocumentView,
  PartyType,
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

/**
 * `GET /parties/{partyId}` -- the FULL record, not the four-field `PartyView` the
 * list returns. Date of birth, registration number, phone, email, KYC decision
 * time and who registered them: all stored since the first migration, and until
 * this returned `PartyDetailView` none of it was readable through any endpoint.
 *
 * Object-level scoping applies to both non-staff realms and is enforced
 * server-side: a customer may read only itself, an agent only a client it
 * registered. Either refusal is a real 403, so a UI must be prepared to render
 * one rather than assuming a party it can see in a list it can also open.
 */
export function getParty(partyId: string): Promise<PartyDetailView> {
  return get<PartyDetailView>(`/parties/${encodeURIComponent(partyId)}`);
}

/**
 * `GET /parties/{partyId}/documents` -- metadata only, newest first.
 *
 * Lives on the party, not on the document module: authorizing a document means
 * asking its owning aggregate, and `document` cannot depend back on `party`
 * without a module cycle -- which is also why the generic document endpoints are
 * staff-only and this one can serve agents.
 *
 * Covers KYC evidence and anything else filed under `party:{id}`. Evidence
 * attached to the client's CLAIMS lives under `claim:{id}` and is fetched per
 * claim from `GET /claims/{claimId}/evidence`; this endpoint deliberately does
 * not reach across to it, so a documents panel that wants both must compose them.
 */
export function listPartyDocuments(partyId: string): Promise<PartyDocumentView[]> {
  return get<PartyDocumentView[]>(`/parties/${encodeURIComponent(partyId)}/documents`);
}

/**
 * The two working areas of the client register, as the set of party types each one is.
 *
 * `INDIVIDUAL` and `CORPORATE`/`GROUP` are separated because they are separated in the
 * work: a natural person's KYC is an ID scan and a date of birth, an organisation's is a
 * registration number and a certificate, and the two are reviewed by different people
 * against different evidence. The grouping lives here rather than in a screen so both
 * areas name the same thing.
 *
 * `organisations` is CORPORATE **and** GROUP together on purpose. No GROUP party can be
 * created over HTTP today (`registerGroup` has no endpoint), so it contributes no rows
 * yet -- but naming it now means the area does not silently start omitting groups the day
 * it can.
 */
export const PARTY_AREAS = {
  individuals: ['INDIVIDUAL'],
  organisations: ['CORPORATE', 'GROUP'],
} as const satisfies Record<string, readonly PartyType[]>;

export type PartyArea = keyof typeof PARTY_AREAS;

export interface PartySearchParams {
  kycStatus?: KycStatus;
  q?: string;
  /**
   * Restricts the result to these party types. Serialised comma-separated, which is the
   * form the endpoint declares -- see its `partyType` parameter for why it is a
   * pattern-constrained string rather than an array.
   */
  partyTypes?: readonly PartyType[];
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
      // Omitted when empty rather than sent as an empty string: absent means "every
      // type", and `partyType=` would fail the endpoint's own pattern.
      ...(params.partyTypes && params.partyTypes.length > 0
        ? { partyType: params.partyTypes.join(',') }
        : {}),
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

/**
 * `PUT /parties/individuals/{partyId}` -- correct what is recorded about a person.
 *
 * STAFF ONLY, unlike registration, which agents and customers may also do: creating your own
 * record is not the same act as rewriting one.
 *
 * A full replacement, which is why it takes the same request shape as registration. An omitted
 * field CLEARS the value -- there is no patch semantics here, deliberately, so that "remove the
 * employer I recorded by mistake" is expressible at all.
 *
 * KYC status is untouched by this. KYC is the passport: it verifies that this person is who they
 * say they are, and correcting their address does not un-verify the document that was checked.
 */
export function amendIndividual(
  partyId: string,
  request: RegisterIndividualRequest,
): Promise<PartyDetailView> {
  return put<PartyDetailView>(`/parties/individuals/${encodeURIComponent(partyId)}`, request);
}

/**
 * `PUT /parties/corporates/{partyId}` -- the same act, for a company or a group.
 *
 * Note there is NO registrationNumber: a company's registration number is its identity in the
 * national register, and an edit form must not be a route to becoming a different company.
 */
export function amendCorporate(
  partyId: string,
  request: AmendCorporateRequest,
): Promise<PartyDetailView> {
  return put<PartyDetailView>(`/parties/corporates/${encodeURIComponent(partyId)}`, request);
}
