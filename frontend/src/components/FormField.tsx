import { useId, type ReactNode } from 'react';
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
  children,
}: {
  label: string;
  // The explicit `| undefined` matters under exactOptionalPropertyTypes: every
  // call site passes `errors.x?.message`, which IS `string | undefined` -- a bare
  // `error?: string` would reject that assignment outright.
  error?: string | undefined;
  children: ReactNode;
}) {
  const id = useId();
  const errorId = `${id}-error`;

  return (
    <div className="block">
      <label htmlFor={id} className="mb-1 block text-xs font-medium text-muted-foreground">
        {label}
      </label>
      <FieldControl value={{ id, invalid: Boolean(error), errorId: error ? errorId : undefined }}>
        {children}
      </FieldControl>
      {error && (
        // role="alert" so a rejection that appears on submit is announced rather
        // than just painted red for whoever can see it.
        <p id={errorId} role="alert" className="mt-1 text-[11px] text-status-danger-fg">
          {error}
        </p>
      )}
    </div>
  );
}
