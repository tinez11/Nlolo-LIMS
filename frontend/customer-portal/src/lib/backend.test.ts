import { describe, expect, it, vi } from 'vitest';
import { ApiError, callBackend, callBackendRaw, resolveOwnPartyId } from './backend';

/** header.payload.signature with payload `{ party_id: 'p-1' }`, unsigned -- fine for a claims-only decode. */
function fakeJwt(claims: Record<string, unknown>): string {
  const base64url = (s: string) => Buffer.from(s).toString('base64url');
  return `${base64url(JSON.stringify({ alg: 'none' }))}.${base64url(JSON.stringify(claims))}.sig`;
}

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

describe('callBackendRaw with a FormData body', () => {
  it('forwards the FormData unchanged and does not set a Content-Type header', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 201, headers: new Headers() });
    const form = new FormData();
    form.set('file', new File(['x'], 'scan.pdf', { type: 'application/pdf' }));

    await callBackendRaw('/claims/c1/evidence', {
      method: 'POST', body: form, accessToken: 'tok',
      fetchImpl: fetchImpl as unknown as typeof fetch, baseUrl: 'http://backend:8080',
    });

    const [, init] = fetchImpl.mock.calls[0];
    // fetch, not this wrapper, must own the multipart boundary -- a hand-set
    // 'Content-Type: multipart/form-data' with no boundary parameter breaks the upload.
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
    expect(init.body).toBe(form);
  });

  it('still JSON-serialises and sets Content-Type for a plain object body', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200, headers: new Headers() });

    await callBackendRaw('/claims', {
      method: 'POST', body: { policyNumber: 'POL-1' }, accessToken: 'tok',
      fetchImpl: fetchImpl as unknown as typeof fetch, baseUrl: 'http://backend:8080',
    });

    const [, init] = fetchImpl.mock.calls[0];
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"policyNumber":"POL-1"}');
  });
});

describe('resolveOwnPartyId', () => {
  it('decodes the party_id claim off the caller\'s own access token', async () => {
    const token = fakeJwt({ party_id: '33333333-3333-3333-3333-333333333333', sub: 'u1' });
    await expect(resolveOwnPartyId({ accessToken: token })).resolves.toBe(
      '33333333-3333-3333-3333-333333333333',
    );
  });

  it('throws ApiError(401) when the token carries no party_id claim', async () => {
    const token = fakeJwt({ sub: 'u1' });
    await expect(resolveOwnPartyId({ accessToken: token })).rejects.toBeInstanceOf(ApiError);
  });
});
