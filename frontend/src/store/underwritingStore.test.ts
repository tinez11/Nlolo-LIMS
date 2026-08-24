import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import type { UnderwritingCaseView } from '@/api/types';
import { failure, idle } from './createResourceSlice';
import { useUnderwritingStore } from './underwritingStore';

const anError: ApiError = {
  status: 409,
  kind: 'conflict',
  errorCode: 'UNDERWRITING_CASE_ALREADY_DECIDED',
  title: 'Conflict',
  detail: 'Case already decided',
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

/**
 * `opening` (single slot) and `submittingAssessment` (keyed) both outlive their
 * owning form's mount/unmount, same failure mode already found live on
 * beneficiaries/claims/policy-issuance/products -- built in from the start here.
 */
describe('resetOpenCase', () => {
  it('clears a failed open back to idle', () => {
    useUnderwritingStore.setState({ opening: failure(idle<UnderwritingCaseView>(), anError) });
    useUnderwritingStore.getState().resetOpenCase();
    expect(useUnderwritingStore.getState().opening).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useUnderwritingStore.setState({ opening: idle() });
    expect(() => useUnderwritingStore.getState().resetOpenCase()).not.toThrow();
  });
});

describe('resetSubmitAssessment', () => {
  it('clears a failed submission back to idle', () => {
    useUnderwritingStore.setState({
      submittingAssessment: { 'case-1': failure(idle<UnderwritingCaseView>(), anError) },
    });
    useUnderwritingStore.getState().resetSubmitAssessment('case-1');
    expect(useUnderwritingStore.getState().submittingAssessment['case-1']).toBeUndefined();
  });

  it('does not touch a different case id', () => {
    useUnderwritingStore.setState({
      submittingAssessment: {
        'case-1': failure(idle<UnderwritingCaseView>(), anError),
        'case-2': idle<UnderwritingCaseView>(),
      },
    });
    useUnderwritingStore.getState().resetSubmitAssessment('case-1');
    expect(useUnderwritingStore.getState().submittingAssessment['case-2']).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useUnderwritingStore.setState({ submittingAssessment: {} });
    expect(() => useUnderwritingStore.getState().resetSubmitAssessment('case-1')).not.toThrow();
  });
});
