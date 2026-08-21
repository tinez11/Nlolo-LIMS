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
});
