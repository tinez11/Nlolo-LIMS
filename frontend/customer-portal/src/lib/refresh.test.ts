import { describe, expect, it, vi } from 'vitest';
import { refreshAccessToken } from './refresh';

const CONFIG = {
  issuer: 'http://localhost:8081/realms/customers',
  clientId: 'lifeplatform-app',
  clientSecret: 'dev-secret-customers',
};

describe('refreshAccessToken', () => {
  it('exchanges the refresh token and returns new expiry', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 300 }),
    });
    const now = 1_000_000;

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'old-refresh', expiresAt: now - 1 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => now },
    );

    expect(result.accessToken).toBe('new-access');
    expect(result.refreshToken).toBe('new-refresh');
    expect(result.expiresAt).toBe(now + 300_000);
    expect(result.error).toBeUndefined();
  });

  it('posts to the realm token endpoint with grant_type=refresh_token and the client secret', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 'a', refresh_token: 'r', expires_in: 300 }),
    });

    await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'the-refresh-token', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('http://localhost:8081/realms/customers/protocol/openid-connect/token');
    const body = new URLSearchParams(init.body as string);
    expect(body.get('grant_type')).toBe('refresh_token');
    expect(body.get('refresh_token')).toBe('the-refresh-token');
    expect(body.get('client_id')).toBe('lifeplatform-app');
    expect(body.get('client_secret')).toBe('dev-secret-customers');
  });

  it('keeps the previous refresh token when Keycloak does not rotate it', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ access_token: 'new-access', expires_in: 300 }),
    });

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'keep-me', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    expect(result.refreshToken).toBe('keep-me');
  });

  it('marks the token RefreshFailed when Keycloak rejects the refresh', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ error: 'invalid_grant' }),
    });

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'expired', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    expect(result.error).toBe('RefreshFailed');
  });

  it('marks the token RefreshFailed when the network throws', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));

    const result = await refreshAccessToken(
      { accessToken: 'old', refreshToken: 'r', expiresAt: 0 },
      { ...CONFIG, fetchImpl: fetchImpl as unknown as typeof fetch, now: () => 0 },
    );

    expect(result.error).toBe('RefreshFailed');
  });
});
