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

/**
 * `submittingAssessment`, `decidingSettlement`, and `reopening` are all keyed by
 * claimId and all outlive their owning panel's mount/unmount, same failure mode
 * as `registering` above -- built in from the start for all three.
 */
describe('resetSubmitAssessment', () => {
  it('clears a failed submission back to idle', () => {
    useClaimStore.setState({ submittingAssessment: { 'claim-1': failure(idle<never>(), anError) } });
    useClaimStore.getState().resetSubmitAssessment('claim-1');
    expect(useClaimStore.getState().submittingAssessment['claim-1']).toBeUndefined();
  });

  it('does not touch a different claim id', () => {
    useClaimStore.setState({
      submittingAssessment: {
        'claim-1': failure(idle<never>(), anError),
        'claim-2': idle<never>(),
      },
    });
    useClaimStore.getState().resetSubmitAssessment('claim-1');
    expect(useClaimStore.getState().submittingAssessment['claim-2']).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useClaimStore.setState({ submittingAssessment: {} });
    expect(() => useClaimStore.getState().resetSubmitAssessment('claim-1')).not.toThrow();
  });
});

describe('resetDecideSettlement', () => {
  it('clears a failed decision back to idle', () => {
    useClaimStore.setState({ decidingSettlement: { 'claim-1': failure(idle<never>(), anError) } });
    useClaimStore.getState().resetDecideSettlement('claim-1');
    expect(useClaimStore.getState().decidingSettlement['claim-1']).toBeUndefined();
  });

  it('does no harm when there is nothing to reset', () => {
    useClaimStore.setState({ decidingSettlement: {} });
    expect(() => useClaimStore.getState().resetDecideSettlement('claim-1')).not.toThrow();
  });
});

describe('resetReopenClaim', () => {
  it('clears a failed reopen back to idle', () => {
    useClaimStore.setState({ reopening: { 'claim-1': failure(idle<never>(), anError) } });
    useClaimStore.getState().resetReopenClaim('claim-1');
    expect(useClaimStore.getState().reopening['claim-1']).toBeUndefined();
  });

  it('does no harm when there is nothing to reset', () => {
    useClaimStore.setState({ reopening: {} });
    expect(() => useClaimStore.getState().resetReopenClaim('claim-1')).not.toThrow();
  });
});
