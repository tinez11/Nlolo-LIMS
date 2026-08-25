import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import { failure, idle } from './createResourceSlice';
import { useReinsuranceStore } from './reinsuranceStore';

const anError: ApiError = {
  status: 422,
  kind: 'unprocessable',
  errorCode: 'REINSURANCE_VALIDATION_FAILED',
  title: 'Unprocessable Entity',
  detail: 'A reinsurer name is required',
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

/**
 * `creating` (single slot) and `confirmingRecovery` (keyed) both outlive
 * their owning form's mount/unmount, same failure mode already found live on
 * beneficiaries/claims/policy-issuance/products -- built in from the start.
 */
describe('resetCreateTreaty', () => {
  it('clears a failed creation back to idle', () => {
    useReinsuranceStore.setState({ creating: failure(idle<never>(), anError) });
    useReinsuranceStore.getState().resetCreateTreaty();
    expect(useReinsuranceStore.getState().creating).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useReinsuranceStore.setState({ creating: idle() });
    expect(() => useReinsuranceStore.getState().resetCreateTreaty()).not.toThrow();
  });
});

describe('resetConfirmRecovery', () => {
  it('clears a failed confirmation back to idle', () => {
    useReinsuranceStore.setState({
      confirmingRecovery: { 'recovery-1': failure(idle<never>(), anError) },
    });
    useReinsuranceStore.getState().resetConfirmRecovery('recovery-1');
    expect(useReinsuranceStore.getState().confirmingRecovery['recovery-1']).toBeUndefined();
  });

  it('does not touch a different recovery id', () => {
    useReinsuranceStore.setState({
      confirmingRecovery: {
        'recovery-1': failure(idle<never>(), anError),
        'recovery-2': idle<never>(),
      },
    });
    useReinsuranceStore.getState().resetConfirmRecovery('recovery-1');
    expect(useReinsuranceStore.getState().confirmingRecovery['recovery-2']).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useReinsuranceStore.setState({ confirmingRecovery: {} });
    expect(() => useReinsuranceStore.getState().resetConfirmRecovery('recovery-1')).not.toThrow();
  });
});
