/**
 * Whether the record rail can be pinned beside the work without hiding any of it.
 *
 * Space below the page bar, less a 2rem margin top and bottom. A rail that fits is pinned,
 * so an assessor keeps the claimant in view while writing; one that does not scrolls with
 * the page, instead of growing the nested scrollbar it used to.
 */
export function shouldPin(railHeight: number, viewportHeight: number, offset: number): boolean {
  if (railHeight <= 0) return false;
  return railHeight <= viewportHeight - offset - 32;
}
