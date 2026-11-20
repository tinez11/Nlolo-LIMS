import { renderHook } from '@testing-library/react';
import type { User } from 'oidc-client-ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_CAP_MS, msUntilSessionCap, useSessionCap } from './sessionCap';

const SIGNED_IN_AT = Date.UTC(2026, 10, 19, 8, 0, 0);
const HOUR = 60 * 60 * 1000;

const userSignedInAt = (ms: number | undefined) =>
  ({ profile: { sub: 'u-1', auth_time: ms === undefined ? undefined : Math.floor(ms / 1000) } }) as unknown as User;

describe('msUntilSessionCap', () => {
  it('counts ten hours from the sign-in the token names, not from the token itself', () => {
    expect(SESSION_CAP_MS).toBe(10 * HOUR);
    expect(msUntilSessionCap(userSignedInAt(SIGNED_IN_AT), SIGNED_IN_AT + 9 * HOUR)).toBe(HOUR);
    expect(msUntilSessionCap(userSignedInAt(SIGNED_IN_AT), SIGNED_IN_AT + 10 * HOUR)).toBe(0);
    expect(msUntilSessionCap(userSignedInAt(SIGNED_IN_AT), SIGNED_IN_AT + 11 * HOUR)).toBeLessThan(0);
  });

  it('has nothing to count without a signed-in user or an auth_time', () => {
    expect(msUntilSessionCap(null, SIGNED_IN_AT)).toBeNull();
    expect(msUntilSessionCap(userSignedInAt(undefined), SIGNED_IN_AT)).toBeNull();
  });
});

describe('useSessionCap', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('signs out a session already older than ten hours at once', () => {
    vi.setSystemTime(SIGNED_IN_AT + 10 * HOUR + 1000);
    const signOut = vi.fn();
    renderHook(() => useSessionCap(userSignedInAt(SIGNED_IN_AT), signOut));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('leaves a younger session alone, then signs it out once ten hours have passed', () => {
    vi.setSystemTime(SIGNED_IN_AT + 9 * HOUR);
    const signOut = vi.fn();
    renderHook(() => useSessionCap(userSignedInAt(SIGNED_IN_AT), signOut));
    expect(signOut).not.toHaveBeenCalled();

    vi.advanceTimersByTime(59 * 60 * 1000);
    expect(signOut).not.toHaveBeenCalled();

    vi.advanceTimersByTime(2 * 60 * 1000);
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('keeps the cap from the first sign-in when silent renew hands over a new token', () => {
    vi.setSystemTime(SIGNED_IN_AT + 9 * HOUR);
    const signOut = vi.fn();
    const { rerender } = renderHook(({ user }) => useSessionCap(user, signOut), {
      initialProps: { user: userSignedInAt(SIGNED_IN_AT) },
    });
    // A renewed token carries the same auth_time: the clock does not restart.
    rerender({ user: userSignedInAt(SIGNED_IN_AT) });
    vi.advanceTimersByTime(HOUR + 60 * 1000);
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('does nothing while no one is signed in', () => {
    vi.setSystemTime(SIGNED_IN_AT + 20 * HOUR);
    const signOut = vi.fn();
    renderHook(() => useSessionCap(null, signOut));
    vi.advanceTimersByTime(HOUR);
    expect(signOut).not.toHaveBeenCalled();
  });
});
