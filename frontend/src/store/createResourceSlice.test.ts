import { describe, expect, it, vi } from 'vitest';
import type { ApiError } from '@/lib/apiError';
import {
  failure,
  idle,
  isEmpty,
  isInitialLoad,
  loading,
  success,
  track,
  type Resource,
} from './createResourceSlice';

const anError: ApiError = {
  status: 500,
  kind: 'server',
  errorCode: 'BOOM',
  title: 'Server error',
  detail: null,
  traceId: 'trace-1',
  fieldErrors: [],
  mayBeDenied: false,
};

describe('resource transitions', () => {
  it('starts idle with nothing loaded', () => {
    expect(idle<string[]>()).toEqual({
      data: null,
      status: 'idle',
      error: null,
      loadedAt: null,
    });
  });

  /**
   * REGRESSION. Zustand subscribes via useSyncExternalStore and compares the value
   * a selector returns to the previous one. Selectors here fall back to `idle()`
   * for an absent key, so if `idle()` allocated a new object per call the selector
   * would return a fresh reference every render and React would loop forever
   * ("The result of getSnapshot should be cached to avoid an infinite loop").
   *
   * This actually happened -- PolicyDrawer hung -- and every unit test passed
   * while it did, because the loop only manifests with a live store driving a real
   * component. Referential stability is the precondition, so that is what is
   * asserted here rather than the shape.
   */
  it('returns a STABLE reference so a fallback selector cannot loop React', () => {
    expect(idle<string[]>()).toBe(idle<string[]>());
    expect(idle<string[]>()).toBe(idle<number>() as unknown as ReturnType<typeof idle<string[]>>);
  });

  it('freezes the shared empty resource so no caller can corrupt it', () => {
    expect(Object.isFrozen(idle())).toBe(true);
  });

  it('does not let a transition mutate the shared empty resource', () => {
    const start = idle<string[]>();
    const next = success(['a']);
    expect(next).not.toBe(start);
    expect(idle<string[]>().data).toBeNull();
    expect(idle<string[]>().status).toBe('idle');
  });

  // A refetch must not blank the table the user is currently reading.
  it('keeps previous data while reloading', () => {
    const loaded = success(['a', 'b']);
    const reloading = loading(loaded);
    expect(reloading.status).toBe('loading');
    expect(reloading.data).toEqual(['a', 'b']);
  });

  it('clears a stale error when a reload starts', () => {
    const failed = failure(success(['a']), anError);
    expect(loading(failed).error).toBeNull();
  });

  // A failed refresh should degrade to "showing older data" rather than an error page.
  it('keeps previous data when a refresh fails', () => {
    const failed = failure(success(['a', 'b']), anError);
    expect(failed.status).toBe('error');
    expect(failed.data).toEqual(['a', 'b']);
    expect(failed.error?.traceId).toBe('trace-1');
  });

  it('stamps loadedAt on success', () => {
    expect(success(['a'], 1234).loadedAt).toBe(1234);
  });
});

describe('isInitialLoad', () => {
  it('is true only when there is nothing to show yet', () => {
    expect(isInitialLoad(idle())).toBe(true);
    expect(isInitialLoad(loading(idle()))).toBe(true);
    expect(isInitialLoad(success(['a']))).toBe(false);
    // Reloading with data present is a refresh, not an initial load.
    expect(isInitialLoad(loading(success(['a'])))).toBe(false);
  });
});

describe('isEmpty', () => {
  it('distinguishes a successful empty result from a pending one', () => {
    expect(isEmpty(success<string[]>([]))).toBe(true);
    expect(isEmpty(success<string[]>(['a']))).toBe(false);
    // Not yet loaded is a spinner, never an empty state.
    expect(isEmpty(idle<string[]>())).toBe(false);
    expect(isEmpty(loading(idle<string[]>()))).toBe(false);
  });

  it('handles a paged envelope as well as a bare array', () => {
    expect(isEmpty(success({ items: [] as string[] }))).toBe(true);
    expect(isEmpty(success({ items: ['a'] }))).toBe(false);
  });
});

