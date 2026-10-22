/**
 * Bring a tab strip or step bar back into view after its content changed -- only when it has scrolled out of sight
 * under the sticky page bar (the user, 2026-10-22: a step or tab clicked while in plain view moved the page). The
 * element carries its own `scroll-mt-*` so it lands below the page bar, and `scrollIntoView` scrolls whichever box
 * actually scrolls -- the shell's main panel, not the window.
 */
export function revealIfHidden(el: HTMLElement | null) {
  if (!el) return;
  const bar = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--pagebar-h')) || 0;
  // jsdom implements no scrolling, hence the optional call.
  if (el.getBoundingClientRect().top < bar) el.scrollIntoView?.({ block: 'start' });
}
