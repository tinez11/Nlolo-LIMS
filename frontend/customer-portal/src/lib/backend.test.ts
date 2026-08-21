import { describe, expect, it, vi } from 'vitest';
import { ApiError, callBackend } from './backend';

const OK = (body: unknown) => ({
  ok: true, status: 200,
  headers: new Headers({ 'content-type': 'application/json' }),
  json: async () => body,
});

describe('callBackend', () => {
  it('attaches the bearer token and returns parsed JSON', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(OK({ policyNumber: 'POL-1' }));

    const result = await callBackend<{ policyNumber: string }>('/policies/POL-1', {
      accessToken: 'tok', fetchImpl: fetchImpl as unknown as typeof fetch,
      baseUrl: 'http://backend:8080',
    });

    expect(result.policyNumber).toBe('POL-1');
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe('http://backend:8080/policies/POL-1');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
  });

  it('serialises searchParams and drops undefined values', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(OK({ items: [] }));

    await callBackend('/policies', {
      accessToken: 'tok', fetchImpl: fetchImpl as unknown as typeof fetch,
      baseUrl: 'http://backend:8080',
      searchParams: { page: 0, pageSize: 20, status: undefined },
    });

    expect(fetchImpl.mock.calls[0][0]).toBe('http://backend:8080/policies?page=0&pageSize=20');
  });

  it('throws ApiError carrying the decoded ProblemDetails', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false, status: 409,
      headers: new Headers({ 'content-type': 'application/problem+json' }),
      json: async () => ({
        type: 'about:blank', title: 'Conflict', status: 409,
        errorCode: 'INSUFFICIENT_LOAN_VALUE', traceId: 'abc',
      }),
    });

    await expect(callBackend('/policies/P/loans', {
      method: 'POST', accessToken: 'tok',
      fetchImpl: fetchImpl as unknown as typeof fetch, baseUrl: 'http://backend:8080',
    })).rejects.toMatchObject({
      status: 409,
      problem: { errorCode: 'INSUFFICIENT_LOAN_VALUE' },
    });
  });

  it('throws ApiError with a null problem when the error body is not JSON', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false, status: 502,
      headers: new Headers({ 'content-type': 'text/html' }),
      json: async () => { throw new Error('not json'); },
    });

    const error = await callBackend('/policies', {
      accessToken: 'tok', fetchImpl: fetchImpl as unknown as typeof fetch,
      baseUrl: 'http://backend:8080',
    }).catch((e: unknown) => e as ApiError);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(502);
    expect((error as ApiError).problem).toBeNull();
  });

  it('returns undefined for a 204 rather than trying to parse a body', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 204, headers: new Headers(),
      json: async () => { throw new Error('no body'); },
    });

    await expect(callBackend('/policies/P/beneficiaries', {
      method: 'PUT', accessToken: 'tok',
      fetchImpl: fetchImpl as unknown as typeof fetch, baseUrl: 'http://backend:8080',
    })).resolves.toBeUndefined();
  });
});
