import { describe, expect, it, vi } from 'vitest';
import type { BordereauView } from '@/api/types';
import type { ApiError } from '@/lib/apiError';
import { failure, idle } from './createResourceSlice';
import { selectBordereauPreview, selectBordereaux, useReinsuranceStore } from './reinsuranceStore';

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

const month = (period: string, treatyId: string): BordereauView => ({
  bordereauId: `b-${period}`,
  treatyId,
  period,
  currency: 'TZS',
  policyCount: 1,
  premium: '50000.00',
  commission: '10000.00',
  recoveries: '0.00',
  lines: [],
});

vi.mock('@/api/reinsurance', () => ({
  listBordereaux: vi.fn((treatyId: string) => Promise.resolve([month('2026-09', treatyId)])),
  previewBordereau: vi.fn((treatyId: string) =>
    Promise.resolve({ ...month('2026-10', treatyId), bordereauId: null }),
  ),
}));

/**
 * `creating` (single slot) outlives its owning form's mount/unmount, same failure mode already found live on
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

/** IFRS 17 I3c: a treaty's written months and its current-month preview are kept per treaty, never shared. */
describe('bordereaux', () => {
  it('keeps each treaty its own months and preview', async () => {
    await useReinsuranceStore.getState().loadBordereaux('treaty-a');
    await useReinsuranceStore.getState().loadBordereaux('treaty-b');
    await useReinsuranceStore.getState().loadBordereauPreview('treaty-a');

    const state = useReinsuranceStore.getState();
    expect(selectBordereaux('treaty-a')(state).data?.[0]?.treatyId).toBe('treaty-a');
    expect(selectBordereaux('treaty-b')(state).data?.[0]?.treatyId).toBe('treaty-b');
    expect(selectBordereauPreview('treaty-a')(state).data?.bordereauId).toBeNull();
    expect(selectBordereauPreview('treaty-b')(state)).toEqual(idle());
  });
});
