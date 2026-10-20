import type { User } from 'oidc-client-ts';
import { afterEach, describe, expect, it } from 'vitest';
import { readReturnRouterState, readReturnTo, returnToState } from './returnTo';

const userWith = (state: unknown) => ({ state }) as unknown as User;

afterEach(() => window.history.replaceState(null, '', '/'));

describe('returnTo', () => {
  it('carries the page router state through the sign-in round trip, beside the address', () => {
    const trail = { cameFrom: [{ label: 'Amina Owner', to: '/staff/parties/p-1' }] };
    window.history.replaceState({ usr: trail, key: 'k', idx: 0 }, '', '/staff/policies/POL-1?tab=cover');

    const state = returnToState();
    expect(state).toEqual({ returnTo: '/staff/policies/POL-1?tab=cover', routerState: trail });
    expect(readReturnTo(userWith(state))).toBe('/staff/policies/POL-1?tab=cover');
    expect(readReturnRouterState(userWith(state))).toEqual(trail);
  });

  it('carries no router state when the page has none', () => {
    window.history.replaceState(null, '', '/staff/policies');
    expect(returnToState()).toEqual({ returnTo: '/staff/policies' });
    expect(readReturnRouterState(userWith(returnToState()))).toBeNull();
    expect(readReturnRouterState(null)).toBeNull();
  });

  it('still refuses a return address off the console', () => {
    expect(readReturnTo(userWith({ returnTo: '//evil.example' }))).toBeNull();
  });
});
