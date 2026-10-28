import type { ReactNode } from 'react';
import { canAny } from '@/auth/access';
import { NoAccess } from '@/components/states';
import { SessionSplash } from '@/components/SessionSplash';
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
  // The same screen as signing in, one line further on: the person sees one continuous moment, not two pages.
  return <SessionSplash label="Opening your console" console="Staff" />;
}

/**
 * A staff page the person's permissions do not reach. The menu never offers it; this answers a typed or bookmarked
 * address.
 */
export function RequirePermission({ permissions, children }: { permissions: readonly string[]; children: ReactNode }) {
  if (canAny(...permissions)) return <>{children}</>;
  // The platform's own refusal: the same sentence a 403 shows, so it reads alike from the server or from here.
  return <NoAccess what="This page" who="staff whose role gives them access; ask an administrator if your work needs it" />;
}
