import * as Dialog from '@radix-ui/react-dialog';
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
 *   `useBlocker`, and a popup asks "Leave without saving?" with the safe answer focused. A change on
 *   the SAME page (a tab, a filter in the query string) is not leaving it and is never blocked.
 * - **Closing or reloading the tab** gets the browser's own prompt through `beforeunload`; the
 *   browser words that one, and no page may.
 *
 * A centred popup over a dimmed page (the user, 2026-10-22, replacing a floating bar): the click
 * that triggers it may come from the sidebar, far from the form, and the answer has to be the one
 * thing on screen.
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

  return (
    // A full popup over a dimmed page (the user, 2026-10-22): the choice is the only thing on screen until it is made.
    // Escape and a click outside keep the work, as "Keep editing" does.
    <Dialog.Root open={blocker.state === 'blocked'} onOpenChange={(open) => { if (!open) blocker.reset?.(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/30 dark:bg-black/60" />
        <Dialog.Content
          role="alertdialog"
          aria-labelledby={titleId}
          aria-describedby={bodyId}
          onOpenAutoFocus={(e) => { e.preventDefault(); keep.current?.focus(); }}
          className="fixed top-1/2 left-1/2 z-50 w-[min(28rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-xl bg-surface p-6 shadow-card"
        >
          <Dialog.Title id={titleId} className="text-title">
            Leave without saving?
          </Dialog.Title>
          <Dialog.Description id={bodyId} className="mt-2 text-sm text-muted-foreground">
            {what} is not saved, and leaving this page discards it.
          </Dialog.Description>
          <div className="mt-6 flex items-center justify-end gap-2">
            {/* The safe answer focused, so Enter keeps the work. The leaving button carries its own verb, never
                "OK" (DESIGN.md, Confirm Act). */}
            <Button type="button" variant="ghost" onClick={() => blocker.proceed?.()}>
              Leave
            </Button>
            <Button ref={keep} type="button" variant="primary" onClick={() => blocker.reset?.()}>
              Keep editing
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