describe('track', () => {
  // Each test uses its own key so the module-level request-id map does not leak
  // sequencing state between unrelated tests.
  let key = 0;
  const nextKey = () => `test-${key++}`;

  it('drives loading then success', async () => {
    const seen: Resource<string[]>[] = [];
    await track(nextKey(), idle<string[]>(), (next) => seen.push(next), () =>
      Promise.resolve(['a']),
    );
    expect(seen.map((s) => s.status)).toEqual(['loading', 'success']);
    expect(seen[1]?.data).toEqual(['a']);
  });

  it('drives loading then error, preserving prior data', async () => {
    const seen: Resource<string[]>[] = [];
    const start = success(['old']);
    await track(nextKey(), start, (next) => seen.push(next), () => Promise.reject(anError));
    expect(seen.map((s) => s.status)).toEqual(['loading', 'error']);
    expect(seen[1]?.data).toEqual(['old']);
    expect(seen[1]?.error).toBe(anError);
  });

  it('does not throw out of the caller', async () => {
    const set = vi.fn();
    await expect(
      track(nextKey(), idle<string[]>(), set, () => Promise.reject(anError)),
    ).resolves.toBeUndefined();
    expect(set).toHaveBeenCalledTimes(2);
  });

  /**
   * REGRESSION. Nothing previously stopped an OLDER request that happens to
   * resolve LAST from overwriting a NEWER one already showing correct data --
   * exactly what rapid status-filter clicks in PoliciesPage would trigger, since
   * network timing has no relationship to click order. Deferred promises let this
   * test resolve them out of order deterministically instead of hoping a race
   * reproduces.
   */
  it('discards a stale success once a newer request for the same key has started', async () => {
    const seen: Resource<string>[] = [];
    const set = (next: Resource<string>) => seen.push(next);
    const k = nextKey();

    let resolveOld!: (v: string) => void;
    let resolveNew!: (v: string) => void;
    const old = new Promise<string>((r) => (resolveOld = r));
    const fresh = new Promise<string>((r) => (resolveNew = r));

    const oldCall = track(k, idle<string>(), set, () => old);
    const newCall = track(k, idle<string>(), set, () => fresh);

    // The newer request resolves FIRST -- the common case, since it usually
    // supersedes the older one precisely because it was faster or retried less.
    resolveNew('fresh-result');
    await newCall;
    // The older request resolves SECOND. Without sequencing this would overwrite
    // the fresh result the user is already looking at.
    resolveOld('stale-result');
    await oldCall;

    const successes = seen.filter((s) => s.status === 'success');
    expect(successes).toHaveLength(1);
    expect(successes[0]?.data).toBe('fresh-result');
  });

  it('discards a stale FAILURE the same way -- an old timeout must not blank a fresh success', async () => {
    const seen: Resource<string>[] = [];
    const set = (next: Resource<string>) => seen.push(next);
    const k = nextKey();

    let rejectOld!: (e: unknown) => void;
    let resolveNew!: (v: string) => void;
    const old = new Promise<string>((_, reject) => (rejectOld = reject));
    const fresh = new Promise<string>((r) => (resolveNew = r));

    const oldCall = track(k, idle<string>(), set, () => old);
    const newCall = track(k, idle<string>(), set, () => fresh);

    resolveNew('fresh-result');
    await newCall;
    rejectOld(anError);
    await oldCall;

    expect(seen.at(-1)?.status).toBe('success');
    expect(seen.at(-1)?.data).toBe('fresh-result');
  });

  it('does not let unrelated keys interfere with each other', async () => {
    const seenA: Resource<string>[] = [];
    const seenB: Resource<string>[] = [];
    await Promise.all([
      track(nextKey(), idle<string>(), (n) => seenA.push(n), () => Promise.resolve('a')),
      track(nextKey(), idle<string>(), (n) => seenB.push(n), () => Promise.resolve('b')),
    ]);
    expect(seenA.at(-1)?.data).toBe('a');
    expect(seenB.at(-1)?.data).toBe('b');
  });
});
