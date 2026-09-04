import { Navigate, useLocation } from 'react-router-dom';

/**
 * A route that moved, without dropping what the old link was asking for.
 *
 * A bare `<Navigate to="...">` discards the query string, which is the whole payload of
 * the links this exists to serve: the client register's KYC badge linked to
 * `kyc?kycStatus=PENDING`, and a staff bookmark of a search is `kyc?q=...`. Landing
 * those on an unfiltered register would silently answer a different question than the
 * one asked -- and look like the filter had simply stopped working.
 *
 * `replace` so the dead path does not sit in history: pressing Back from the new route
 * would otherwise bounce through the redirect and straight back again.
 */
export function RedirectPreservingQuery({ to }: { to: string }) {
  const { search, hash } = useLocation();
  return <Navigate to={{ pathname: to, search, hash }} replace relative="path" />;
}
