import * as Popover from '@radix-ui/react-popover';
import { ChevronDown, type LucideIcon } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { POPOVER_MOTION } from '@/components/ui/motion';
import { cn } from '@/lib/cn';

export interface QuickAction {
  label: string;
  /** Where the act is done, with what is known already filled in. */
  to: string;
  icon?: LucideIcon;
}

/** Shown as buttons before the rest go under "More": enough for one role's everyday acts, never a toolbar. */
const SHOWN = 3;

/**
 * The acts this person may do on this record, at its top (2026-11-04, the user: whoever searches a person, a group or
 * a policy sees their actions straight away instead of clicking through tabs). The caller lists only what the viewer's
 * permissions allow; each opens the page or tab that already does it, with the record filled in.
 */
export function QuickActions({ actions, primary }: { actions: QuickAction[]; primary?: string }) {
  const [open, setOpen] = useState(false);
  if (actions.length === 0) return null;
  const shown = actions.slice(0, actions.length > SHOWN + 1 ? SHOWN : actions.length);
  const more = actions.slice(shown.length);

  return (
    <span className="flex flex-wrap items-center gap-2">
      {shown.map((a) => (
        <Button key={a.label} asChild size="sm" variant={a.label === primary ? 'primary' : 'outline'}>
          <Link to={a.to}>
            {a.icon && <a.icon aria-hidden />}
            {a.label}
          </Link>
        </Button>
      ))}
      {more.length > 0 && (
        <Popover.Root open={open} onOpenChange={setOpen}>
          <Popover.Trigger asChild>
            <Button size="sm" variant="outline" aria-label={`More actions (${more.length})`}>
              More
              <ChevronDown aria-hidden />
            </Button>
          </Popover.Trigger>
          <Popover.Portal>
            <Popover.Content align="end" sideOffset={6} collisionPadding={12}
              className={cn('z-50 min-w-48 rounded-md border border-border bg-surface p-1 shadow-lg', POPOVER_MOTION)}>
              <ul>
                {more.map((a) => (
                  <li key={a.label}>
                    <Link to={a.to} onClick={() => setOpen(false)}
                      className="flex items-center gap-2 rounded px-2.5 py-1.5 text-sm hover:bg-hover focus-visible:-outline-offset-2">
                      {a.icon && <a.icon className="size-4 text-muted-foreground" aria-hidden />}
                      {a.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </Popover.Content>
          </Popover.Portal>
        </Popover.Root>
      )}
    </span>
  );
}
