import { useEffect, useRef, useState, type ReactNode } from 'react';
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
 *
 * <p><b>It goes away as the person corrects the form (2026-10-20).</b> A server's refusal --
 * "Client reference BLIC001 already belongs to another client" -- used to stay on screen until
 * Submit was pressed again, so the form went on saying it was wrong after it had been put right.
 * Now the first edit inside the same form -- its `<form>`, else the nearest `data-edit-scope`
 * container (a form laid out without one), else the panel it sits in -- hides it; submitting again shows the server's answer to the
 * corrected form, which is the only way to know it is now acceptable. A different error -- told
 * apart by its words and its reference, never by object identity, which a re-render changes --
 * always shows. Every form in the console gets this from here rather than one at a time.
 *
 * `sticky` is for an error that is not the result of the form's own submission -- a product
 * version or a list that could not be READ while the form is open -- which typing elsewhere
 * does nothing to resolve, so it must not disappear.
 */
export function InlineError({
  error,
  lead,
  children,
  className,
  sticky = false,
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
  /** A failed READ shown inside a form: kept until it is resolved, not hidden by editing. */
  sticky?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const identity = `${error.title}|${error.detail ?? ''}|${error.traceId ?? ''}`;
  const [dismissed, setDismissed] = useState<string | null>(null);

  useEffect(() => {
    if (sticky) return undefined;
    // The form it answers for: a real <form>, else a container marked as one, else the panel it sits in.
    const here = ref.current;
    const scope = here?.closest('form') ?? here?.closest('[data-edit-scope]') ?? here?.closest('section');
    if (!scope) return undefined;
    // Only an edit the person makes: typing, choosing, ticking. Pressing Submit again is not an
    // edit, so a refusal stays put while the same form is resent.
    const onEdit = () => setDismissed(identity);
    scope.addEventListener('input', onEdit);
    scope.addEventListener('change', onEdit);
    return () => {
      scope.removeEventListener('input', onEdit);
      scope.removeEventListener('change', onEdit);
    };
  }, [identity, sticky]);

  if (!sticky && dismissed === identity) return null;

  return (
    <div
      ref={ref}
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
