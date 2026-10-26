import type { ReactNode } from 'react';
import { canAny } from '@/auth/access';
import { EmptyState, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { useAccessStore, useMyAccess } from '@/store/accessStore';

/**
 * Holds the staff console until the person's access has arrived (2026-10-26), so that every menu, page and button is
 * drawn for what they hold from the first frame -- never drawn and then taken away.
 */
export function StaffAccessGate({ children }: { children: ReactNode }) {
  const access = useMyAccess(true);
  const mine = useAccessStore((s) => s.mine);
  const reload = useAccessStore((s) => s.reload);
  if (access) return <>{children}</>;
  if (mine.status === 'error' && mine.error) {
    // RequireAuth's own failure screen, one step later: signed in, but what they may do could not be read.
    return (
      <div className="grid h-full place-items-center px-6">
        <div className="max-w-sm text-center">
          <p className="text-sm font-medium">Could not load your access</p>
          <p className="mt-1 text-xs text-muted-foreground">{mine.error.detail ?? mine.error.title}</p>
          <Button className="mt-4" onClick={() => void reload()}>Try again</Button>
        </div>
      </div>
    );
  }
  return <LoadingBlock label="Loading your access" />;
}

/**
 * A staff page the person's permissions do not reach. The menu never offers it; this answers a typed or bookmarked
 * address, and names the page's own permission so the person knows what to ask their administrator for.
 */
export function RequirePermission({ permissions, children }: { permissions: readonly string[]; children: ReactNode }) {
  if (canAny(...permissions)) return <>{children}</>;
  return (
    <EmptyState
      title="Not available to your role"
      description="This page needs a permission you do not hold. If your work needs it, ask an administrator to add it to your access."
    />
  );
}
