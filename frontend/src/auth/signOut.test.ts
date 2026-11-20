import { describe, expect, it, vi } from 'vitest';
import { REVOCATION_PATH, signOut } from './signOut';

const authWith = (revoke: () => Promise<void>) => {
  const calls: string[] = [];
  return {
    calls,
    auth: {
      revokeTokens: vi.fn(async () => {
        calls.push('revoke');
        await revoke();
      }),
      signoutRedirect: vi.fn(async () => {
        calls.push('redirect');
      }),
    },
  };
};

describe('signOut', () => {
  it('revokes the tokens, then ends the Authentik session', async () => {
    const { auth, calls } = authWith(async () => undefined);
    await signOut(auth);
    expect(calls).toEqual(['revoke', 'redirect']);
  });

  it('still ends the session when revoking fails', async () => {
    const { auth, calls } = authWith(async () => {
      throw new Error('network down');
    });
    await signOut(auth);
    expect(calls).toEqual(['revoke', 'redirect']);
  });

  it('revokes through the console origin, which forwards to Authentik', () => {
    // Authentik 2026.8.5 sends no CORS headers from its revoke endpoint, so the browser cannot call it across origins.
    expect(REVOCATION_PATH).toBe('/oauth/revoke/');
  });
});
