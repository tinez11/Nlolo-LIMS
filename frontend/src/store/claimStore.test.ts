import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import { failure, idle } from './createResourceSlice';
import { useClaimStore } from './claimStore';

const anError: ApiError = {
  status: 422,
  kind: 'unprocessable',
  errorCode: 'CLAIM_VALIDATION_FAILED',
  title: 'Unprocessable Entity',
  detail: 'Policy POL-1 was not in force on 2026-08-01',
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

/**
 * Built in from the start this time: a policyStore e2e test caught a real bug
 * where a stale save-error resurfaced on reopening a form because nothing reset
 * the resource holding it. `registering` is a single (non-keyed) slot -- unlike
 * that bug's per-key resource -- but the same failure mode applies: without an
 * explicit reset, a rejected registration would resurface on a fresh attempt.
 */
describe('resetRegisterClaim', () => {
  it('clears a failed registration back to idle', () => {
    useClaimStore.setState({ registering: failure(idle<never>(), anError) });

    useClaimStore.getState().resetRegisterClaim();

    expect(useClaimStore.getState().registering).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useClaimStore.setState({ registering: idle() });

    expect(() => useClaimStore.getState().resetRegisterClaim()).not.toThrow();
    expect(useClaimStore.getState().registering.status).toBe('idle');
  });

  it('never leaves the previous error reachable afterward', () => {
    useClaimStore.setState({ registering: failure(idle<never>(), anError) });

    useClaimStore.getState().resetRegisterClaim();

    expect(useClaimStore.getState().registering.error).toBeNull();
  });
});
