import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2 } from 'lucide-react';
import type { ComponentProps } from 'react';
import { cn } from '@/lib/cn';

const button = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors disabled:pointer-events-none disabled:opacity-50 aria-busy:cursor-progress [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary: 'bg-accent text-accent-foreground hover:opacity-90',
        // Espresso's default button: a filled ground, no border. A button needs no drawn
        // boundary to be identified -- its label does that -- so the 3:1 rule that keeps a
        // border on every text field does not reach it.
        secondary: 'bg-control text-foreground hover:bg-control-hover',
        outline: 'border border-border-strong bg-surface hover:bg-hover',
        ghost: 'hover:bg-hover',
        // Reserved for genuinely destructive actions, so the colour keeps meaning.
        danger: 'bg-status-danger-fg text-background hover:opacity-90',
      },
      size: {
        // Desktop density stays; a finger gets 44px. `pointer-coarse` rather than a width
        // breakpoint, because a touch laptop at 1440px needs the target as much as a phone.
        sm: 'h-8 px-2.5 text-sm pointer-coarse:h-11',
        md: 'h-9 px-3.5 pointer-coarse:h-11',
        icon: 'size-8 pointer-coarse:size-11',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'md' },
  },
);

/**
 * A button, or -- with `asChild` -- whatever the caller rendered wearing its clothes.
 *
 * The two are genuinely different things, so the props are split rather than made optional
 * on one shape: an anchor has no disabled state and no busy state, and `Slot` takes exactly
 * one child, so a spinner cannot be injected beside it. Spelling that as a union means
 * `<Button asChild pending>` does not compile, instead of compiling and doing nothing.
 */
type ButtonBaseProps = ComponentProps<'button'> & VariantProps<typeof button>;

export type ButtonProps =
  | (ButtonBaseProps & {
      asChild?: false;
      /**
       * The request this button started is in flight. Disables it AND says so to assistive
       * tech, where a bare `disabled` reports only "unavailable", never "working".
       *
       * It also removes the label swap those forms hand-rolled ("Saving…" / "Save"): a
       * button whose NAME changes mid-flight is a different control to a screen reader and
       * to every test that locates it, and the spinner already says the same thing.
       *
       * Adopted on the policy surfaces; the remaining lanes move onto it as they are
       * rebuilt, so `disabled={x.status === 'loading'}` still appears elsewhere.
       */
      pending?: boolean;
    })
  | (ButtonBaseProps & { asChild: true; pending?: never });

export function Button({
  className,
  variant,
  size,
  asChild = false,
  pending = false,
  disabled,
  children,
  ...props
}: ButtonProps) {
  // Slot forwards to whatever the caller rendered -- an <a>, a router Link -- so neither
  // `disabled` nor a spinner child belongs on that branch: an anchor has no disabled state,
  // and injecting a child would break Slot's single-child requirement.
  if (asChild) {
    return (
      <Slot className={cn(button({ variant, size }), className)} {...props}>
        {children}
      </Slot>
    );
  }

  return (
    <button
      className={cn(button({ variant, size }), className)}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      {...props}
    >
      {pending && <Loader2 className="animate-spin" aria-hidden />}
      {children}
    </button>
  );
}
