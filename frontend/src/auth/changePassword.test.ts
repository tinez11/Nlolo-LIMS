import { describe, expect, it } from 'vitest';
import { changePasswordArgs } from './changePassword';

describe('changePasswordArgs', () => {
  it("asks Keycloak for its own change-password action and comes back to this page", () => {
    window.history.replaceState({}, '', '/customers/policies?tab=open');
    expect(changePasswordArgs()).toEqual({
      extraQueryParams: { kc_action: 'UPDATE_PASSWORD' },
      state: { returnTo: '/customers/policies?tab=open' },
    });
  });
});
