import { AlertTriangle, Ban, Inbox, Loader2, ShieldOff, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import type { ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { Button } from './ui/button';

/** Shared loading / empty / error surfaces, so every screen fails the same way. */

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('size-4 animate-spin', className)} aria-hidden />;
}

/**
 * A block of placeholder lines, for content whose rows are on their way (2026-10-09).
 *
 * It was a spinner centred in a 150px void, in 82 feature files -- mostly inside panels, where the
 * real content then replaced the void and moved everything below it. Lines at staggered widths say
 * "rows of text go here", and the page moves less when they land. `TableSkeleton` is still the one
 * for a table.
 *
 * The label stays visible, in Micro and Subtle Ink: e2e waits for a load to end with
 * `getByText(label)).not.toBeVisible()`, which an sr-only label would satisfy immediately.
 */
export function LoadingBlock({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="space-y-2.5 px-4 py-5" role="status">
      {[62, 48, 34].map((width) => (
        <div
          key={width}
          className="h-3 animate-pulse rounded bg-control"
          style={{ width: `${width}%` }}
          aria-hidden
        />
      ))}
      <p className="pt-1 text-xs text-subtle-foreground">{label}</p>
    </div>
  );
}

/** Skeleton rows, so a table does not jump when real data lands. */
export function TableSkeleton({ rows = 8, columns = 5 }: { rows?: number; columns?: number }) {
  return (
    <div className="divide-y divide-border" aria-hidden>
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="flex items-center gap-4 px-4 py-3.5">
          {Array.from({ length: columns }, (_, c) => (
            <div
              key={c}
              className="h-3 animate-pulse rounded bg-control"
              style={{ width: `${[22, 16, 14, 12, 10][c % 5]}%` }}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

/**
 * A role refusal shown BEFORE the work, not after it.
 *
 * Every other gated surface on this console learns it is gated from the server: it issues its
 * read, gets a 403, and `ErrorPanel` says so. That works because those screens fetch on mount.
 * An authoring form fetches nothing — so without this, a staff member without the role would
 * fill the whole form and be refused only on submit, having done the work twice: once here and
 * once in whatever they have to do to get it authorised.
 *
 * The headline is deliberately the SAME sentence `ErrorPanel` uses for a 403, so a refusal
 * reads identically whether it came from the server or from the token in hand.
 */
export function NoAccess({ what, who }: { what: string; who: string }) {
  return (
    <div className="mx-6 my-8 max-w-xl rounded-md border border-border bg-surface px-4 py-5">
      <p className="text-sm font-medium">You do not have access to this</p>
      <p className="mt-1.5 text-xs text-muted-foreground">
        {what} is restricted to {who}. Your session does not carry that role.
      </p>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-16 text-center">
      <Inbox className="size-5 text-subtle-foreground" aria-hidden />
      <p className="text-sm font-medium">{title}</p>
      {description && (
        <p className="max-w-sm text-xs text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

const ICON_BY_KIND = {
  network: WifiOff,
  forbidden: ShieldOff,
  notImplemented: Ban,
} as const;

/**
 * Renders an ApiError.
 *
 * Deliberately different copy per kind, because the platform's status codes mean
 * specific things: 403 is a real permission boundary rather than a transient
 * failure, 404 may be a *disguised* denial (the refdata allowlist and the IDOR
 * guards return 404 so existence is not leaked), and 501 marks the two genuinely
 * unimplemented workflow endpoints, which must not offer a retry.
 */
export function ErrorPanel({
  error,
  onRetry,
  className,
}: {
  error: ApiError;
  onRetry?: () => void;
  className?: string;
}) {
  const Icon =
    error.kind in ICON_BY_KIND
      ? ICON_BY_KIND[error.kind as keyof typeof ICON_BY_KIND]
      : AlertTriangle;

  const retryable =
    onRetry !== undefined &&
    error.kind !== 'forbidden' &&
    error.kind !== 'notImplemented' &&
    error.kind !== 'unauthenticated';

  return (
    <div
      className={cn(
        'flex flex-col items-center gap-2 px-6 py-14 text-center',
        className,
      )}
      role="alert"
    >
      <Icon className="size-5 text-status-danger-fg" aria-hidden />
      <p className="text-sm font-medium">{headline(error)}</p>
      <p className="max-w-md text-xs text-muted-foreground">{explain(error)}</p>

      {retryable && (
        <Button size="sm" onClick={onRetry} className="mt-2">
          Try again
        </Button>
      )}

      {error.traceId && (
        // The only thread back to the backend logs. Selectable, not decorative.
        <p className="mt-3 font-mono text-xs text-subtle-foreground select-all">
          trace {error.traceId}
        </p>
      )}
    </div>
  );
}

function headline(error: ApiError): string {
  switch (error.kind) {
    case 'network':
      return 'Could not reach the server';
    case 'unauthenticated':
      return 'Your session has expired';
    case 'forbidden':
      return "You do not have access to this";
    case 'notFound':
      return 'Not found';
    case 'notImplemented':
      return 'Not available yet';
    case 'conflict':
      return 'That has already changed';
    default:
      return error.title;
  }
}

function explain(error: ApiError): string {
  switch (error.kind) {
    case 'network':
      return 'Check your connection. Nothing was submitted.';
    case 'unauthenticated':
      return 'Signing you back in.';
    case 'forbidden':
      return 'Your role does not permit this. Ask an administrator if you think it should.';
    case 'notFound':
      // Never promise absence: a denied resource is deliberately indistinguishable.
      return error.mayBeDenied
        ? 'This record does not exist, or it is not available to your role.'
        : 'This record does not exist.';
    case 'notImplemented':
      return 'This operation is not implemented on the platform yet.';
    case 'conflict':
      return 'Someone else changed this first. Reload and review before retrying.';
    default:
      return error.detail ?? 'Something went wrong.';
  }
}
