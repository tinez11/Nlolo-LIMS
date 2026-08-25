import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import type { PolicyView } from '@/api/types';
import { failure, idle, success } from './createResourceSlice';
import { selectSavingBeneficiaries, usePolicyStore } from './policyStore';

const anError: ApiError = {
  status: 500,
  kind: 'server',
  errorCode: 'BOOM',
  title: 'Server error',
  detail: 'Simulated',
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

/**
 * REGRESSION for a bug an e2e test caught, not a unit test: `savingBeneficiaries`
 * is keyed by policy number and deliberately outlives the edit form's own
 * mount/unmount (a failure must still be visible if the form is torn down and
 * rebuilt mid-flight). Without an explicit reset, reopening the form after a
 * rejected save immediately resurfaced the PREVIOUS rejection, before the new
 * attempt had done anything wrong. `resetSaveBeneficiaries` is what
 * `BeneficiariesPanel` calls the instant editing begins, to guarantee that.
 */
describe('resetSaveBeneficiaries', () => {
  it('clears a failed save back to idle', () => {
    usePolicyStore.setState({
      savingBeneficiaries: { 'POL-1': failure(idle<true>(), anError) },
    });

    usePolicyStore.getState().resetSaveBeneficiaries('POL-1');

    expect(selectSavingBeneficiaries('POL-1')(usePolicyStore.getState())).toEqual(idle());
  });

  it('clears a still-loading save the same way', () => {
    usePolicyStore.setState({
      savingBeneficiaries: { 'POL-1': { data: null, status: 'loading', error: null, loadedAt: null } },
    });

    usePolicyStore.getState().resetSaveBeneficiaries('POL-1');

    expect(selectSavingBeneficiaries('POL-1')(usePolicyStore.getState()).status).toBe('idle');
  });

  it('does no harm when there is nothing to reset', () => {
    usePolicyStore.setState({ savingBeneficiaries: {} });

    expect(() => usePolicyStore.getState().resetSaveBeneficiaries('POL-1')).not.toThrow();
    expect(selectSavingBeneficiaries('POL-1')(usePolicyStore.getState()).status).toBe('idle');
  });

  it('does not touch a different policy number', () => {
    usePolicyStore.setState({
      savingBeneficiaries: {
        'POL-1': failure(idle<true>(), anError),
        'POL-2': success(true),
      },
    });

    usePolicyStore.getState().resetSaveBeneficiaries('POL-1');

    expect(selectSavingBeneficiaries('POL-2')(usePolicyStore.getState()).status).toBe('success');
  });

  it('never leaves the previous error reachable through the same key afterward', () => {
    usePolicyStore.setState({
      savingBeneficiaries: { 'POL-1': failure(idle<true>(), anError) },
    });

    usePolicyStore.getState().resetSaveBeneficiaries('POL-1');

    expect(selectSavingBeneficiaries('POL-1')(usePolicyStore.getState()).error).toBeNull();
  });
});

/**
 * `issuing` is a single, non-keyed slot (issuance creates a NEW policy, so there
 * is no existing id to key by) -- the same shape as claims' `registering`, which
 * needed the identical reset for the identical reason. Built in from the start.
 */
describe('resetIssuePolicy', () => {
  it('clears a failed issuance back to idle', () => {
    usePolicyStore.setState({ issuing: failure(idle<PolicyView>(), anError) });

    usePolicyStore.getState().resetIssuePolicy();

    expect(usePolicyStore.getState().issuing).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    usePolicyStore.setState({ issuing: idle() });

    expect(() => usePolicyStore.getState().resetIssuePolicy()).not.toThrow();
    expect(usePolicyStore.getState().issuing.status).toBe('idle');
  });

  it('never leaves the previous error reachable afterward', () => {
    usePolicyStore.setState({ issuing: failure(idle<PolicyView>(), anError) });

    usePolicyStore.getState().resetIssuePolicy();

    expect(usePolicyStore.getState().issuing.error).toBeNull();
  });
});

/**
 * `waivingInvoice` and `requestingPayment` are both keyed by invoiceId and
 * outlive their owning row's mount/unmount, same failure mode as every other
 * mutation resource on this console -- built in from the start.
 */
describe('resetWaiveInvoice', () => {
  it('clears a failed waiver back to idle', () => {
    usePolicyStore.setState({ waivingInvoice: { 'inv-1': failure(idle<true>(), anError) } });
    usePolicyStore.getState().resetWaiveInvoice('inv-1');
    expect(usePolicyStore.getState().waivingInvoice['inv-1']).toBeUndefined();
  });

  it('does not touch a different invoice id', () => {
    usePolicyStore.setState({
      waivingInvoice: { 'inv-1': failure(idle<true>(), anError), 'inv-2': success(true) },
    });
    usePolicyStore.getState().resetWaiveInvoice('inv-1');
    expect(usePolicyStore.getState().waivingInvoice['inv-2']?.status).toBe('success');
  });

  it('does no harm when there is nothing to reset', () => {
    usePolicyStore.setState({ waivingInvoice: {} });
    expect(() => usePolicyStore.getState().resetWaiveInvoice('inv-1')).not.toThrow();
  });
});

describe('resetRequestPaymentForInvoice', () => {
  it('clears a failed payment request back to idle', () => {
    usePolicyStore.setState({ requestingPayment: { 'inv-1': failure(idle<true>(), anError) } });
    usePolicyStore.getState().resetRequestPaymentForInvoice('inv-1');
    expect(usePolicyStore.getState().requestingPayment['inv-1']).toBeUndefined();
  });

  it('does no harm when there is nothing to reset', () => {
    usePolicyStore.setState({ requestingPayment: {} });
    expect(() => usePolicyStore.getState().resetRequestPaymentForInvoice('inv-1')).not.toThrow();
  });
});
