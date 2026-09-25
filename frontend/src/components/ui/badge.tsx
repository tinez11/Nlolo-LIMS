import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * A neutral tag -- "Group scheme", "Evidence · POL-…", a count beside a nav item.
 *
 * Deliberately colourless and deliberately not StatusBadge: a tag says what KIND of thing a
 * row is, never what STATE it is in, and the Stamp Rule keeps colour for state. Same shape
 * and size as StatusBadge so the two sit on one baseline in a row.
 */
export function Badge({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full bg-control px-2 py-0.5 text-xs font-medium whitespace-nowrap text-muted-foreground',
        className,
      )}
    >
      {children}
    </span>
  );
}
