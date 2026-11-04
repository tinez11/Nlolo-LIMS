import * as Dialog from '@radix-ui/react-dialog';
import { Command, defaultFilter } from 'cmdk';
import { Building2, FileText, User } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { searchParties } from '@/api/party';
import type { PartyView } from '@/api/types';
import type { Realm } from '@/auth/realms';
import { clientActions } from '@/lib/quickActions';
import type { NavItem } from '@/screens';
import { resolveJump } from './jump';

/**
 * Ctrl+K: go to any screen this person can reach, open a policy by its number, or find a person or group by name and
 * go straight to an act on them (2026-11-04, quick actions).
 *
 * Built from the same `navFor` result as the sidebar, so the palette can never offer a
 * screen the role cannot see -- one source, not a second list that drifts.
 *
 * People are searched only for someone who can open the client lists -- the same nav result again -- and each found
 * person carries the acts their record page offers (clientActions), so a claims officer types a name and picks
 * "Register a claim" without opening the record first.
 */
export function CommandPalette({
  realm,
  groups,
  open,
  onOpenChange,
}: {
  realm: Realm;
  groups: { label: string; items: NavItem[] }[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const jump = resolveJump(query);
  const people = usePeopleSearch(
    realm === 'staff' && groups.some((g) => g.items.some((i) => i.to.startsWith('clients/'))),
    jump ? '' : query,
  );

  const close = (open: boolean) => {
    // Cleared on EVERY close, not only on a jump: Escape and a click on the overlay both
    // land here, and reopening onto a stale query and its filtered list reads as a box
    // that remembered something it had no business remembering.
    if (!open) setQuery('');
    onOpenChange(open);
  };

  const go = (to: string) => {
    close(false);
    navigate(`/${realm}/${to}`);
    // Radix restores focus to whatever was focused before the palette opened -- on a list
    // screen that is a table-row button this navigation immediately unmounts, dropping
    // focus to <body>. A keyboard user would then be at the top of the document with the
    // whole sidebar ahead of them, which is the exact cost the skip link exists to remove.
    // `#main` is already tabIndex={-1} for the skip link, so it can take focus.
    requestAnimationFrame(() => document.getElementById('main')?.focus());
  };
  // Quick actions carry the whole address already.
  const goTo = (path: string) => go(path.replace(/^\/[a-z]+\//, ''));

  const item =
    'flex min-h-9 cursor-pointer items-center gap-2.5 rounded-md px-3 text-sm aria-selected:bg-selected pointer-coarse:min-h-11';

  return (
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-foreground/25" />
        <Dialog.Content
          aria-describedby={undefined}
          className="fixed top-[15vh] left-1/2 z-50 w-[min(36rem,calc(100vw-2rem))] -translate-x-1/2 overflow-hidden rounded-xl border border-border bg-surface shadow-card shadow-raise"
        >
          <Dialog.Title className="sr-only">Go to</Dialog.Title>
          <Command
            label="Go to"
            loop
            // People came from the server's own name search, which also matches what is not in the name shown; cmdk
            // must not filter them out again. Screens are still filtered here.
            filter={(value, search) => (value.startsWith('person:') ? 1 : defaultFilter(value, search))}
          >
            <Command.Input
              value={query}
              onValueChange={setQuery}
              placeholder={people.enabled ? 'Find a person or group, a screen, or a policy number' : 'Go to a screen, or paste a policy number'}
              className="h-11 w-full border-b border-border bg-transparent px-4 text-sm outline-none placeholder:text-muted-foreground"
            />
            <Command.List className="max-h-80 overflow-y-auto p-1 [&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pt-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-subtle-foreground">
              <Command.Empty className="px-3 py-6 text-center text-sm text-muted-foreground">
                {people.enabled
                  ? people.loading
                    ? 'Searching…'
                    : 'Nobody and no screen matches. Paste a policy number to open one.'
                  : 'No screen matches. This does not search names or records — paste a policy number to open one.'}
              </Command.Empty>
              {jump && (
                <Command.Group heading="Open">
                  <Command.Item
                    // No forceMount: the value contains the query, so cmdk scores it in
                    // anyway -- and a force-mounted item renders without the `role="option"`
                    // cmdk otherwise gives it, which is the difference between a listbox a
                    // screen reader can read and a div with a stray aria-selected on it.
                    value={`open ${query}`}
                    onSelect={() => go(jump)}
                    className={item}
                  >
                    <FileText className="size-4 shrink-0" aria-hidden />
                    Open policy {query.trim().toUpperCase()}
                  </Command.Item>
                </Command.Group>
              )}
              {people.found.length > 0 && (
                <Command.Group heading="People and groups">
                  {people.found.map((party) => {
                    const name = party.displayName ?? 'Unnamed client';
                    const Icon = party.partyType === 'INDIVIDUAL' ? User : Building2;
                    return [
                      <Command.Item
                        key={party.partyId}
                        value={`person:${party.partyId}`}
                        onSelect={() => go(`parties/${party.partyId}`)}
                        className={item}
                      >
                        <Icon className="size-4 shrink-0" aria-hidden />
                        <span className="min-w-0 truncate font-medium">{name}</span>
                      </Command.Item>,
                      ...clientActions({ partyId: party.partyId ?? '', kycStatus: party.kycStatus, partyType: party.partyType })
                        .slice(0, 3)
                        .map((action) => (
                          <Command.Item
                            key={`${party.partyId}-${action.label}`}
                            value={`person:${party.partyId}:${action.label}`}
                            onSelect={() => goTo(action.to)}
                            className={`${item} min-h-8 pl-9 text-muted-foreground aria-selected:text-foreground`}
                          >
                            {action.icon && <action.icon className="size-3.5 shrink-0" aria-hidden />}
                            {action.label}
                            <span className="sr-only">{`, for ${name}`}</span>
                          </Command.Item>
                        )),
                    ];
                  })}
                </Command.Group>
              )}
              {groups.map((group) => (
                <Command.Group key={group.label} heading={group.label}>
                  {group.items.map((entry) => (
                    <Command.Item
                      key={entry.to}
                      // The label alone. Adding the group name would make "clai" match
                      // Policies too, through "Policies & claims".
                      value={entry.label}
                      onSelect={() => go(entry.to)}
                      className={item}
                    >
                      <entry.icon className="size-4 shrink-0" aria-hidden />
                      {entry.label}
                    </Command.Item>
                  ))}
                </Command.Group>
              ))}
            </Command.List>
            {/* The list shortcuts live nowhere else on screen, so the one place a keyboard user
                already goes to move around says they exist. */}
            <p className="border-t border-border px-3 py-2 text-xs text-subtle-foreground">
              In a list: <kbd className="font-mono">j</kbd> / <kbd className="font-mono">k</kbd> to move,{' '}
              <kbd className="font-mono">Enter</kbd> to open, <kbd className="font-mono">/</kbd> to filter.
            </p>
          </Command>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Typed at least this much before anybody is looked up: one letter matches half the register. */
const MIN_QUERY = 2;

/**
 * The server's client search, debounced like PartyPicker's. A result is kept with the query it answered, so a stale
 * answer is never shown against what is typed now, and nothing is set synchronously in the effect.
 */
function usePeopleSearch(enabled: boolean, query: string) {
  const q = query.trim();
  const [result, setResult] = useState<{ q: string; found: PartyView[] }>({ q: '', found: [] });

  useEffect(() => {
    if (!enabled || q.length < MIN_QUERY) return;
    let live = true;
    const timer = setTimeout(() => {
      searchParties({ q, pageSize: 5 })
        .then((page) => live && setResult({ q, found: page.items ?? [] }))
        // A failed lookup leaves the screens to choose from; the palette is no place for an error panel.
        .catch(() => live && setResult({ q, found: [] }));
    }, 300);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [enabled, q]);

  const current = enabled && q.length >= MIN_QUERY && result.q === q;
  return { enabled, found: current ? result.found : [], loading: enabled && q.length >= MIN_QUERY && !current };
}
