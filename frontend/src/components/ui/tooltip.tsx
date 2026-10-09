import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import type { ReactElement, ReactNode } from 'react';

/**
 * A short explanation that keyboard users reach too (2026-10-09, review B5).
 *
 * It replaces the `title` attribute wherever the title carried MEANING -- what a status means, why
 * a value is what it is. A title shows only to a mouse that rests, after the browser's own delay,
 * and never to a keyboard: a person tabbing through the claims list could not learn what a
 * dispatch status meant at all. This opens on hover and on focus, and Radix links it to the
 * trigger with `aria-describedby`, so a screen reader reads it as the trigger's description.
 *
 * `focusable` makes a trigger that is not already a control into a tab stop. Use it only where
 * the explanation is the point (a status), never on every name in a table -- tab stops are a cost.
 * Raw identifiers peeked at on hover (a party id behind a name) stay as plain `title`.
 *
 * Each instance carries its own provider, so a component renders the same inside the app and on
 * its own in a test. Styled as the Floating menu: the popover family's border and shadow.
 */
export function Tip({
  content,
  children,
  focusable = false,
}: {
  content: ReactNode;
  /** A single element; it becomes the trigger. */
  children: ReactElement;
  focusable?: boolean;
}) {
  return (
    <TooltipPrimitive.Provider delayDuration={300}>
      <TooltipPrimitive.Root>
        <TooltipPrimitive.Trigger asChild>
          {focusable ? (
            <span tabIndex={0} className="inline-flex rounded-sm">
              {children}
            </span>
          ) : (
            children
          )}
        </TooltipPrimitive.Trigger>
        <TooltipPrimitive.Portal>
          <TooltipPrimitive.Content
            sideOffset={6}
            collisionPadding={8}
            className="z-50 max-w-xs rounded-md border border-border bg-surface px-2.5 py-1.5 text-xs text-foreground shadow-lg"
          >
            {content}
          </TooltipPrimitive.Content>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  );
}
