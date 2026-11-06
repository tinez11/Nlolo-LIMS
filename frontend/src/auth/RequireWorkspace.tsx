import type { ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router-dom';
import { readIdentity } from './claims';
import { workspacesOf, type Realm } from './realms';

/**
 * A workspace's pages, for accounts that hold it (2026-11-06). Anyone else goes back to "/", which offers what they do
 * hold. Convenience only: the backend refuses a request in a workspace the token does not hold either way.
 */
export function RequireWorkspace({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const held = workspacesOf(readIdentity(auth.user?.access_token).roles);
  return held.includes(realm) ? <>{children}</> : <Navigate to="/" replace />;
}
