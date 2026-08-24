import * as Dialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * Right-hand slide-over. Radix Dialog underneath, so focus trapping, Escape,
 * scroll locking and aria wiring are handled rather than reimplemented.
 *
 * This is the "preview" half of drawer-previews-page-acts: it is dismissable by
 * clicking the backdrop, so it must never host a mutating action.
 */

// These ARE components -- the react-refresh rule just cannot see through a
// re-exported Radix primitive to tell that they are.
/* eslint-disable react-refresh/only-export-components */
export const Sheet = Dialog.Root;
export const SheetTrigger = Dialog.Trigger;
export const SheetClose = Dialog.Close;
/* eslint-enable react-refresh/only-export-components */

export function SheetContent({
  className,
  children,
  ...props
}: ComponentProps<typeof Dialog.Content>) {
  return (
    <Dialog.Portal>
      <Dialog.Overlay
        className={cn(
          'fixed inset-0 z-40 bg-black/20 dark:bg-black/50',
          'data-[state=open]:animate-in data-[state=closed]:animate-out',
        )}
      />
      <Dialog.Content
        className={cn(
          'fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col',
          'border-l border-border bg-surface shadow-xl',
          // A slide-over that cannot be reached on a laptop is not a slide-over.
          'sm:max-w-md',
          className,
        )}
        {...props}
      >
        {children}
      </Dialog.Content>
    </Dialog.Portal>
  );
}

export function SheetHeader({
  title,
  subtitle,
  action,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
      <div className="min-w-0">
        <Dialog.Title className="truncate text-base font-semibold">{title}</Dialog.Title>
        {subtitle ? (
          <Dialog.Description className="mt-0.5 truncate text-xs text-muted-foreground">
            {subtitle}
          </Dialog.Description>
        ) : (
          // Radix warns when a Dialog has no description; an explicit empty one is
          // quieter than suppressing the warning.
          <Dialog.Description className="sr-only">Detail preview</Dialog.Description>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {action}
        <Dialog.Close
          aria-label="Close"
          className="grid size-7 place-items-center rounded-md text-muted-foreground hover:bg-hover hover:text-foreground"
        >
          <X className="size-4" />
        </Dialog.Close>
      </div>
    </div>
  );
}

export function SheetBody({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('flex-1 overflow-y-auto px-5 py-4', className)}>{children}</div>;
}

export function SheetFooter({ children }: { children: ReactNode }) {
  return <div className="border-t border-border px-5 py-3">{children}</div>;
}
