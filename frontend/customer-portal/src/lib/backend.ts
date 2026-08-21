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

  return doFetch(buildUrl(baseUrl, path, init.searchParams), {
    method: init.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
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
