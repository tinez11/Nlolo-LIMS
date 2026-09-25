import * as Dialog from '@radix-ui/react-dialog';
import { Command } from 'cmdk';
import { FileText } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Realm } from '@/auth/realms';
import type { NavItem } from '@/screens';
import { resolveJump } from './jump';

/**
 * Ctrl+K: go to any screen this person can reach, or open a policy by its number.
 *
 * Built from the same `navFor` result as the sidebar, so the palette can never offer a
 * screen the role cannot see -- one source, not a second list that drifts.
 *
 * It does NOT search records, and says so rather than looking like it failed to find
 * anything: there is no search endpoint on this platform, and a box that matched only
 * screen names while wearing a magnifying glass would read as a broken record search.
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

  const item =
    'flex min-h-9 cursor-pointer items-center gap-2.5 rounded-md px-3 text-sm aria-selected:bg-selected pointer-coarse:min-h-11';

  return (
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-foreground/25" />
        <Dialog.Content
          aria-describedby={undefined}
          className="fixed top-[15vh] left-1/2 z-50 w-[min(36rem,calc(100vw-2rem))] -translate-x-1/2 overflow-hidden rounded-lg border border-border bg-surface shadow-raise"
        >
          <Dialog.Title className="sr-only">Go to</Dialog.Title>
          <Command label="Go to" loop>
            <Command.Input
              value={query}
              onValueChange={setQuery}
              placeholder="Go to a screen, or paste a policy number"
              className="h-11 w-full border-b border-border bg-transparent px-4 text-sm outline-none placeholder:text-muted-foreground"
            />
            <Command.List className="max-h-80 overflow-y-auto p-1 [&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pt-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-subtle-foreground">
              <Command.Empty className="px-3 py-6 text-center text-sm text-muted-foreground">
                No screen matches. This does not search names or records — paste a policy
                number to open one.
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
          </Command>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
