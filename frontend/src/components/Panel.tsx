import type { ReactNode } from 'react';

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
 */
export function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
