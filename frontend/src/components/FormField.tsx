import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { FieldControl } from './fieldControl';

/**
 * One labelled control in a form, with its validation message beneath.
 *
 * Distinct from `Field`, which is the read-only label/value row in a detail
 * panel. This one wraps an editable control.
 *
 * Previously copy-pasted into twelve feature files, byte-identical in eleven of
 * them. Twelve private copies meant any spacing or accessibility fix had to be
 * made twelve times, or made inconsistently.
 *
 * ## Why this is no longer a `<label>` wrapping everything
 *
 * It used to be, and the error message lived *inside* it. That is comfortable to
 * write and wrong to use: everything inside a `<label>` folds into the control's
 * accessible name, so a rejected field announced "Sum assured Must be at least
 * 0.01" as its NAME — and kept announcing it, because a name is not a thing that
 * changes when the user fixes the problem. An error is a description, not a name.
 *
 * So the label is a real `<label htmlFor>`, the error is a sibling carrying its
 * own id, and the control is told about both through `useFieldControl`. Controls
 * from `components/ui/input` read that automatically, which is the whole point:
 * 98 inputs were never going to be annotated by hand, and any that were would
 * have drifted the first time somebody copied a neighbouring field.
 */
export function FormField({
  label,
  error,
  hint,
  required,
  className,
  children,
}: {
  label: string;
  // The explicit `| undefined` matters under exactOptionalPropertyTypes: every
  // call site passes `errors.x?.message`, which IS `string | undefined` -- a bare
  // `error?: string` would reject that assignment outright.
  error?: string | undefined;
  /**
   * A standing explanation, rendered under the control and wired into
   * `aria-describedby` — a limit the value must respect, or where a prefilled
   * figure came from.
   *
   * Distinct from `error` in the way that matters for assistive tech: an error
   * appears because something is wrong and goes away when it is fixed, while a
   * hint is true before anything is typed. Putting a limit in the error slot
   * would mean nobody learns it until they have already broken it, which is the
   * exact failure this prop was added to fix.
   */
  hint?: string | undefined;
  /**
   * The form will not submit without this field. Draws a `*` after the label and marks the
   * control `aria-required`; every field without it is optional, so no label says "(optional)".
   *
   * The `*` is a SIBLING of the `<label>`, never inside it, and hidden from assistive tech:
   * inside the label it would become part of the field's accessible name and its text, so
   * "Full name" would be announced "Full name star" and every test that finds the field by its
   * label would stop finding it. `aria-required` says the same thing to a screen reader, in
   * words.
   */
  required?: boolean | undefined;
  /** For a field that has to participate in its parent's layout — `flex-1`, a
   *  grid span. Not for restyling the field itself. */
  className?: string | undefined;
  children: ReactNode;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;

  return (
    <div className={cn('block', className)}>
      <div className="mb-1 flex items-baseline gap-0.5">
        <label htmlFor={id} className="text-xs text-label">
          {label}
        </label>
        {required && <RequiredMark />}
      </div>
      <FieldControl
        value={{
          id,
          invalid: Boolean(error),
          errorId: error ? errorId : undefined,
          hintId: hint ? hintId : undefined,
          required,
        }}
      >
        {children}
      </FieldControl>
      {hint && (
        <p id={hintId} className="mt-1 text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        // role="alert" so a rejection that appears on submit is announced rather
        // than just painted red for whoever can see it.
        <p id={errorId} role="alert" className="mt-1 text-xs text-status-danger-fg">
          {error}
        </p>
      )}
    </div>
  );
}

/** The `*` beside a required field's label. Decorative to assistive tech — the control's own
 *  `aria-required` carries the meaning — so it is hidden rather than read out as "star". */
export function RequiredMark() {
  return (
    <span aria-hidden="true" className="text-xs font-semibold text-status-danger-fg">
      *
    </span>
  );
}

/**
 * The key to the `*`, once per form, above its first field. Without it the mark is a
 * convention the reader is assumed to know; with it the form says what it means.
 */
export function RequiredNote({
  label = 'Required',
  className,
}: {
  /** The word for "required" in the page's language — the customer portal speaks English and Swahili. */
  label?: string | undefined;
  className?: string | undefined;
}) {
  return (
    <p className={cn('text-xs text-muted-foreground', className)}>
      <RequiredMark /> {label}
    </p>
  );
}
