import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import type { ProductSummary } from '@/api/types';
import { failure, idle, success } from './createResourceSlice';
import { useProductStore } from './productStore';

const anError: ApiError = {
  status: 409,
  kind: 'conflict',
  errorCode: 'DUPLICATE_PRODUCT_CODE',
  title: 'Conflict',
  detail: 'Product code already exists',
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

/**
 * Both `creating` (single slot) and `publishing` (keyed) share the same failure
 * mode already found live in two prior features: a resource that outlives its
 * form's mount/unmount resurfaces a stale rejection unless something explicitly
 * resets it. Built in from the start here.
 */
describe('resetCreateProduct', () => {
  it('clears a failed creation back to idle', () => {
    useProductStore.setState({ creating: failure(idle<ProductSummary>(), anError) });
    useProductStore.getState().resetCreateProduct();
    expect(useProductStore.getState().creating).toEqual(idle());
  });

  it('does no harm when there is nothing to reset', () => {
    useProductStore.setState({ creating: idle() });
    expect(() => useProductStore.getState().resetCreateProduct()).not.toThrow();
  });
});

describe('resetPublishVersion', () => {
  it('clears a failed publish back to idle', () => {
    useProductStore.setState({ publishing: { 'prod-1': failure(idle<true>(), anError) } });
    useProductStore.getState().resetPublishVersion('prod-1');
    expect(useProductStore.getState().publishing['prod-1']).toBeUndefined();
  });

  it('does not touch a different product id', () => {
    useProductStore.setState({
      publishing: { 'prod-1': failure(idle<true>(), anError), 'prod-2': success(true) },
    });
    useProductStore.getState().resetPublishVersion('prod-1');
    expect(useProductStore.getState().publishing['prod-2']?.status).toBe('success');
  });

  it('does no harm when there is nothing to reset', () => {
    useProductStore.setState({ publishing: {} });
    expect(() => useProductStore.getState().resetPublishVersion('prod-1')).not.toThrow();
  });
});
