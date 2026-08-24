import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import { failure, idle } from './createResourceSlice';
import { useDistributionStore } from './distributionStore';

const anError: ApiError = {
  status: 422,
  kind: 'unprocessable',
  errorCode: 'DISTRIBUTION_VALIDATION_FAILED',
  title: 'Unprocessable Entity',
  detail: 'License number already in use',
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

/**
 * `onboarding` and `creatingPlan` are single slots; `requestingPayout` is
 * keyed. All three outlive their owning form's mount/unmount, same failure
 * mode already found live on beneficiaries/claims/policy-issuance/products --
 * built in from the start here.
 */
describe('resetOnboardAgent', () => {
  it('clears a failed onboarding back to idle', () => {
    useDistributionStore.setState({ onboarding: failure(idle<never>(), anError) });
    useDistributionStore.getState().resetOnboardAgent();
    expect(useDistributionStore.getState().onboarding).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useDistributionStore.setState({ onboarding: idle() });
    expect(() => useDistributionStore.getState().resetOnboardAgent()).not.toThrow();
  });
});

describe('resetCreateCommissionPlan', () => {
  it('clears a failed plan creation back to idle', () => {
    useDistributionStore.setState({ creatingPlan: failure(idle<never>(), anError) });
    useDistributionStore.getState().resetCreateCommissionPlan();
    expect(useDistributionStore.getState().creatingPlan).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useDistributionStore.setState({ creatingPlan: idle() });
    expect(() => useDistributionStore.getState().resetCreateCommissionPlan()).not.toThrow();
  });
});

describe('resetRequestPayout', () => {
  it('clears a failed payout request back to idle', () => {
    useDistributionStore.setState({
      requestingPayout: { 'agent-1:stmt-1': failure(idle<never>(), anError) },
    });
    useDistributionStore.getState().resetRequestPayout('agent-1', 'stmt-1');
    expect(useDistributionStore.getState().requestingPayout['agent-1:stmt-1']).toBeUndefined();
  });

  it('does not touch a different statement', () => {
    useDistributionStore.setState({
      requestingPayout: {
        'agent-1:stmt-1': failure(idle<never>(), anError),
        'agent-1:stmt-2': idle<never>(),
      },
    });
    useDistributionStore.getState().resetRequestPayout('agent-1', 'stmt-1');
    expect(useDistributionStore.getState().requestingPayout['agent-1:stmt-2']).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useDistributionStore.setState({ requestingPayout: {} });
    expect(() =>
      useDistributionStore.getState().resetRequestPayout('agent-1', 'stmt-1'),
    ).not.toThrow();
  });
});
