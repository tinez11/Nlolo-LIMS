import { describe, expect, it, vi } from 'vitest';
import {
  claimIdempotency, idempotencyKeyFor, recordOutcome, releaseIdempotency, type IdempotencyStore,
} from './idempotency';

function fakeStore(): IdempotencyStore & { setCalls: unknown[][] } {
  const data = new Map<string, string>();
  const setCalls: unknown[][] = [];
  return {
    setCalls,
    async setIfAbsent(key, value, ttlSeconds) {
      setCalls.push([key, value, ttlSeconds]);
      if (data.has(key)) return false;
      data.set(key, value);
      return true;
    },
    async get(key) { return data.get(key) ?? null; },
    async set(key, value) { data.set(key, value); },
    async del(key) { data.delete(key); },
  };
}

describe('idempotencyKeyFor', () => {
  it('namespaces by realm, subject and operation so keys cannot collide across users', () => {
    const a = idempotencyKeyFor({ realm: 'customers', subject: 'user-a', operation: 'loan-origination', clientKey: 'k' });
    const b = idempotencyKeyFor({ realm: 'customers', subject: 'user-b', operation: 'loan-origination', clientKey: 'k' });
    expect(a).not.toBe(b);
    expect(a).toContain('customers');
    expect(a).toContain('loan-origination');
  });
});

describe('claimIdempotency', () => {
  it('claims a fresh key with a single atomic NX SET and a 900s TTL', async () => {
    const store = fakeStore();
    const result = await claimIdempotency(store, 'idem:k1');

    expect(result).toEqual({ claimed: true });
    expect(store.setCalls).toHaveLength(1);
    expect(store.setCalls[0][2]).toBe(900);
  });

  it('refuses a second claim on the same key', async () => {
    const store = fakeStore();
    await claimIdempotency(store, 'idem:k1');
    const second = await claimIdempotency(store, 'idem:k1');

    expect(second).toEqual({ claimed: false, outcome: null });
  });

  it('replays a recorded outcome on a repeat claim', async () => {
    const store = fakeStore();
    await claimIdempotency(store, 'idem:k1');
    await recordOutcome(store, 'idem:k1', '{"loanId":"L-1"}');

    const second = await claimIdempotency(store, 'idem:k1');
    expect(second).toEqual({ claimed: false, outcome: '{"loanId":"L-1"}' });
  });

  it('lets a released key be claimed again — the un-retryable-form fix', async () => {
    // A correctable 422 releases the claim; the user corrects the amount and resubmits with the
    // same client key. Without this, the form would be permanently poisoned.
    const store = fakeStore();
    await claimIdempotency(store, 'idem:k1');
    await releaseIdempotency(store, 'idem:k1');

    expect(await claimIdempotency(store, 'idem:k1')).toEqual({ claimed: true });
  });

  it('uses exactly one round trip to claim — never GET-then-SET', async () => {
    const store = fakeStore();
    const getSpy = vi.spyOn(store, 'get');
    await claimIdempotency(store, 'idem:k1');
    // A fresh claim must not read first; the read only happens on a REFUSED claim, to replay.
    expect(getSpy).not.toHaveBeenCalled();
  });
});
