import type { ReactNode } from 'react';
import type { ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';

/**
 * A rejected action, shown where the action was taken.
 *
 * Hand-copied into twenty call sites before this file, each printing the trace id at 10px
 * and 80% opacity on the danger ground -- the one string a support call needs to close was
 * the least legible text on the screen. It is labelled, full strength and `select-all` here,
 * so a staff member can read it out or copy it in one click.
 *
 * `ErrorPanel` is the other error surface and stays separate: it replaces a whole region
 * whose READ failed. This one sits beside a form whose WRITE failed, and the form stays.
 */
export function InlineError({
  error,
  lead,
  children,
  className,
}: {
  error: Pick<ApiError, 'title' | 'detail' | 'traceId'>;
  /**
   * What the person was trying to do, before the server's own words -- "Could not mark it
   * matched". A ProblemDetail says what is wrong with the request, not which of the several
   * actions on the screen sent it, so on a queue where one strip serves every row the lead
   * is the half that names the act.
   */
  lead?: string;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <div
      role="alert"
      className={cn(
        'rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg',
        className,
      )}
    >
      <p>
        {lead ? `${lead} — ` : ''}
        {error.detail ?? error.title}
      </p>
      {children}
      {error.traceId && (
        <p className="mt-1 text-xs">
          Reference <span className="font-mono select-all">{error.traceId}</span>
        </p>
      )}
    </div>
  );
}
