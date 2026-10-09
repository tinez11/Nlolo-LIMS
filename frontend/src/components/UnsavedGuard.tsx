import { useContext, useEffect, useId, useRef } from 'react';
import { UNSAFE_DataRouterContext, useBlocker } from 'react-router-dom';
import { Button } from './ui/button';

/**
 * Asks before unsaved work is left behind (2026-10-09, review C1, decision D7).
 *
 * There was no guard anywhere: one click on the sidebar while a 1,500-line product version form
 * was half filled in discarded it without a word. Two ways out are covered:
 *
 * - **In-app navigation** -- a nav link, a breadcrumb, Back -- is held by React Router's
 *   `useBlocker`, and a bar asks "Leave without saving?" with the safe answer focused. A change on
 *   the SAME page (a tab, a filter in the query string) is not leaving it and is never blocked.
 * - **Closing or reloading the tab** gets the browser's own prompt through `beforeunload`; the
 *   browser words that one, and no page may.
 *
 * Inline in spirit, as DESIGN.md's Confirm Act asks: no backdrop, nothing trapped, the form still
 * on screen above it. It floats only because the click that triggered it may have come from the
 * sidebar, far from the form, and an answer the person cannot see is no answer.
 *
 * `useBlocker` throws outside a data router, and component tests render in a plain MemoryRouter,
 * so the blocking half mounts only when a data router is present. The app has one since C1.
 *
 * Callers pass `when={isDirty && !isSubmitting}`: a successful submit navigates to the new record
 * while the form is still dirty, and that is not leaving work behind.
 */
export function UnsavedGuard({ when, what }: { when: boolean; what: string }) {
  const dataRouter = useContext(UNSAFE_DataRouterContext);

  useEffect(() => {
    if (!when) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [when]);

  return dataRouter ? <LeavePrompt when={when} what={what} /> : null;
}

function LeavePrompt({ when, what }: { when: boolean; what: string }) {
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) => when && currentLocation.pathname !== nextLocation.pathname,
  );
  const keep = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const bodyId = useId();

  useEffect(() => {
    if (blocker.state === 'blocked') keep.current?.focus();
  }, [blocker.state]);

  if (blocker.state !== 'blocked') return null;

  return (
    <div
      role="alertdialog"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      className="fixed inset-x-0 bottom-4 z-50 mx-auto w-[min(36rem,calc(100vw-2rem))] rounded-md border border-border-strong bg-surface p-3 shadow-lg"
    >
      <p id={titleId} className="text-sm font-semibold">
        Leave without saving?
      </p>
      <p id={bodyId} className="mt-1 text-xs text-muted-foreground">
        {what} is not saved, and leaving this page discards it.
      </p>
      <div className="mt-3 flex items-center gap-1.5">
        {/* The safe answer first and focused, so Enter keeps the work. The leaving button
            carries its own verb, never "OK" (DESIGN.md, Confirm Act). */}
        <Button ref={keep} type="button" size="sm" variant="primary" onClick={() => blocker.reset?.()}>
          Keep editing
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={() => blocker.proceed?.()}>
          Leave
        </Button>
      </div>
    </div>
  );
}
