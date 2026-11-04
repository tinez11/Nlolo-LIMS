import * as Popover from '@radix-ui/react-popover';
import { Bell, ChevronRight } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getActions, type ActionGroup, type ActionsView } from '@/api/actions';
import { POPOVER_MOTION } from '@/components/ui/motion';
import { cn } from '@/lib/cn';
import { actionLink, allLink, kindLabel, waitedFor } from './actionLinks';

/** How often the bell asks again while the tab is visible (2026-11-03, the user: every 30 seconds is enough). */
export const BELL_REFRESH_MS = 30_000;

/** Jobs an opened group shows before "Show all": enough to start on, never a wall. */
const SHOWN_PER_GROUP = 5;

/**
 * The bell (2026-11-03, the user: notify on needed actions, not on what has been done). Every job waits for the
 * viewer, read live from the records, so it leaves the list when anybody does it -- there is nothing to mark read.
 *
 * Grouped by kind of work (the user, on seeing one mixed list of 452): a line per kind with its own count shows what
 * waits at a glance, and opening one lists its newest jobs with the person first.
 */
export function ActionBell({ realm }: { realm: 'staff' | 'agents' }) {
  const navigate = useNavigate();
  const [view, setView] = useState<ActionsView | null>(null);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  // One kind open at a time: opening another closes it, so a long kind never buries the rest.
  const [openKind, setOpenKind] = useState<string | null>(null);

  const refresh = useCallback(() => {
    getActions().then(
      (v) => { setView(v); setFailed(false); },
      () => setFailed(true),
    );
  }, []);

  useEffect(() => {
    refresh();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refresh();
    }, BELL_REFRESH_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refresh]);

  const total = view?.total ?? 0;
  const label = view === null
    ? 'Jobs waiting for you'
    : `${total === 0 ? 'No' : total} ${total === 1 ? 'job' : 'jobs'} waiting for you`;

  function go(to: string) {
    setOpen(false);
    navigate(to);
  }

  return (
    <Popover.Root open={open} onOpenChange={(next) => { setOpen(next); if (next) refresh(); }}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label={label}
          className="relative grid size-9 shrink-0 place-items-center rounded-md bg-control text-muted-foreground transition-colors hover:bg-control-hover hover:text-foreground pointer-coarse:size-11"
        >
          <Bell className="size-4" aria-hidden />
          {total > 0 && (
            <span
              aria-hidden
              className="absolute -right-1.5 -top-1.5 grid h-5 min-w-5 place-items-center rounded-full bg-status-danger-bg px-1 text-xs font-semibold leading-none text-status-danger-fg ring-2 ring-surface-muted tabular-nums"
            >
              {total > 9 ? '9+' : total}
            </span>
          )}
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        {/* Below the bell, not beside it: beside the sidebar there is no room on a phone, where the drawer fills the
            screen's left, and the panel opened off it. */}
        <Popover.Content
          side="bottom"
          align="start"
          sideOffset={8}
          collisionPadding={12}
          className={cn('z-50 flex max-h-[min(36rem,var(--radix-popover-content-available-height))] w-96 max-w-[calc(100vw-2rem)] flex-col rounded-xl border border-border bg-surface shadow-lg', POPOVER_MOTION)}
        >
          <div className="flex items-baseline justify-between gap-3 px-4 pb-3 pt-3.5">
            <h2 className="text-title">Waiting for you</h2>
            {view && total > 0 && (
              <p className="text-xs text-muted-foreground tabular-nums">
                {total.toLocaleString()} {total === 1 ? 'job' : 'jobs'}
              </p>
            )}
          </div>
          {failed && view === null ? (
            <p role="alert" className="px-4 pb-4 text-xs text-status-danger-fg">Could not check for jobs just now. Try again shortly.</p>
          ) : view === null ? (
            <p className="px-4 pb-4 text-xs text-muted-foreground">Checking…</p>
          ) : view.groups.length === 0 ? (
            <p className="px-4 pb-4 text-sm text-muted-foreground">
              {view.incomplete ? 'Some work could not be checked just now.' : 'Nothing is waiting for you.'}
            </p>
          ) : (
            <ul className="min-h-0 overflow-y-auto border-t border-border">
              {view.groups.map((group) => (
                <Group
                  key={group.kind}
                  group={group}
                  realm={realm}
                  expanded={view.groups.length === 1 || openKind === group.kind}
                  onToggle={() => setOpenKind((k) => (k === group.kind ? null : group.kind))}
                  onGo={go}
                />
              ))}
            </ul>
          )}
          {view?.incomplete && view.groups.length > 0 && (
            <p className="border-t border-border px-4 py-2 text-xs text-muted-foreground">
              Some work could not be checked just now, so there may be more.
            </p>
          )}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

/** One kind of work: its name and count, and when opened its newest jobs, the person first. */
function Group({ group, realm, expanded, onToggle, onGo }: {
  group: ActionGroup;
  realm: 'staff' | 'agents';
  expanded: boolean;
  onToggle: () => void;
  onGo: (to: string) => void;
}) {
  const all = allLink(realm, group.kind);
  // Where a full list exists the bell shows a few and links to it; where it is the only list, all it was sent.
  const shown = all ? group.items.slice(0, SHOWN_PER_GROUP) : group.items;
  const label = kindLabel(group.kind);

  return (
    <li className="border-b border-border last:border-b-0">
      <button
        type="button"
        aria-expanded={expanded}
        onClick={onToggle}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-hover focus-visible:-outline-offset-2"
      >
        <ChevronRight
          className={cn('size-4 shrink-0 text-muted-foreground transition-transform', expanded && 'rotate-90')}
          aria-hidden
        />
        <span className="min-w-0 flex-1 truncate text-sm font-medium">{label}</span>
        <span className="shrink-0 rounded-full bg-control px-2 py-0.5 text-xs font-semibold tabular-nums">
          {group.total.toLocaleString()}
        </span>
      </button>
      {expanded && (
        <div className="pb-2">
          <ul>
            {shown.map((item) => {
              const to = actionLink(realm, group.kind, item);
              return (
                <li key={item.reference + item.title}>
                  <button
                    type="button"
                    disabled={!to}
                    onClick={() => { if (to) onGo(to); }}
                    className="flex w-full items-baseline gap-3 py-1.5 pl-11 pr-4 text-left transition-colors hover:bg-hover focus-visible:-outline-offset-2 disabled:cursor-default"
                  >
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate text-sm">{item.title}</span>
                      <span className="truncate text-xs text-muted-foreground">{item.detail}</span>
                    </span>
                    {item.since && (
                      <span className="shrink-0 text-xs text-muted-foreground">{waitedFor(item.since)}</span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
          {all && group.total > shown.length && (
            <button
              type="button"
              onClick={() => onGo(all)}
              className="ml-11 mt-1 rounded-md text-xs font-medium text-accent hover:underline"
            >
              Show all {group.total.toLocaleString()}
            </button>
          )}
          {!all && group.total > shown.length && (
            <p className="ml-11 mt-1 text-xs text-muted-foreground">
              The newest {shown.length} of {group.total.toLocaleString()}. Each opens where it is done.
            </p>
          )}
        </div>
      )}
    </li>
  );
}
