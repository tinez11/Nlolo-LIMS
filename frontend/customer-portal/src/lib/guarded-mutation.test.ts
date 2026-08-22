import { describe, expect, it, vi } from 'vitest';
import { runGuardedMutation } from './guarded-mutation';
import type { IdempotencyStore } from './idempotency';

function fakeStore() {
  const data = new Map<string, string>();
  return {
    data,
    async setIfAbsent(key: string, value: string) {
      if (data.has(key)) return false;
      data.set(key, value);
      return true;
    },
    async get(key: string) { return data.get(key) ?? null; },
    async set(key: string, value: string) { data.set(key, value); },
    async del(key: string) { data.delete(key); },
  } satisfies IdempotencyStore & { data: Map<string, string> };
}

const KEY = 'idem:customers:sub-1:loan-origination:client-key-1';

describe('runGuardedMutation', () => {
  it('performs the call and records the outcome on success', async () => {
    const store = fakeStore();
    const perform = vi.fn().mockResolvedValue({ kind: 'success', body: { loanId: 'L-1' } });

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result).toEqual({ status: 200, body: { loanId: 'L-1' } });
    expect(store.data.get(KEY)).toContain('L-1');
  });

  it('replays a recorded outcome instead of calling again', async () => {
    const store = fakeStore();
    await runGuardedMutation({ store, key: KEY, perform: vi.fn().mockResolvedValue({ kind: 'success', body: { loanId: 'L-1' } }) });

    const perform = vi.fn();
    const replay = await runGuardedMutation({ store, key: KEY, perform });

    expect(perform).not.toHaveBeenCalled();
    expect(replay.body).toEqual({ loanId: 'L-1' });
  });

  it('returns 409 when a claim is in flight with no outcome yet', async () => {
    const store = fakeStore();
    const never = vi.fn().mockImplementation(() => new Promise(() => {}));
    void runGuardedMutation({ store, key: KEY, perform: never });   // holds the claim

    const second = await runGuardedMutation({ store, key: KEY, perform: vi.fn() });

    expect(second.status).toBe(409);
  });

  it('RELEASES the claim on a correctable 4xx so the user can retry', async () => {
    // The un-retryable-form bug. Without this branch, correcting a rejected amount and
    // resubmitting would replay the stale rejection forever.
    const store = fakeStore();
    const perform = vi.fn().mockResolvedValue({
      kind: 'rejected', status: 422,
      problem: { status: 422, errorCode: 'VALIDATION_ERROR', traceId: 't' },
    });

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result.status).toBe(422);
    expect(store.data.has(KEY)).toBe(false);

    const retry = vi.fn().mockResolvedValue({ kind: 'success', body: { loanId: 'L-2' } });
    const second = await runGuardedMutation({ store, key: KEY, perform: retry });
    expect(retry).toHaveBeenCalledTimes(1);
    expect(second.status).toBe(200);
  });

  it('KEEPS the claim on an indeterminate failure', async () => {
    // A timeout or 5xx may mean the backend applied the change. Blocking the retry is the
    // conservative, correct answer here.
    const store = fakeStore();
    const perform = vi.fn().mockResolvedValue({ kind: 'indeterminate', status: 504 });

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result.status).toBe(504);
    expect(store.data.has(KEY)).toBe(true);
  });

  it('keeps the claim when perform throws unexpectedly', async () => {
    const store = fakeStore();
    const perform = vi.fn().mockRejectedValue(new Error('socket hang up'));

    const result = await runGuardedMutation({ store, key: KEY, perform });

    expect(result.status).toBe(504);
    expect(store.data.has(KEY)).toBe(true);
  });
});
