/**
 * Idempotency keys.
 *
 * Six endpoints HARD-REQUIRE `Idempotency-Key` and return 400 without it, so a
 * submit button that omits the header is simply broken. Six more accept it and
 * ignore it.
 *
 * The key is minted ONCE per user intent, by the store action that owns the
 * mutation, and reused for every retry of that intent. This is the whole point of
 * the mechanism: an Axios interceptor that generated a key per *request* would mint
 * a fresh one on the retry after a network timeout -- turning one payment into two
 * at exactly the moment the guarantee is needed.
 */

export const IDEMPOTENCY_HEADER = 'Idempotency-Key';

/** Endpoints that 400 without the header. Paths are matched after the optional
 *  `/api` dev-proxy prefix is stripped. */
const REQUIRED: readonly RegExp[] = [
  /^\/invoices\/[^/]+\/payment-request$/,
  /^\/claims$/,
  /^\/agents$/,
  /^\/agents\/[^/]+\/commission-statements\/[^/]+\/payout$/,
  /^\/claims\/[^/]+\/recoveries\/[^/]+\/confirm$/,
  /^\/treaties$/,
];

/** True when the backend will reject this request without an Idempotency-Key. */
export function requiresIdempotencyKey(method: string, url: string): boolean {
  if (method.toLowerCase() !== 'post') return false;
  const path = url.split('?')[0]?.replace(/^\/api(?=\/)/, '') ?? '';
  return REQUIRED.some((re) => re.test(path));
}

function newKey(): string {
  // randomUUID needs a secure context; fall back rather than crash a submit.
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; // version 4
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // variant 10
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export interface MutationAttempt {
  /** Stable for the lifetime of this attempt, including retries. */
  readonly key: string;
  /** Headers to spread onto the request. */
  headers(): Record<string, string>;
}

/**
 * Begin one user intent. Call this once in the store action, then pass the same
 * attempt to every retry.
 */
export function startMutation(): MutationAttempt {
  const key = newKey();
  return {
    key,
    headers: () => ({ [IDEMPOTENCY_HEADER]: key }),
  };
}
