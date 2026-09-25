import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type CheckboxProps = Omit<ComponentProps<'input'>, 'type'>;

/**
 * A native checkbox, themed rather than replaced.
 *
 * Native on purpose: it keeps keyboard, form and react-hook-form behaviour for free, and
 * `accent-color` paints the checked state in ink in both themes. The three it replaces were
 * bare OS defaults at ~13px -- one of them the fraud flag on a claim assessment.
 */
export function Checkbox({ className, ...props }: CheckboxProps) {
  return (
    <input
      type="checkbox"
      className={cn(
        'size-4 shrink-0 cursor-pointer rounded-sm accent-[var(--color-accent)] disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  );
}

/** A checkbox and the words that name it, as one 44px target on touch. */
export function CheckboxField({
  label,
  className,
  ...props
}: CheckboxProps & { label: ReactNode }) {
  return (
    <label
      className={cn(
        'inline-flex min-h-8 cursor-pointer items-center gap-2 text-sm text-foreground pointer-coarse:min-h-11',
        className,
      )}
    >
      <Checkbox {...props} />
      {label}
    </label>
  );
}
