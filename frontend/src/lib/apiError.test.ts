import { AxiosError, AxiosHeaders } from 'axios';
import { describe, expect, it } from 'vitest';
import { fieldErrorMap, toApiError } from './apiError';

/** Build an AxiosError shaped the way axios really delivers one. */
function axiosError(status: number, data: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() };
  const err = new AxiosError('boom', 'ERR_BAD_RESPONSE', config as never);
  err.response = {
    status,
    statusText: '',
    data,
    headers: {},
    config: config as never,
  };
  return err;
}

const problem = {
  type: 'https://docs.nlolo/errors/validation',
  title: 'Validation failure',
  status: 400,
  detail: 'sumAssured must be positive',
  errorCode: 'VALIDATION_ERROR',
  traceId: 'trace-abc-123',
  errors: [{ field: 'sumAssured', message: 'must be positive' }],
};

describe('toApiError', () => {
  it('lifts every ProblemDetails field the backend guarantees', () => {
    const e = toApiError(axiosError(400, problem));
    expect(e.status).toBe(400);
    expect(e.title).toBe('Validation failure');
    expect(e.detail).toBe('sumAssured must be positive');
    expect(e.errorCode).toBe('VALIDATION_ERROR');
    // traceId is the only thread back to the backend logs, so it must survive.
    expect(e.traceId).toBe('trace-abc-123');
    expect(e.fieldErrors).toEqual([{ field: 'sumAssured', message: 'must be positive' }]);
  });

  it('classifies each status the console has to branch on', () => {
    expect(toApiError(axiosError(400, problem)).kind).toBe('validation');
    expect(toApiError(axiosError(401, {})).kind).toBe('unauthenticated');
    expect(toApiError(axiosError(403, {})).kind).toBe('forbidden');
    expect(toApiError(axiosError(404, {})).kind).toBe('notFound');
    expect(toApiError(axiosError(409, {})).kind).toBe('conflict');
    expect(toApiError(axiosError(422, {})).kind).toBe('unprocessable');
    // POST /policies/{n}/surrender and the process-status endpoint are deferred
    // stubs that really do return 501. The UI must disable them, not retry.
    expect(toApiError(axiosError(501, {})).kind).toBe('notImplemented');
    expect(toApiError(axiosError(500, {})).kind).toBe('server');
    expect(toApiError(axiosError(418, {})).kind).toBe('unknown');
  });

  it('reports a request that never got a response as a network error', () => {
    const err = new AxiosError('Network Error', 'ERR_NETWORK');
    const e = toApiError(err);
    expect(e.kind).toBe('network');
    expect(e.status).toBe(0);
    expect(e.traceId).toBeNull();
  });

  it('survives a non-ProblemDetails body without inventing fields', () => {
    const e = toApiError(axiosError(500, '<html>gateway blew up</html>'));
    expect(e.status).toBe(500);
    expect(e.kind).toBe('server');
    expect(e.errorCode).toBeNull();
    expect(e.traceId).toBeNull();
    expect(e.fieldErrors).toEqual([]);
    // Still needs *something* renderable rather than an empty panel.
    expect(e.title.length).toBeGreaterThan(0);
  });

  it('handles a thrown non-Axios value', () => {
    const e = toApiError(new Error('oops'));
    expect(e.kind).toBe('unknown');
    expect(e.title).toBe('oops');
  });

  // lib/http's helpers already throw an ApiError; a screen that normalized it again lost the server's words.
  it('passes an error that is already normalized through unchanged', () => {
    const refused = toApiError(axiosError(422, {
      title: 'Unprocessable Entity', detail: 'A funeral plan is paid monthly, quarterly or annually', traceId: 't-1',
    }));
    expect(toApiError(refused)).toBe(refused);
    expect(toApiError(refused).detail).toBe('A funeral plan is paid monthly, quarterly or annually');
  });

  // A 404 on this platform is not always "missing": the refdata allowlist and the
  // IDOR guards deliberately return 404 for a denied resource so existence is not
  // leaked. The UI must not promise the user the record does not exist.
  it('marks 404 as possibly-denied rather than definitely-absent', () => {
    expect(toApiError(axiosError(404, {})).mayBeDenied).toBe(true);
    expect(toApiError(axiosError(500, {})).mayBeDenied).toBe(false);
  });
});

describe('fieldErrorMap', () => {
  it('keys field errors by field name for form binding', () => {
    const e = toApiError(axiosError(400, problem));
    expect(fieldErrorMap(e)).toEqual({ sumAssured: 'must be positive' });
  });

  it('keeps the first message when a field reports more than one', () => {
    const e = toApiError(
      axiosError(400, {
        ...problem,
        errors: [
          { field: 'amount', message: 'first' },
          { field: 'amount', message: 'second' },
        ],
      }),
    );
    expect(fieldErrorMap(e)).toEqual({ amount: 'first' });
  });

  it('is empty when there are no field errors', () => {
    expect(fieldErrorMap(toApiError(axiosError(403, {})))).toEqual({});
  });
});
