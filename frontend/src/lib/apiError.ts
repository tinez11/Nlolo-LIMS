import { AxiosError } from 'axios';

/**
 * Every failure in the console is normalized to this one shape, lifted from the
 * backend's `application/problem+json` (RFC 7807). Stores hold it in their `error`
 * slot; components render it without knowing anything about Axios.
 *
 * `openapi-common.yaml#/components/schemas/ProblemDetails` guarantees type, title,
 * status and traceId, and optionally carries detail, errorCode and a field-level
 * `errors[]` array on 400s.
 */

export type ApiErrorKind =
  | 'network'
  | 'unauthenticated'
  | 'forbidden'
  | 'notFound'
  | 'validation'
  | 'unprocessable'
  | 'conflict'
  | 'notImplemented'
  | 'server'
  | 'unknown';

export interface FieldError {
  field: string;
  message: string;
}

export interface ApiError {
  /** HTTP status, or 0 when the request never reached the server. */
  status: number;
  kind: ApiErrorKind;
  /** Stable machine-readable code, distinct from the HTTP status. */
  errorCode: string | null;
  title: string;
  detail: string | null;
  /** Always present on a real platform response; the only thread back to the logs. */
  traceId: string | null;
  fieldErrors: FieldError[];
  /**
   * True for 404. This platform deliberately returns 404 rather than 403 in places
   * where a 403 would leak existence -- the refdata per-realm allowlist, and the
   * claim-evidence / commission-statement IDOR guards. So the UI must say "not
   * found or not available to you", never "this does not exist".
   */
  mayBeDenied: boolean;
}

const KIND_BY_STATUS: Record<number, ApiErrorKind> = {
  400: 'validation',
  401: 'unauthenticated',
  403: 'forbidden',
  404: 'notFound',
  409: 'conflict',
  422: 'unprocessable',
  501: 'notImplemented',
};

function classify(status: number): ApiErrorKind {
  const known = KIND_BY_STATUS[status];
  if (known) return known;
  if (status >= 500) return 'server';
  return 'unknown';
}

/** Shape-check rather than trust: a 502 from an ingress is HTML, not problem+json. */
function isProblemDetails(body: unknown): body is Record<string, unknown> {
  return typeof body === 'object' && body !== null && !Array.isArray(body);
}

function readString(body: Record<string, unknown>, key: string): string | null {
  const value = body[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function readFieldErrors(body: Record<string, unknown>): FieldError[] {
  const raw = body.errors;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((entry): FieldError[] => {
    if (!isProblemDetails(entry)) return [];
    const field = readString(entry, 'field');
    const message = readString(entry, 'message');
    return field && message ? [{ field, message }] : [];
  });
}

/**
 * An error already normalized -- what `lib/http`'s helpers throw. Normalizing it again read it as an unknown
 * throw and replaced the server's own words with "Something went wrong" (2026-10-08): the funeral quote said that
 * where the server had said "A funeral plan is paid monthly, quarterly or annually".
 */
function isApiError(cause: unknown): cause is ApiError {
  if (typeof cause !== 'object' || cause === null) return false;
  const c = cause as Record<string, unknown>;
  return typeof c.status === 'number' && typeof c.kind === 'string' && typeof c.title === 'string'
    && Array.isArray(c.fieldErrors);
}

/** Normalize anything thrown by the Axios layer into an ApiError; an ApiError passes through unchanged. */
export function toApiError(cause: unknown): ApiError {
  if (isApiError(cause)) return cause;
  if (cause instanceof AxiosError) {
    if (!cause.response) {
      return {
        status: 0,
        kind: 'network',
        errorCode: cause.code ?? null,
        title: 'Could not reach the server',
        detail: cause.message,
        traceId: null,
        fieldErrors: [],
        mayBeDenied: false,
      };
    }

    const { status, data } = cause.response;
    const body = isProblemDetails(data) ? data : null;

    return {
      status,
      kind: classify(status),
      errorCode: body ? readString(body, 'errorCode') : null,
      title: (body && readString(body, 'title')) ?? `Request failed (${status})`,
      detail: body ? readString(body, 'detail') : null,
      traceId: body ? readString(body, 'traceId') : null,
      fieldErrors: body ? readFieldErrors(body) : [],
      mayBeDenied: status === 404,
    };
  }

  return {
    status: 0,
    kind: 'unknown',
    errorCode: null,
    title: cause instanceof Error ? cause.message : 'Something went wrong',
    detail: null,
    traceId: null,
    fieldErrors: [],
    mayBeDenied: false,
  };
}

/**
 * Field name -> message, for binding a 400 onto form inputs (react-hook-form's
 * setError). First message wins when a field reports several.
 */
export function fieldErrorMap(error: ApiError): Record<string, string> {
  const map: Record<string, string> = {};
  for (const { field, message } of error.fieldErrors) {
    if (!(field in map)) map[field] = message;
  }
  return map;
}
