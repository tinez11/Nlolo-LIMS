/**
 * Layer 2 of the hard guard from spec §6: a server-side atomic dedup claim, held in Redis, for the
 * two backend endpoints that accept `Idempotency-Key` and never read it (loan origination, loan
 * repayment). Layer 1 (the useRef guard) dies with the tab; this survives a refresh, a second tab,
 * and anything that is not the portal's own UI.
 *
 * The claim MUST be one atomic operation (SET .. NX EX). A GET-then-SET would have exactly the
 * check-then-act race this layer exists to prevent.
 */
export type IdempotencyStore = {
  /** SET key value NX EX ttl — true when this caller won the claim. */
  setIfAbsent(key: string, value: string, ttlSeconds: number): Promise<boolean>;
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  del(key: string): Promise<void>;
};

export const CLAIM_TTL_SECONDS = 900;
const IN_FLIGHT = 'in-flight';

export function idempotencyKeyFor(parts: {
  realm: string; subject: string; operation: string; clientKey: string;
}): string {
  // Namespaced by realm + token subject so one customer's key can never collide with, or replay,
  // another's.
  return `idem:${parts.realm}:${parts.subject}:${parts.operation}:${parts.clientKey}`;
}

export type ClaimResult =
  | { claimed: true }
  | { claimed: false; outcome: string | null };

export async function claimIdempotency(store: IdempotencyStore, key: string): Promise<ClaimResult> {
  const won = await store.setIfAbsent(key, IN_FLIGHT, CLAIM_TTL_SECONDS);
  if (won) {
    return { claimed: true };
  }
  const existing = await store.get(key);
  return { claimed: false, outcome: existing === IN_FLIGHT ? null : existing };
}

/** Success, or any definitive response that CHANGED state: keep the claim, store the outcome. */
export async function recordOutcome(store: IdempotencyStore, key: string, outcome: string): Promise<void> {
  await store.set(key, outcome);
}

/**
 * A definitive rejection that changed nothing (422, a business 409 the user can act on): drop the
 * claim so the user can correct and resubmit immediately. NOT for indeterminate outcomes
 * (timeout, 5xx) — those keep the claim, because the request may in fact have been applied.
 */
export async function releaseIdempotency(store: IdempotencyStore, key: string): Promise<void> {
  await store.del(key);
}
