import type { ReactNode } from 'react';

/**
 * One labelled control in a form, with its validation message beneath.
 *
 * Distinct from `Field`, which is the read-only label/value row in a detail
 * panel. This one wraps an editable control, and the wrapper is a `<label>` so
 * the caption is implicitly associated with whatever single control sits inside
 * -- no `htmlFor`/`id` pair to keep in step.
 *
 * Previously copy-pasted into twelve feature files, byte-identical in eleven of
 * them. Twelve private copies meant any spacing or accessibility fix had to be
 * made twelve times, or made inconsistently.
 */
export function FormField({
  label,
  error,
  children,
}: {
  label: string;
  // The explicit `| undefined` matters under exactOptionalPropertyTypes: every
  // call site passes `errors.x?.message`, which IS `string | undefined` -- a bare
  // `error?: string` would reject that assignment outright.
  error?: string | undefined;
  children: ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-muted-foreground">{label}</span>
      {children}
      {error && <p className="mt-1 text-[11px] text-status-danger-fg">{error}</p>}
    </label>
  );
}
