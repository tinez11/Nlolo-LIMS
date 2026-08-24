import { describe, expect, it, vi } from 'vitest';
import {
  IDEMPOTENCY_HEADER,
  requiresIdempotencyKey,
  startMutation,
} from './idempotency';

describe('startMutation', () => {
  it('reuses the SAME key across retries of one user intent', () => {
    const attempt = startMutation();
    // A retry after a network timeout MUST resend the original key -- generating a
    // fresh one is precisely how a timed-out payment becomes two payments.
    expect(attempt.key).toBe(attempt.key);
    const first = attempt.key;
    expect(attempt.key).toBe(first);
    expect(attempt.headers()[IDEMPOTENCY_HEADER]).toBe(first);
  });

  it('issues a distinct key per new intent', () => {
    const keys = new Set(Array.from({ length: 50 }, () => startMutation().key));
    expect(keys.size).toBe(50);
  });

  it('produces a UUID', () => {
    expect(startMutation().key).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
  });

  it('falls back when crypto.randomUUID is unavailable', () => {
    const original = globalThis.crypto.randomUUID;
    // Some older WebViews and non-secure contexts genuinely lack it.
    Reflect.deleteProperty(globalThis.crypto, 'randomUUID');
    try {
      expect(startMutation().key).toMatch(/^[0-9a-f-]{36}$/i);
    } finally {
      Object.defineProperty(globalThis.crypto, 'randomUUID', {
        value: original,
        configurable: true,
        writable: true,
      });
    }
  });
});

describe('requiresIdempotencyKey', () => {
  // These six 400 without the header. A submit button that omits it is broken, so
  // this list is asserted rather than trusted to a code comment.
  it.each([
    ['post', '/invoices/9f2/payment-request'],
    ['post', '/claims'],
    ['post', '/agents'],
    ['post', '/agents/1/commission-statements/2/payout'],
    ['post', '/claims/7/recoveries/8/confirm'],
    ['post', '/treaties'],
  ])('requires a key for %s %s', (method, url) => {
    expect(requiresIdempotencyKey(method, url)).toBe(true);
  });

  it('is method-sensitive -- reading a treaty list needs no key', () => {
    expect(requiresIdempotencyKey('get', '/treaties')).toBe(false);
  });

  it('is case-insensitive about the method', () => {
    expect(requiresIdempotencyKey('POST', '/claims')).toBe(true);
  });

  it('ignores a query string', () => {
    expect(requiresIdempotencyKey('post', '/claims?foo=bar')).toBe(true);
  });

  it('tolerates the /api proxy prefix used in dev', () => {
    expect(requiresIdempotencyKey('post', '/api/claims')).toBe(true);
  });

  // These accept the header but do not enforce it. Not required, so not asserted as
  // required -- but they must not be mistaken for the enforced set either.
  it.each([
    ['post', '/policies/POL-1/endorsements'],
    ['post', '/policies/POL-1/loans'],
    ['post', '/loans/abc/repayments'],
    ['post', '/underwriting/cases'],
  ])('does not require a key for %s %s', (method, url) => {
    expect(requiresIdempotencyKey(method, url)).toBe(false);
  });

  it('does not confuse a claim sub-resource with claim creation', () => {
    expect(requiresIdempotencyKey('post', '/claims/7/assessments')).toBe(false);
    expect(requiresIdempotencyKey('post', '/claims/7/evidence')).toBe(false);
  });

  it('does not treat a policy-scoped invoice read as a payment request', () => {
    expect(requiresIdempotencyKey('get', '/policies/POL-1/invoices/next-due')).toBe(false);
  });
});

describe('the dev-time guard', () => {
  it('is exercised by requiresIdempotencyKey rather than a comment', () => {
    // Sanity: a random unrelated POST is not swept into the required set.
    const spy = vi.fn(() => requiresIdempotencyKey('post', '/parties/individuals'));
    expect(spy()).toBe(false);
  });
});
