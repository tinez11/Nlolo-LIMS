/**
 * How a floating layer arrives and leaves (2026-10-09, review B6).
 *
 * A popover used to appear in place, at full size, with no motion at all; and when it did move
 * (the slide-over), it moved from its own centre. Floating menus now grow out of the control that
 * opened them -- Radix publishes the trigger-facing corner as a transform origin -- over 150ms,
 * fading in at 95% scale, and leave the same way in reverse, so the path out mirrors the path in.
 *
 * Small on purpose: DESIGN.md's motion vocabulary is "almost none", and this is the one gesture
 * that tells a person where a menu came from. Under prefers-reduced-motion, index.css removes the
 * scale and leaves only the fade.
 */
export const POPOVER_MOTION =
  'origin-[var(--radix-popover-content-transform-origin)] data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95 data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95';

/**
 * The same for a tooltip, whose open states are `delayed-open` and `instant-open` rather than
 * `open` -- so it animates in on mount, which is when it opens.
 */
export const TOOLTIP_MOTION =
  'origin-[var(--radix-tooltip-content-transform-origin)] animate-in fade-in-0 zoom-in-95 data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95';
