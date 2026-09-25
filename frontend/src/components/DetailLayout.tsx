import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { shouldPin } from './railPin';

/**
 * The two-column body of every detail page: the work, and the record it is about.
 *
 * Extracted rather than copy-pasted, because the grid it replaces was already
 * written out longhand in eight feature files -- the same story as `Panel`,
 * `Field` and `FormField` before it, and the reason the sticky behaviour below
 * could not have been added eight times consistently.
 *
 * WHAT GOES WHERE is the whole point of the component, and it is not a preference:
 *
 * - **`record` is facts, and it is pinned WHEN IT FITS.** Who, what, how much,
 *   as-of-when. It sticks while the main column scrolls, because an assessor writing
 *   findings needs the claimant's name on screen the entire time -- the previous layout
 *   let it scroll away and the answer was a second browser tab. A rail taller than the
 *   space below the page bar is not pinned at all and scrolls with the page: it used to
 *   be pinned with its own `overflow-y`, which put a third scrollbar on the screen beside
 *   the sidebar's and the page's, and the credit-life scheme record had all three at once.
 *   See `railPin.ts`.
 * - **`children` is the work**, ordered act-first where the page exists to perform
 *   an act, then the registers that evidence it.
 * - **No mutating action goes in `record`.** Not because it would not fit, but
 *   because PRODUCT.md principle 4 is that acting is deliberate: a decision belongs
 *   in the column a person is reading, at full width, not in a 320px margin they
 *   have learned to treat as a summary.
 *
 * Below `lg` the grid collapses and the record falls after the work. That is the
 * right order for a narrow viewport -- the person came to do the job, and the
 * identifying name and status are already in `PageHeader` above -- and it needs no
 * `order` utilities, so DOM order still matches visual order for keyboard and
 * assistive tech at every width.
 */
export function DetailLayout({ record, children }: { record?: ReactNode; children: ReactNode }) {
  const rail = useRef<HTMLDivElement>(null);
  const [pinned, setPinned] = useState(false);

  useEffect(() => {
    const node = rail.current;
    if (!node) return;
    const measure = () => {
      const offset =
        Number.parseFloat(
          getComputedStyle(document.documentElement).getPropertyValue('--pagebar-h'),
        ) || 0;
      setPinned(shouldPin(node.offsetHeight, window.innerHeight, offset));
    };
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    window.addEventListener('resize', measure);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, []);

  return (
    <div className="grid gap-5 px-6 pt-5 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="min-w-0 space-y-5">{children}</div>

      {record && (
        /* `self-start` is what makes `sticky` mean anything in a grid: without it the
           column stretches to the row's full height and has no slack to stick within. */
        <div
          ref={rail}
          className={cn(
            'space-y-5 lg:self-start',
            pinned && 'lg:sticky lg:top-[calc(var(--pagebar-h,0px)+1rem)]',
          )}
        >
          {record}
        </div>
      )}
    </div>
  );
}
