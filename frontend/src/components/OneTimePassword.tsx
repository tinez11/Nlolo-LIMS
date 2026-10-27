import type { ReactNode } from 'react';

/**
 * A one-time password, shown once (moved here 2026-10-26 from the portal invite panel, so a staff login made on the
 * Users screen is handed over exactly as a portal login is). Nothing keeps it: if it is lost, a new one is made.
 */
export function OneTimePassword({ password, notice, children }: {
  password: string;
  /** Why it is being shown at all, when something else should have delivered it. */
  notice?: string | null | undefined;
  /** Who to give it to and what happens next. */
  children: ReactNode;
}) {
  return (
    <div role="status" className="rounded-md border border-status-warning-fg/40 bg-status-warning-bg p-3 text-sm">
      <p className="font-medium">One-time password — shown once</p>
      <p className="mt-1 font-mono text-base tracking-wider">{password}</p>
      {notice && <p className="mt-1">{notice}</p>}
      <p className="mt-1 text-xs text-muted-foreground">{children}</p>
    </div>
  );
}
