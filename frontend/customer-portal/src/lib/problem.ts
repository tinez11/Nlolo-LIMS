/**
 * Single place that turns a backend RFC 7807 ProblemDetails into user-facing copy. Screens branch
 * on the outcome of this function, never on an HTTP status directly.
 *
 * `detail` from the backend is never shown verbatim: it is written for an operator, and on some
 * paths carries internal specifics. Two errorCodes get bespoke copy because they are expected
 * states rather than faults — CHOREOGRAPHY_NOT_IMPLEMENTED (surrender is a documented 501) and
 * INSUFFICIENT_LOAN_VALUE (fires on every loan attempt today, because cash value is never
 * credited platform-wide).
 */
export type ApiProblem = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  errorCode?: string;
  traceId: string;
  errors?: Array<{ field: string; message: string }>;
};

const BY_ERROR_CODE: Record<string, string> = {
  CHOREOGRAPHY_NOT_IMPLEMENTED:
    'Policy surrender is not available online yet. Please contact your agent to start a surrender.',
  INSUFFICIENT_LOAN_VALUE:
    'This policy has no cash value available to borrow against yet, so a loan cannot be issued.',
  VALIDATION_ERROR:
    'Some of the details entered are not valid. Please check the form and try again.',
  REQUEST_ALREADY_IN_PROGRESS:
    'This request is already being processed. Please wait a moment before trying again.',
  OUTCOME_UNKNOWN:
    'We could not confirm whether this request went through. Please check your policy before retrying.',
};

export function mapApiError(problem: ApiProblem | null, fallbackStatus?: number): string {
  if (!problem) {
    return 'We could not reach the service. Please check your connection and try again.';
  }
  if (problem.errorCode && BY_ERROR_CODE[problem.errorCode]) {
    return BY_ERROR_CODE[problem.errorCode];
  }
  const status = problem.status || fallbackStatus || 0;
  if (status === 401) {
    return 'Your session has expired. Please sign in again.';
  }
  if (status === 403) {
    return 'You do not have access to this item.';
  }
  if (status === 404) {
    return 'We could not find that item.';
  }
  if (status >= 500) {
    return `Something went wrong on our side. Please try again shortly. (Reference: ${problem.traceId})`;
  }
  return `That request could not be completed. (Reference: ${problem.traceId})`;
}
