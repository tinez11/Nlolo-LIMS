import * as Popover from '@radix-ui/react-popover';
import { Command as CommandPrimitive } from 'cmdk';
import { Loader2 } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { searchParties } from '@/api/party';
import type { KycStatus, PartyView } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import { UUID_PATTERN } from '@/lib/patterns';

export interface PartyPickerProps {
  /** The selected partyId, or null. This component does not resolve a label
   *  for a pre-existing value on mount -- every integration point on this
   *  platform today is a CREATE form, where value always starts null, so
   *  there is no existing id to resolve a name for. If a future caller needs
   *  to edit an already-selected party, it must pass an initial label some
   *  other way; that is out of scope until a real caller needs it. */
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

export function PartyPicker({ value, onChange, kycStatus, placeholder = 'Search by name…' }: PartyPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null);
  const [results, setResults] = useState<PartyView[]>([]);
  const [status, setStatus] = useState<SearchStatus>('idle');
  const cancelledRef = useRef(false);

  // A directly-typed/pasted UUID (a staff member copying an id from
  // elsewhere out of habit) searches immediately -- it is already a
  // complete, unambiguous value, so waiting for the debounce or the
  // 2-character minimum would only add latency with no benefit.
  const trimmed = query.trim();
  const isUuid = UUID_PATTERN.test(trimmed);

  const performSearch = useCallback(() => {
    cancelledRef.current = false;
    setStatus('loading');
    const timer = setTimeout(
      () => {
        searchParties({ q: trimmed, ...(kycStatus ? { kycStatus } : {}), pageSize: 10 })
          .then((page) => {
            if (cancelledRef.current) return;
            setResults(page.items);
            setStatus('success');
          })
          .catch(() => {
            if (cancelledRef.current) return;
            setStatus('error');
          });
      },
      isUuid ? 0 : DEBOUNCE_MS,
    );

    return () => {
      cancelledRef.current = true;
      clearTimeout(timer);
    };
  }, [trimmed, isUuid, kycStatus]);

  useEffect(() => {
    if (!open || (trimmed.length < MIN_QUERY_LENGTH && !isUuid)) {
      return;
    }

    // eslint-disable-next-line react-hooks/set-state-in-effect
    const cleanup = performSearch();
    return cleanup;
  }, [open, trimmed, isUuid, kycStatus, performSearch]);

  function select(party: PartyView) {
    onChange(party.partyId ?? null, party);
    setSelectedLabel(party.displayName ?? party.partyId ?? null);
    setOpen(false);
    setQuery('');
  }

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label={value && selectedLabel ? selectedLabel : placeholder}
          className="flex h-9 w-full items-center rounded-md border border-input bg-surface px-2.5 text-left text-sm"
        >
          {value && selectedLabel ? (
            selectedLabel
          ) : (
            <span className="text-muted-foreground">{placeholder}</span>
          )}
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={4}
          className="z-50 w-[--radix-popover-trigger-width] rounded-md border border-border bg-surface shadow-lg"
        >
          <CommandPrimitive shouldFilter={false}>
            <CommandPrimitive.Input
              value={query}
              onValueChange={setQuery}
              placeholder="Type a name to search"
              className="h-9 w-full border-b border-border bg-transparent px-2.5 text-sm outline-none"
            />
            <CommandPrimitive.List className="max-h-64 overflow-y-auto p-1">
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
                      <span className="text-[11px] text-muted-foreground">{party.partyType}</span>
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
