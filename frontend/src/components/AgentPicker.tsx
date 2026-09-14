import * as Popover from '@radix-ui/react-popover';
import { Command as CommandPrimitive } from 'cmdk';
import { Loader2, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { getAgent, listAgents } from '@/api/distribution';
import type { AgentView } from '@/api/types';
import { AgentName } from '@/components/AgentName';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import type { ApiError } from '@/lib/http';
import { UUID_PATTERN } from '@/lib/patterns';
import { useFieldControl } from './fieldControl';

/**
 * Chooses the agent of record BY NAME.
 *
 * <p>What it replaces: a bare text input that took a raw uuid. Nobody knows an
 * agent's uuid, so in practice one got copied from somewhere else — and a wrong
 * one was not inert. The policy was attributed to nobody, the accrual listener
 * logged "does not resolve to an agent" into a server log, and the sale silently
 * earned the agent nothing. This platform's own dev data carries six policies
 * pointing at one phantom id.
 *
 * <p>Searching by NAME is only possible because `GET /agents`'s `q` now matches
 * a person's name as well as a licence number — an agent has no name in the
 * distribution context, so the backend resolves names to party ids through
 * `party` and filters ids it already holds. A pasted uuid still works, and goes
 * straight to `GET /agents/{id}` rather than through a text search that could
 * never match it.
 *
 * <p>Deliberately NOT a `PartyPicker` with a different filter: most parties are
 * customers, and a picker that lets you select one and then fails at submit is
 * the same silent-attribution problem wearing a nicer control. Only agents are
 * offered here.
 */

export interface AgentPickerProps {
  /** The selected agentId, or null. */
  value: string | null;
  onChange: (agentId: string | null, agent: AgentView | null) => void;
  placeholder?: string;
}

const DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;

type SearchStatus = 'idle' | 'loading' | 'success' | 'error';

function isNotFound(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as ApiError).kind === 'notFound';
}

export function AgentPicker({
  value,
  onChange,
  placeholder = 'Search agents by name or licence…',
}: AgentPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<AgentView[]>([]);
  const [status, setStatus] = useState<SearchStatus>('idle');
  const { id: fieldId } = useFieldControl();

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
    setResults([]);
    setStatus('loading');
    const timer = setTimeout(
      () => {
        const request: Promise<AgentView[]> = isUuid
          ? getAgent(trimmed).then((agent) => [agent])
          : listAgents({ q: trimmed, pageSize: 10 }).then((page) => page.items);

        request
          .then((items) => {
            if (cancelled) return;
            setResults(items);
            setStatus('success');
          })
          .catch((error: unknown) => {
            if (cancelled) return;
            // A pasted id resolving to no agent is "no matches", the same outcome
            // as a name search with zero hits — not a failure of the search.
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
  }, [open, trimmed, isUuid]);

  function select(agent: AgentView) {
    onChange(agent.agentId ?? null, agent);
    setOpen(false);
    setQuery('');
  }

  function clear(e: React.MouseEvent) {
    e.stopPropagation();
    onChange(null, null);
  }

  return (
    // `modal` locks page scroll while the list is open, for the same reason
    // PartyPicker does: the portalled content re-anchors to the trigger on every
    // scroll, so scrolling toward an option moves the option.
    <Popover.Root open={open} onOpenChange={setOpen} modal>
      <div className="relative">
        <Popover.Trigger asChild>
          <button
            type="button"
            {...(fieldId ? { id: fieldId } : {})}
            // The placeholder is the accessible name whether or not something is
            // selected: it says what the control DOES, and it stays stable, so a
            // test that finds this control does not break the first time an
            // agent is renamed.
            aria-label={placeholder}
            className="flex h-9 w-full items-center rounded-md border border-input bg-surface px-2.5 text-left text-sm"
          >
            {value ? (
              <span className="min-w-0 truncate pr-6">
                <AgentName agentId={value} />
              </span>
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
          collisionPadding={8}
          className="z-50 w-[--radix-popover-trigger-width] rounded-md border border-border bg-surface shadow-lg"
        >
          <CommandPrimitive shouldFilter={false}>
            <CommandPrimitive.Input
              value={query}
              onValueChange={setQuery}
              placeholder="Type a name or licence number"
              className="h-9 w-full border-b border-border bg-transparent px-2.5 text-sm outline-none"
            />
            <CommandPrimitive.List className="max-h-[min(16rem,var(--radix-popover-content-available-height))] overflow-y-auto p-1">
              {trimmed.length < MIN_QUERY_LENGTH && !isUuid && (
                <p className="px-2.5 py-2 text-xs text-muted-foreground">
                  Type a name or licence number to search
                </p>
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
                    <p className="px-2.5 py-2 text-xs text-status-danger-fg">
                      Couldn't search — try again
                    </p>
                  )}
                  {status === 'success' && results.length === 0 && (
                    <p className="px-2.5 py-2 text-xs text-muted-foreground">
                      No agents match '{trimmed}'
                    </p>
                  )}
                  {results
                    .filter((agent) => agent.agentId)
                    .map((agent) => (
                      <CommandPrimitive.Item
                        key={agent.agentId}
                        value={agent.agentId!}
                        onSelect={() => select(agent)}
                        className="flex cursor-pointer items-center justify-between gap-2 rounded px-2.5 py-1.5 text-sm data-[selected=true]:bg-hover"
                      >
                        <span className="min-w-0 truncate">
                          {/* The name lives on the party, not the agent -- an
                              agent row carries only a partyId and a licence. */}
                          {agent.partyId ? (
                            <PartyName partyId={agent.partyId} />
                          ) : (
                            <span className="font-mono text-xs">{agent.agentId}</span>
                          )}
                        </span>
                        <span className="flex shrink-0 items-center gap-1.5">
                          <span className="font-mono text-[11px] text-muted-foreground">
                            {agent.licenseNumber}
                          </span>
                          {agent.licenseStatus && (
                            <StatusBadge kind="agentLicense" value={agent.licenseStatus} />
                          )}
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
