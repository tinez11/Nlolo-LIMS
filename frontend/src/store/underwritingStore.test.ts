import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import type { MedicalDisclosureView, UnderwritingCaseView } from '@/api/types';
import { failure, idle, success } from './createResourceSlice';
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

/**
 * Disclosures are the one pair of slots where the read matters as much as the write: the sets
 * already on screen are the evidence a reader came for, so a failed recording must not discard
 * them. Hence two keyed slots rather than one.
 */
describe('resetRecordDisclosures', () => {
  it('clears a failed recording back to idle', () => {
    useUnderwritingStore.setState({
      recordingDisclosures: { 'case-1': failure(idle<true>(), anError) },
    });
    useUnderwritingStore.getState().resetRecordDisclosures('case-1');
    expect(useUnderwritingStore.getState().recordingDisclosures['case-1']).toBeUndefined();
  });

  it('does not touch a different case id', () => {
    useUnderwritingStore.setState({
      recordingDisclosures: {
        'case-1': failure(idle<true>(), anError),
        'case-2': idle<true>(),
      },
    });
    useUnderwritingStore.getState().resetRecordDisclosures('case-1');
    expect(useUnderwritingStore.getState().recordingDisclosures['case-2']).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useUnderwritingStore.setState({ recordingDisclosures: {} });
    expect(() => useUnderwritingStore.getState().resetRecordDisclosures('case-1')).not.toThrow();
  });

  it('leaves the disclosures already loaded on the case alone', () => {
    const loaded = success<MedicalDisclosureView[]>([
      {
        medicalDisclosureId: 'd-1',
        caseId: 'case-1',
        answers: [{ questionCode: 'Q1', question: 'Do you smoke?', answer: 'No' }],
        recordedBy: 'agent.senior',
        recordedAt: '2026-09-01T08:00:00Z',
      },
    ]);
    useUnderwritingStore.setState({
      disclosures: { 'case-1': loaded },
      recordingDisclosures: { 'case-1': failure(idle<true>(), anError) },
    });
    useUnderwritingStore.getState().resetRecordDisclosures('case-1');
    expect(useUnderwritingStore.getState().disclosures['case-1']).toEqual(loaded);
  });
});
