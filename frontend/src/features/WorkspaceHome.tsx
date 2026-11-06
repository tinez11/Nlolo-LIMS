import { ArrowRight } from 'lucide-react';
import { useAuth } from 'react-oidc-context';
import { Link, Navigate } from 'react-router-dom';
import { readIdentity } from '@/auth/claims';
import { REALM_CONFIG } from '@/auth/realms';
import { availableWorkspaces } from '@/auth/workspaces';
import { Button } from '@/components/ui/button';

/**
 * Where everyone lands after signing in (2026-11-06, universal login; replaces the realm picker). One workspace opens
 * at once; several are offered ("Continue as …"); none is said plainly rather than shown as an empty console.
 */
export function WorkspaceHome() {
  const auth = useAuth();
  const workspaces = availableWorkspaces(readIdentity(auth.user?.access_token).roles);

  if (workspaces.length === 1) return <Navigate to={`/${REALM_CONFIG[workspaces[0]].slug}`} replace />;

  return (
    <div className="grid min-h-full place-items-center px-6 py-16">
      <div className="w-full max-w-md">
        <h1 className="text-headline">Life Platform</h1>
        {workspaces.length === 0 ? (
          <>
            <p className="mt-1 text-sm text-muted-foreground">
              This account has no access yet. Contact the insurer.
            </p>
            <Button className="mt-6" variant="outline" onClick={() => void auth.signoutRedirect().catch(() => undefined)}>
              Sign out
            </Button>
          </>
        ) : (
          <>
            <p className="mt-1 text-sm text-muted-foreground">Choose how you are continuing.</p>
            <ul className="mt-6 space-y-2">
              {workspaces.map((realm) => {
                const config = REALM_CONFIG[realm];
                return (
                  <li key={realm}>
                    <Link
                      to={`/${config.slug}`}
                      className="flex items-center justify-between gap-4 rounded-xl border border-border bg-surface shadow-card px-4 py-3 transition-colors hover:border-border-strong hover:bg-hover"
                    >
                      <span className="min-w-0">
                        <span className="block text-sm font-medium">Continue as {config.label}</span>
                        <span className="block text-xs text-muted-foreground">{config.description}</span>
                      </span>
                      <ArrowRight className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    </Link>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}
