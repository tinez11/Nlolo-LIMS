import axios, { type AxiosRequestConfig } from 'axios';
import { toApiError, type ApiError } from './apiError';
import { IDEMPOTENCY_HEADER, requiresIdempotencyKey } from './idempotency';

/**
 * The single Axios instance every domain client uses.
 *
 * baseURL comes from the environment and defaults to the dev proxy path. It is
 * deliberately NOT read from the specs' `servers` block: those declare
 * `https://api.nlolo-lifeplatform.tz/v1`, but the backend sets no
 * `server.servlet.context-path` and serves at the root, so a client trusting
 * `servers[0]` 404s on every call. Whether `/v1` exists is a deployment concern.
 *
 * In dev the browser talks to the Vite proxy so it stays same-origin -- the
 * platform has no CORS configuration at all, and a direct cross-origin call to
 * :8080 is blocked outright.
 */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  headers: { Accept: 'application/json' },
  // Tokens are Bearer, not cookies, and there is no cookie auth to send.
  withCredentials: false,
});

// --- token wiring -----------------------------------------------------------
// The auth layer registers a provider rather than this module importing it, which
// would be a cycle. Tokens are held in memory only (see auth/userStore.ts).

let accessTokenProvider: () => string | undefined = () => undefined;
let onUnauthenticated: (() => void) | undefined;

export function setAccessTokenProvider(provider: () => string | undefined): void {
  accessTokenProvider = provider;
}

/** Called on a 401 so the auth layer can attempt a silent renew. */
export function setUnauthenticatedHandler(handler: () => void): void {
  onUnauthenticated = handler;
}

http.interceptors.request.use((config) => {
  const token = accessTokenProvider();
  if (token) config.headers.Authorization = `Bearer ${token}`;

  // Dev-time guard, not a key generator. Auto-generating here would mint a fresh
  // key on every retry and defeat the whole mechanism -- the key belongs to the
  // user intent, which only the store action knows about.
  if (import.meta.env.DEV) {
    const method = config.method ?? 'get';
    const url = config.url ?? '';
    if (requiresIdempotencyKey(method, url) && !config.headers[IDEMPOTENCY_HEADER]) {
      throw new Error(
        `${method.toUpperCase()} ${url} requires an ${IDEMPOTENCY_HEADER} header and none was set. ` +
          `Mint one with startMutation() in the store action so retries reuse it.`,
      );
    }
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (cause: unknown) => {
    const error = toApiError(cause);
    if (error.kind === 'unauthenticated') onUnauthenticated?.();
    // Every caller downstream sees an ApiError, never an AxiosError.
    return Promise.reject(error);
  },
);

/** GET helper that returns the body and throws ApiError. */
export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.get<T>(url, config);
  return response.data;
}

/** POST helper that returns the body and throws ApiError. */
export async function post<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response = await http.post<T>(url, body, config);
  return response.data;
}

/** PUT helper that returns the body and throws ApiError. */
export async function put<T>(
  url: string,
  body?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response = await http.put<T>(url, body, config);
  return response.data;
}

export type { ApiError };
