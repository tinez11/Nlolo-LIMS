import { describe, expect, it } from 'vitest';
import { mapApiError } from './problem';

describe('mapApiError', () => {
  it('gives surrender its own explanation, not an error banner', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Not Implemented', status: 501,
      errorCode: 'CHOREOGRAPHY_NOT_IMPLEMENTED', traceId: 't1',
    })).toMatch(/not available online yet/i);
  });

  it('explains insufficient loan value in plain language', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Conflict', status: 409,
      errorCode: 'INSUFFICIENT_LOAN_VALUE', traceId: 't2',
    })).toMatch(/cash value/i);
  });

  it('falls back on an unknown errorCode without exposing internals', () => {
    const message = mapApiError({
      type: 'about:blank', title: 'Conflict', status: 409,
      errorCode: 'SOME_FUTURE_CODE', traceId: 't3',
      detail: 'stack-ish internal text',
    });
    expect(message).not.toContain('stack-ish');
    expect(message).toMatch(/could not be completed/i);
  });

  it('handles a null problem (network failure, non-JSON body)', () => {
    expect(mapApiError(null)).toMatch(/could not reach/i);
  });

  it('distinguishes a 401 so the UI can re-authenticate', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Unauthorized', status: 401, traceId: 't4',
    })).toMatch(/session/i);
  });

  it('distinguishes a 403 as an access problem, not a missing item', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Forbidden', status: 403, traceId: 't5',
    })).toMatch(/do not have access/i);
  });

  it('distinguishes a 404 as a missing item', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Not Found', status: 404, traceId: 't6',
    })).toMatch(/could not find/i);
  });

  it('gives a server error its own copy, distinct from the generic fallback', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Bad Gateway', status: 502, traceId: 't7',
    })).toMatch(/something went wrong on our side/i);
  });

  it('gives validation errors bespoke copy telling the customer to check the form', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Bad Request', status: 400,
      errorCode: 'VALIDATION_ERROR', traceId: 't8',
    })).toMatch(/check the form/i);
  });

  it('tells the customer a Layer 2 claim is already held, not the generic fallback', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Conflict', status: 409,
      errorCode: 'REQUEST_ALREADY_IN_PROGRESS', traceId: 't9',
    })).toMatch(/already being processed/i);
  });

  it('tells the customer to check their policy rather than retry on an indeterminate outcome', () => {
    expect(mapApiError({
      type: 'about:blank', title: 'Gateway Timeout', status: 504,
      errorCode: 'OUTCOME_UNKNOWN', traceId: 't10',
    })).toMatch(/check your policy before retrying/i);
  });
});
