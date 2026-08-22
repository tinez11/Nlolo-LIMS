import 'server-only';
import { getToken } from 'next-auth/jwt';
import { headers } from 'next/headers';
import type { ApiProblem } from '@/lib/problem';

/**
 * The single path from this portal to the Spring backend. Every Route Handler and Server Action
 * goes through here, which is what keeps the access token server-side and gives ProblemDetails
 * exactly one decode site.
 *
 * `server-only` is imported for its build-time effect: if any Client Component ever imports this
 * module (directly or transitively), the build FAILS rather than shipping a bundle that would try
 * to talk to the backend from the browser — which cannot work anyway, since the backend declares
 * no CORS policy, and would leak the token if it did.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ApiProblem | null,
  ) {
    super(problem?.title ?? `Backend responded ${status}`);
    this.name = 'ApiError';
  }
}

export type BackendInit = {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  searchParams?: Record<string, string | number | undefined>;
  /** Injected in tests and by callers that already hold a token; otherwise read from the session. */
  accessToken?: string;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
};

async function resolveAccessToken(explicit?: string): Promise<string> {
  if (explicit) return explicit;
  // getToken(), NOT auth() -- auth() resolves through the session() callback, which deliberately
  // never exposes accessToken (see Task 5). getToken() reads the raw jwt()-callback payload
  // directly from the encrypted session cookie, server-side only.
  //
  // Calling convention verified against the installed next-auth@5.0.0-beta.32 (re-exporting
  // @auth/core/jwt): `GetTokenParams.req` is typed as `Request | { headers: Headers |
  // Record<string, string> }` -- there is no `cookies` field in the real signature, and
  // getToken() never calls next/headers' cookies() API. Internally it reads the raw `cookie`
  // request header off `req.headers` itself (`parseCookie(headers.get('cookie') ?? '')`), so
  // passing `next/headers`'s `headers()` result -- which already includes the incoming `cookie`
  // header -- is sufficient on its own. `ReadonlyHeaders` (the return type of `headers()`)
  // extends the DOM `Headers` type, so it satisfies the parameter type directly with no `as
  // never` cast needed. The brief's illustrative shape (`{ headers, cookies } as never`) does
  // not match this beta's actual type or runtime behavior -- `cookies` is simply unused -- so it
  // was dropped here.
  const token = (await getToken({
    req: { headers: await headers() },
    secret: process.env.AUTH_SECRET,
  })) as { accessToken?: string; error?: string } | null;
  if (!token?.accessToken || token.error) {
    // Middleware normally redirects before this, but a Route Handler can still be hit directly.
    throw new ApiError(401, null);
  }
  return token.accessToken;
}

/**
 * Extracts the caller's own `party_id` claim from the access token this portal already holds
 * server-side. Task 11 needs this ONLY for claim registration: unlike `GET /claims`'s
 * `claimantPartyId` filter (force-overridden server-side, so the portal never sends it at all),
 * `POST /claims` VALIDATES the request body's `claimantPartyId` against the token's own claim and
 * 403s on a mismatch, rather than rewriting it (verified against
 * `ClaimController.enforceCustomerOwnClaimantOnly`) -- so the field is required, and the ONLY safe
 * source for it is the verified token, never the browser.
 *
 * No local signature verification is performed: this token is the same one every other call in
 * this file sends as the Authorization bearer, and the backend re-validates its signature on every
 * request anyway, so a tampered claim here could only ever produce a 401 from the backend, never a
 * successful call under a forged identity.
 */
export async function resolveOwnPartyId(init: Pick<BackendInit, 'accessToken'> = {}): Promise<string> {
  const accessToken = await resolveAccessToken(init.accessToken);
  const payload = accessToken.split('.')[1];
  let partyId: unknown;
  try {
    partyId = payload
      ? (JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as { party_id?: unknown }).party_id
      : undefined;
  } catch {
    partyId = undefined;
  }
  if (typeof partyId !== 'string' || !partyId) {
    throw new ApiError(401, null);
  }
  return partyId;
}

function buildUrl(baseUrl: string, path: string, searchParams?: BackendInit['searchParams']): string {
  if (!searchParams) return `${baseUrl}${path}`;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(searchParams)) {
    if (value !== undefined) params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `${baseUrl}${path}?${query}` : `${baseUrl}${path}`;
}

export async function callBackendRaw(path: string, init: BackendInit = {}): Promise<Response> {
  const baseUrl = init.baseUrl ?? process.env.BACKEND_BASE_URL!;
  const doFetch = init.fetchImpl ?? fetch;
  const accessToken = await resolveAccessToken(init.accessToken);

  // FormData (claim-evidence upload, task 11) must reach `fetch` unchanged: `fetch` computes the
  // multipart boundary itself only when IT sets the Content-Type header, so a body that is already
  // a FormData instance is passed straight through with no JSON.stringify and no Content-Type of
  // our own -- setting 'multipart/form-data' by hand here, without the boundary parameter, would
  // break every upload.
  const isFormData = init.body instanceof FormData;

  return doFetch(buildUrl(baseUrl, path, init.searchParams), {
    method: init.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(init.body !== undefined && !isFormData ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
    body: init.body === undefined
      ? undefined
      : isFormData
        ? (init.body as FormData)
        : JSON.stringify(init.body),
    cache: 'no-store',
  });
}

export async function callBackend<T>(path: string, init: BackendInit = {}): Promise<T> {
  const response = await callBackendRaw(path, init);

  if (!response.ok) {
    let problem: ApiProblem | null = null;
    try {
      problem = (await response.json()) as ApiProblem;
    } catch {
      problem = null; // HTML error page, empty body, gateway failure
    }
    throw new ApiError(response.status, problem);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
