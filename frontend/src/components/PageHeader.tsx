import { ChevronRight } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

/**
 * Page header used by every screen, so titles and actions align across the console.
 *
 * Sticky, because the actions live here: on a detail page with eight panels the button that
 * issues, suspends or settles scrolled out of reach, and the way back was a scroll to the
 * top. It publishes its own height as `--pagebar-h` so everything else that sticks -- the
 * record tabs, the claim section bar, the record rail -- stops UNDER it rather than behind
 * it, and keeps doing so when a description wraps and the bar grows.
 *
 * Lives here rather than in `AppShell` for an import-cycle reason: `AppShell`
 * builds the sidebar from the screen manifest (`@/screens`), and the manifest
 * imports every page component. If pages kept importing their header from
 * `AppShell`, that would close the cycle pages -> AppShell -> screens -> pages,
 * and the manifest constructs its elements at module scope -- so a badly-resolved
 * cycle would surface as an undefined component at first render rather than as a
 * build error.
 */
export function PageHeader({
  title,
  description,
  count,
  actions,
  breadcrumb,
  status,
}: {
  title: ReactNode;
  description?: ReactNode;
  /**
   * How many rows are behind this screen right now, as a `<CountLine>`.
   *
   * A register's total used to arrive as a stat card: one 282px card in a
   * four-column grid, holding 130px of the first screenful to say a number the
   * pager reprints under the table. A count is a fact ABOUT the screen, so it
   * belongs in the screen's own byline -- close to the title it qualifies, and
   * costing two lines of muted text rather than a row of furniture.
   *
   * Below the description rather than above it: the description says what the
   * register is, which does not change, and the count is the part that moves when
   * a filter does -- so it sits nearest the table it counts.
   */
  count?: ReactNode;
  actions?: ReactNode;
  /** Where this page sits, oldest first. The current page is the title, never a crumb. */
  breadcrumb?: { label: string; to: string }[];
  /** A StatusBadge beside the title -- Espresso's "Active" / "Not saved" pill. */
  status?: ReactNode;
}) {
  const bar = useRef<HTMLDivElement>(null);
  // Read once at mount rather than assumed false: `main` is one element across every route, so a
  // page can mount already scrolled.
  const [scrolled, setScrolled] = useState(
    () => (document.getElementById('main')?.scrollTop ?? 0) > 0,
  );

  useEffect(() => {
    const main = document.getElementById('main');
    if (!main) return;
    const onScroll = () => setScrolled(main.scrollTop > 0);
    main.addEventListener('scroll', onScroll, { passive: true });
    return () => main.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const node = bar.current;
    if (!node) return;
    const publish = () =>
      document.documentElement.style.setProperty('--pagebar-h', `${node.offsetHeight}px`);
    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(node);
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty('--pagebar-h');
    };
  }, []);

  return (
    <div
      ref={bar}
      // Marked so `DetailLayout` can observe this element's height directly: a CSS custom
      // property is only reactive to CSS, and the rail has to re-measure when the bar grows.
      data-pagebar
      // A rule only once content is passing under the bar (2026-10-09). At rest the page title sits
      // on the page, and the space beneath it does the separating. Kept on a record with sticky
      // tabs too: they span the work column only, and without this the record rail slid under the
      // bar with no edge at all -- its text sliced off mid-line beneath the title.
      data-scrolled={scrolled || undefined}
      className="sticky top-0 z-20 flex flex-wrap items-start justify-between gap-4 border-b border-transparent bg-background px-6 pt-4 pb-3 transition-colors data-[scrolled]:border-border"
    >
      <div className="min-w-0">
        {breadcrumb && breadcrumb.length > 0 && (
          <nav aria-label="Breadcrumb" className="mb-1">
            <ol className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
              {breadcrumb.map((crumb) => (
                <li key={crumb.to} className="flex items-center gap-1">
                  <Link to={crumb.to} className="rounded-sm hover:text-foreground hover:underline">
                    {crumb.label}
                  </Link>
                  <ChevronRight className="size-3" aria-hidden />
                </li>
              ))}
            </ol>
          </nav>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          {status}
        </div>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
        {count && <p className="mt-1 text-xs text-muted-foreground">{count}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
