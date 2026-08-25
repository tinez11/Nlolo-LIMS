import { AxiosError } from 'axios';
import { describe, expect, it } from 'vitest';
import { inflateBlobErrorBody } from './http';

function blobError(contentType: string, body: unknown): AxiosError {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  const blob = new Blob([text], { type: contentType });
  return new AxiosError(
    'Request failed',
    'ERR_BAD_REQUEST',
    undefined,
    undefined,
    {
      data: blob,
      status: 404,
      statusText: 'Not Found',
      headers: { 'content-type': contentType },
      config: {} as never,
    } as never,
  );
}

describe('inflateBlobErrorBody', () => {
  it('decodes a JSON problem+json Blob body back into a plain object', async () => {
    const error = blobError('application/problem+json', {
      title: 'Not Found',
      detail: 'No document found for ref x',
      errorCode: 'DOCUMENT_NOT_FOUND',
      traceId: 'abc-123',
    });

    await inflateBlobErrorBody(error);

    expect(error.response?.data).toEqual({
      title: 'Not Found',
      detail: 'No document found for ref x',
      errorCode: 'DOCUMENT_NOT_FOUND',
      traceId: 'abc-123',
    });
  });

  it('leaves a non-JSON Blob body (e.g. an image download that somehow errored) untouched', async () => {
    const error = blobError('image/jpeg', 'not json at all');

    await inflateBlobErrorBody(error);

    expect(error.response?.data).toBeInstanceOf(Blob);
  });

  it('leaves a malformed JSON-labelled body untouched rather than throwing', async () => {
    const error = blobError('application/problem+json', '{not valid json');

    await expect(inflateBlobErrorBody(error)).resolves.toBeUndefined();
    expect(error.response?.data).toBeInstanceOf(Blob);
  });

  it('is a no-op for a non-Blob response body', async () => {
    const error = new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
      data: { title: 'Not Found' },
      status: 404,
      statusText: 'Not Found',
      headers: { 'content-type': 'application/problem+json' },
      config: {} as never,
    } as never);

    await inflateBlobErrorBody(error);

    expect(error.response?.data).toEqual({ title: 'Not Found' });
  });

  it('is a no-op for a non-AxiosError cause', async () => {
    await expect(inflateBlobErrorBody(new Error('plain error'))).resolves.toBeUndefined();
  });
});
