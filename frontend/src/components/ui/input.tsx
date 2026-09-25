import { cva, type VariantProps } from 'class-variance-authority';
import { type ComponentProps } from 'react';
import { cn } from '@/lib/cn';
import { useFieldControl } from '../fieldControl';

/**
 * The text inputs, selects and textareas of the console.
 *
 * These existed as **98 hand-written class strings in 24 variants** before this
 * file — the fourth recurrence of the copy-paste-a-component habit that
 * `FormField` and `Panel` already cured twice. Two of those variants were doing
 * the work of a size scale (`h-9`/`text-sm` and `h-8`/`text-xs`), so that is
 * what they become here: `md` and `sm`, matching `Button`'s own scale so a field
 * and the button beside it line up.
 *
 * **The real reason to extract them is accessibility, not tidiness.** Not one of
 * those 98 inputs carried `aria-invalid` or `aria-describedby`, so a rejected
 * field announced nothing to a screen reader beyond its label. That fix has to
 * be made in one place or it does not get made: it is not 98 edits anybody would
 * ever finish. Wrapped in a {@link FormField}, these controls now pick up their
 * id, their invalid state and their error's id automatically, through context —
 * so a call site gets it right by doing nothing.
 *
 * `className` still passes through, because the genuinely per-field bits are
 * real: a currency box is `w-20 uppercase`, an amount column is `text-right`, an
 * id field is `font-mono`. Those are not variants, they are one-offs, and
 * inventing a prop for each would be a worse API than a class.
 */

const field = cva(
  // Espresso's filled ground WITH this console's 3:1 --input border. Espresso drops the
  // border; its #f3f3f3 ground is 1.11:1 against paper and cannot identify a field alone
  // (WCAG 1.4.11). The field turns to paper on keyboard focus so the ink ring reads on it.
  //
  // focus-visible, not focus: a mouse click on an input should not draw the
  // same ring a keyboard tab does. The ring uses --border-strong rather than
  // --input, because --input at ~1.27:1 against the surface fails 1.4.11's 3:1
  // for a UI component boundary and cannot carry a focus indicator either.
  'w-full rounded-md border border-input bg-control transition-colors focus-visible:bg-surface ' +
    'focus-visible:border-border-strong focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ' +
    'disabled:cursor-not-allowed disabled:opacity-50 ' +
    // Tailwind's built-in aria-invalid variant, so the red border follows the
    // SAME attribute a screen reader reads. Styling an `.error` class instead is
    // how a field ends up looking wrong to one user and sounding fine to another.
    'aria-invalid:border-status-danger-fg aria-invalid:focus-visible:ring-status-danger-fg/30',
  {
    variants: {
      inputSize: {
        md: 'h-9 px-2.5 text-sm pointer-coarse:h-11',
        sm: 'h-8 px-2 text-xs pointer-coarse:h-11',
      },
    },
    defaultVariants: { inputSize: 'md' },
  },
);

type FieldVariants = VariantProps<typeof field>;

export type InputProps = Omit<ComponentProps<'input'>, 'size'> & FieldVariants;

export function Input({ className, inputSize, ...props }: InputProps) {
  const aria = useFieldControl(props.id);
  return <input {...aria} className={cn(field({ inputSize }), className)} {...props} />;
}

export type SelectProps = ComponentProps<'select'> & FieldVariants;

export function Select({ className, inputSize, ...props }: SelectProps) {
  const aria = useFieldControl(props.id);
  return <select {...aria} className={cn(field({ inputSize }), className)} {...props} />;
}

export type TextareaProps = ComponentProps<'textarea'> & FieldVariants;

/** Height comes from the caller (`min-h-16`, `min-h-20`): a textarea's size is its content's. */
export function Textarea({ className, inputSize, ...props }: TextareaProps) {
  const aria = useFieldControl(props.id);
  return (
    <textarea
      {...aria}
      // `pointer-coarse:h-auto` as well as `h-auto`: tailwind-merge only resolves classes
      // that carry the SAME modifiers, so a bare `h-auto` does not cancel the size
      // variant's `pointer-coarse:h-11` and every textarea would be pinned to 44px on a
      // touch device -- invisible today only because every call site passes a `min-h-*`.
      className={cn(field({ inputSize }), 'h-auto py-2 pointer-coarse:h-auto', className)}
      {...props}
    />
  );
}
