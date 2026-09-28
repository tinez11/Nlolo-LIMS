/**
 * Which section the reader is looking at: the last one whose top has passed under the
 * sticky bars.
 *
 * A pure function rather than an IntersectionObserver callback, for the same reason
 * `railPin.ts` is one -- the interesting behaviour is a rule about numbers, and a rule about
 * numbers can be tested without a layout engine. jsdom reports every offset as 0, so an
 * observer-based version would have no test at all.
 *
 * @param tops each section's document-space top, in document order
 * @param scrollTop the window's current scroll position
 * @param offset how much is covered by sticky furniture -- the page bar plus the section bar
 * @param atBottom whether the page is scrolled as far as it goes. A last section shorter than
 *   the viewport never reaches the top of it, so without this it could never be marked and the
 *   bar would say "Evidence" while the reader looked at Payment.
 */
export function activeSection(
  tops: { id: string; top: number }[],
  scrollTop: number,
  offset: number,
  atBottom: boolean,
): string | null {
  if (tops.length === 0) return null;
  if (atBottom) return tops[tops.length - 1]!.id;

  let active = tops[0]!.id;
  for (const section of tops) {
    // +1 absorbs the sub-pixel difference between a fractional bounding rect and an integer
    // scroll position, which otherwise leaves a section one pixel short of its own anchor.
    if (section.top - offset <= scrollTop + 1) active = section.id;
  }
  return active;
}
