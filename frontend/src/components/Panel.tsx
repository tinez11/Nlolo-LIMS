import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * A titled section on a detail page.
 *
 * Previously copy-pasted into eight feature files, **byte-identical in all
 * eight** — verified by hashing each copy before extracting, so nothing was
 * flattened that was deliberately different. The same story as `Field` and
 * `FormField`: eight private copies meant a spacing or heading-level fix had to
 * be made eight times, or made inconsistently.
 *
 * `children` is deliberately NOT padded here. Panels wrap two different kinds of
 * content — a `<dl>` of `Field` rows that brings its own `px-4 pb-2`, and a
 * full-bleed `DataTable` whose rows must reach the panel's edges — so padding
 * belongs to the content, not the frame.
 *
 * The heading is an `<h2>` on every page, which is correct as long as the page
 * title stays the only `<h1>`: panels are siblings under it, never nested.
 *
 * `emphasis` marks THE panel this page was opened to use -- an assessment to
 * submit, a settlement to decide, a KYC decision to record. It buys the Title
 * tier (1rem/600, DESIGN.md's "heaviest in-panel headings", which until now
 * nothing on a detail page claimed) and a Rule-Strong edge, and it is what makes
 * the acting panel readable as the point of the page rather than the fourth of
 * eight identical boxes.
 *
 * Two deliberate limits on it:
 *
 * - **Achromatic only.** No status tint, ever. The Stamp Rule is that colour
 *   reports state and does nothing else -- it never marks hierarchy and never
 *   highlights an action -- so promotion is carried by size, weight and the border
 *   doing extra work.
 * - **One per page, and only where the page exists to perform that act.** With a
 *   stat row already on screen (`GroupSchemePage`) this would be a third size
 *   above body and break the Two-Peaks Rule, so those pages lead with the act and
 *   take no emphasis. Emphasising every panel promotes none of them.
 */
export function Panel({
  id,
  title,
  subtitle,
  emphasis = false,
  children,
}: {
  /**
   * An anchor target for `SectionNav`, on the pages that must stay one scroll. The scroll
   * margin below clears the sticky page bar and the section bar under it, so a jumped-to
   * panel lands below both rather than behind them.
   */
  id?: string;
  title: string;
  subtitle?: string;
  emphasis?: boolean;
  children: ReactNode;
}) {
  return (
    <section
      id={id}
      className={cn(
        'scroll-mt-[calc(var(--pagebar-h,0px)+3.5rem)] rounded-lg border bg-surface',
        emphasis ? 'border-border-strong' : 'border-border',
      )}
    >
      <div className="border-b border-border px-4 py-3">
        <h2 className={cn('font-semibold', emphasis ? 'text-base' : 'text-sm')}>{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
