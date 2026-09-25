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
        sm: 'h-8 px-2.5 text-[13px] pointer-coarse:h-11',
        md: 'h-9 px-3.5 pointer-coarse:h-11',
        icon: 'size-8 pointer-coarse:size-11',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'md' },
  },
);

export type ButtonProps = ComponentProps<'button'> &
  VariantProps<typeof button> & {
    asChild?: boolean;
    /**
     * The request this button started is in flight. Disables it -- the submit guard every
     * mutating form on the console relies on, now in one place -- and says so to assistive
     * tech, where a bare `disabled` only says "unavailable".
     */
    pending?: boolean;
  };

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
