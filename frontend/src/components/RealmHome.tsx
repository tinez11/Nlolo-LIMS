import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router-dom';
import { readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { homeFor } from '@/screens';

/**
 * The realm index. It renders inside `RequireAuth`, so the token is already in hand and the
 * landing screen can depend on the roles in it -- which is the whole point: a redirect
 * declared statically in the manifest cannot know who is signing in.
 */
export function RealmHome({ realm }: { realm: Realm }) {
  const auth = useAuth();
  const home = homeFor(realm, readIdentity(auth.user?.access_token));
  return home ? <Navigate to={home} replace /> : null;
}
