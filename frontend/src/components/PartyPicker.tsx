import * as Popover from '@radix-ui/react-popover';
import { Command as CommandPrimitive } from 'cmdk';
import { Loader2, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { getParty, searchParties } from '@/api/party';
import type { KycStatus, PartyView } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import type { ApiError } from '@/lib/http';
import { UUID_PATTERN } from '@/lib/patterns';
import { useFieldControl } from './fieldControl';
import { POPOVER_MOTION } from './ui/motion';
import { cn } from '@/lib/cn';

export interface PartyPickerProps {
  /** The selected partyId, or null. If `value` is set but no selection has
   *  happened in this component instance yet (e.g. an edit form seeding a
   *  pre-existing party), the label is resolved via `GET /parties/{id}` on
   *  mount/value-change so the field never renders a populated value as an
   *  empty placeholder. */
  value: string | null;
  onChange: (partyId: string | null, party: PartyView | null) => void;
  /** Pre-filters the search to only this KYC status -- e.g. `VERIFIED` for
   *  agent onboarding, which the backend already rejects any other status for. */
  kycStatus?: KycStatus;
  placeholder?: string;
}

const DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;

type SearchStatus = 'idle' | 'loading' | 'success' | 'error';

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

export function PartyPicker({ value, onChange, kycStatus, placeholder = 'Search by name…' }: PartyPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null);
  const [results, setResults] = useState<PartyView[]>([]);
  const [status, setStatus] = useState<SearchStatus>('idle');
  // Tracks which value this instance has already resolved (or attempted to
  // resolve) a label for, so an external value we didn't just select from
  // this component's own list -- e.g. an edit form seeding a real,
  // pre-existing party -- doesn't render as an empty placeholder, and so a
  // 404/network hiccup on that lookup doesn't retry every render.
  const resolvedForValue = useRef<string | null>(null);
  const { id: fieldId } = useFieldControl();

  // A directly-typed/pasted UUID (a staff member copying an id from
  // elsewhere out of habit) searches immediately -- it is already a
  // complete, unambiguous value, so waiting for the debounce or the
  // 2-character minimum would only add latency with no benefit.
  const trimmed = query.trim();
  const isUuid = UUID_PATTERN.test(trimmed);

  useEffect(() => {
    if (!open) return;
    if (trimmed.length < MIN_QUERY_LENGTH && !isUuid) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setResults([]);
      setStatus('idle');
      return;
    }

    let cancelled = false;
    // Cleared immediately, not left showing the previous query's rows under
    // the spinner -- those stayed clickable otherwise.
    setResults([]);
    setStatus('loading');
    const timer = setTimeout(
      () => {
        // `q` matches displayName only -- a pasted UUID needs the direct
        // by-id lookup, not a text search that can never match it.
        const request: Promise<PartyView[]> = isUuid
          ? getParty(trimmed).then((party) => (kycStatus && party.kycStatus !== kycStatus ? [] : [party]))
          : searchParties({ q: trimmed, ...(kycStatus ? { kycStatus } : {}), pageSize: 10 }).then(
              (page) => page.items,
            );

        request
          .then((items) => {
            if (cancelled) return;
            setResults(items);
            setStatus('success');
          })
          .catch((error: unknown) => {
            if (cancelled) return;
            // A pasted id that simply doesn't resolve to any party is "no
            // matches", the same outcome as a name search with zero hits --
            // not a failure of the search itself.
            if (isUuid && isNotFound(error)) {
              setResults([]);
              setStatus('success');
              return;
            }
            setStatus('error');
          });
      },
      isUuid ? 0 : DEBOUNCE_MS,
    );

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [open, trimmed, isUuid, kycStatus]);

  // Resolves a label for a `value` this component didn't just set itself
  // (via select()/clear() below, which set selectedLabel synchronously) --
  // an edit form seeding a real, pre-existing party id being the real case.
  useEffect(() => {
    if (!value || resolvedForValue.current === value) return;
    resolvedForValue.current = value;
    let cancelled = false;
    getParty(value)
      .then((party) => {
        if (cancelled || resolvedForValue.current !== value) return;
        setSelectedLabel(party.displayName ?? party.partyId ?? value);
      })
      .catch(() => {
        // Leave selectedLabel unset -- the trigger falls back to the
        // placeholder rather than crashing or showing a raw id.
      });
    return () => {
      cancelled = true;
    };
  }, [value]);

  function select(party: PartyView) {
    resolvedForValue.current = party.partyId ?? null;
    onChange(party.partyId ?? null, party);
    setSelectedLabel(party.displayName ?? party.partyId ?? null);
    setOpen(false);
    setQuery('');
  }

  function clear(e: React.MouseEvent) {
    e.stopPropagation();
    resolvedForValue.current = null;
    onChange(null, null);
    setSelectedLabel(null);
  }

  return (
    /*
      `modal` locks page scroll while the list is open, and that is a bug fix
      rather than a modality preference.

      The content is portalled and fixed-position, so Radix re-anchors it to the
      trigger on every scroll of the `<main>` column it lives in. That is correct
      behaviour and it is also a chase: scroll the page toward an option and the
      trigger moves, the list re-anchors, and the option you were reaching for
      moves with it. PLAN.md §14.5 recorded this as an intermittent e2e flake and
      the cause was guessed at but never pinned; §14.8 pinned it by making it
      deterministic -- moving the Beneficiaries panel down a long policy page put
      the trigger far enough into the scroll that the chase happened every time.

      Locking the scroll removes the chase at its source: while you are choosing
      from a list anchored to a field, the page underneath does not move. Outside
      clicks still dismiss.
    */
    <Popover.Root open={open} onOpenChange={setOpen} modal>
      <div className="relative">
        <Popover.Trigger asChild>
          <button
            type="button"
            // The id FormField's `<label htmlFor>` points at. A <button> is a
            // labelable element, so the field's caption names this trigger --
            // which it did implicitly when FormField wrapped its child, and
            // stopped doing when the label became explicit.
            {...(fieldId ? { id: fieldId } : {})}
            // aria-label still wins for the accessible NAME, deliberately: the
            // twenty-odd specs that reach this control search for the
            // placeholder text, and more importantly "Search for the
            // policyholder by name" says what the button does, where the
            // field's caption only says what the value means.
            aria-label={value && selectedLabel ? selectedLabel : placeholder}
            className="flex h-9 w-full items-center rounded-md border border-input bg-surface px-2.5 text-left text-sm"
          >
            {value && selectedLabel ? (
              <span className="min-w-0 truncate pr-6">{selectedLabel}</span>
            ) : (
              <span className="min-w-0 truncate text-muted-foreground">{placeholder}</span>
            )}
          </button>
        </Popover.Trigger>
        {value && (
          <button
            type="button"
            aria-label="Clear selection"
            onClick={clear}
            className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:bg-hover hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={4}
          // Keeps the dropdown inside the window when the trigger sits low on a
          // long page. Radix flips sides on its own, but that only helps if the
          // content FITS on the flipped side; a 264px list against 200px of free
          // space still overflows, so the list is also capped at the height
          // Radix reports as available (below).
          //
          // This was tried as a fix for the intermittent e2e failure in PLAN.md
          // §14.5 and DID NOT fix it -- kept because it is a real improvement on
          // its own, but the flake has a different cause. The failure screenshot
          // shows the page scrolled DOWN with this panel off the TOP of the
          // viewport, which points at the portalled, fixed-position content
          // chasing a trigger inside the scrolling <main>, not at overflow below
          // the fold.
          collisionPadding={8}
          className={cn('z-50 w-[--radix-popover-trigger-width] rounded-md border border-border bg-surface shadow-lg', POPOVER_MOTION)}
        >
          <CommandPrimitive shouldFilter={false}>
            <CommandPrimitive.Input
              value={query}
              onValueChange={setQuery}
              placeholder="Type a name to search"
              className="h-9 w-full border-b border-border bg-transparent px-2.5 text-sm outline-none"
            />
            {/* 16rem, or whatever Radix says is actually left on screen —
                whichever is smaller. The var only exists because avoidCollisions
                is on (its default). */}
            <CommandPrimitive.List className="max-h-[min(16rem,var(--radix-popover-content-available-height))] overflow-y-auto p-1">
              {trimmed.length < MIN_QUERY_LENGTH && !isUuid && (
                <p className="px-2.5 py-2 text-xs text-muted-foreground">Type a name to search</p>
              )}
              {trimmed.length >= MIN_QUERY_LENGTH || isUuid ? (
                <>
                  {status === 'loading' && (
                    <div className="flex items-center gap-2 px-2.5 py-2 text-xs text-muted-foreground">
                      <Loader2 className="size-3.5 animate-spin" />
                      Searching…
                    </div>
                  )}
                  {status === 'error' && (
                    <p className="px-2.5 py-2 text-xs text-status-danger-fg">Couldn't search — try again</p>
                  )}
                  {status === 'success' && results.length === 0 && (
                    <p className="px-2.5 py-2 text-xs text-muted-foreground">No matches for '{trimmed}'</p>
                  )}
                  {results
                    .filter((party) => party.partyId)
                    .map((party) => (
                  <CommandPrimitive.Item
                    key={party.partyId}
                    value={party.partyId!}
                    onSelect={() => select(party)}
                    className="flex cursor-pointer items-center justify-between gap-2 rounded px-2.5 py-1.5 text-sm data-[selected=true]:bg-hover"
                  >
                    <span className="min-w-0 truncate">{party.displayName ?? '—'}</span>
                    <span className="flex shrink-0 items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">{party.partyType}</span>
                      {party.kycStatus && <StatusBadge kind="kyc" value={party.kycStatus} />}
                    </span>
                  </CommandPrimitive.Item>
                ))}
                </>
              ) : null}
            </CommandPrimitive.List>
          </CommandPrimitive>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
