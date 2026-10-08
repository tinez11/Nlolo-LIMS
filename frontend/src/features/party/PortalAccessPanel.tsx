import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { getPortalAccess, invitePortal, resendPortalInvite, revokePortal, type PortalAccessView } from '@/api/portal';
import { canInviteToPortal, readIdentity } from '@/auth/claims';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';

const STATUS_LABEL: Record<string, string> = {
  NOT_INVITED: 'Not invited',
  INVITED: 'Invited — not signed in yet',
  ACTIVE: 'Active — has signed in',
  REVOKED: 'Revoked',
};

/**
 * A client's customer-portal access on their record (2026-10-08, the customer portal design step 1). Customer
 * service and admins invite the client: the platform creates the login itself, so nobody types the client's id into
 * Keycloak. With an email the client gets a set-your-password link; without one, a one-time password is shown here
 * ONCE -- read it to the client, it is not kept anywhere and cannot be shown again (re-send makes a new one).
 */
export function PortalAccessPanel({ partyId }: { partyId: string }) {
  const canInvite = canInviteToPortal(readIdentity(useAuth().user?.access_token));
  const [access, setAccess] = useState<PortalAccessView | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const [password, setPassword] = useState<string | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  useEffect(() => {
    let live = true;
    getPortalAccess(partyId).then(
      (a) => { if (live) { setAccess(a); setLoadError(null); } },
      (e: unknown) => { if (live) setLoadError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [partyId, reload]);

  async function act(work: () => Promise<{ temporaryPassword?: string | null } | PortalAccessView>) {
    setPending(true);
    setError(null);
    setPassword(null);
    try {
      const result = await work();
      if ('temporaryPassword' in result) setPassword(result.temporaryPassword ?? null);
      setConfirmRevoke(false);
      setReload((n) => n + 1);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  if (loadError) return <div className="p-4"><ErrorPanel error={loadError} onRetry={() => setReload((n) => n + 1)} /></div>;
  if (!access) return <LoadingBlock label="Loading portal access" />;

  const open = access.status === 'INVITED' || access.status === 'ACTIVE';
  return (
    <div className="space-y-3 p-4">
      <dl>
        <Field label="Status" value={STATUS_LABEL[access.status] ?? access.status} />
        {access.username && <Field label="Signs in as" value={access.username} />}
        {access.invitedAt && (
          <Field label="Invited" value={`${formatInstant(access.invitedAt)}${access.invitedBy ? ` by ${access.invitedBy}` : ''}`} />
        )}
        {access.delivery && (
          <Field label="First password" value={access.delivery === 'EMAIL_LINK' ? 'Emailed as a set-your-password link' : 'One-time password given by staff'} />
        )}
        {access.activatedAt && <Field label="First signed in" value={formatInstant(access.activatedAt)} />}
        {access.revokedAt && (
          <Field label="Revoked" value={`${formatInstant(access.revokedAt)}${access.revokedBy ? ` by ${access.revokedBy}` : ''}`} />
        )}
      </dl>

      {password && (
        <div role="status" className="rounded-md border border-status-warning-fg/40 bg-status-warning-bg p-3 text-sm">
          <p className="font-medium">One-time password — shown once</p>
          <p className="mt-1 font-mono text-base tracking-wider">{password}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Give it to the client in person or by phone. They sign in as {access.username} and must choose their own
            password straight away. It is not kept anywhere: if it is lost, re-send to make a new one.
          </p>
        </div>
      )}

      {error && <InlineError error={error} />}

      {canInvite && (
        <div className="flex flex-wrap gap-2">
          {!open && (
            <Button size="sm" variant="primary" pending={pending} onClick={() => void act(() => invitePortal(partyId))}>
              {access.status === 'REVOKED' ? 'Invite again' : 'Invite to portal'}
            </Button>
          )}
          {open && (
            <Button size="sm" variant="ghost" pending={pending} onClick={() => void act(() => resendPortalInvite(partyId))}>
              {access.delivery === 'EMAIL_LINK' ? 'Re-send the link' : 'New one-time password'}
            </Button>
          )}
          {open && !confirmRevoke && (
            <Button size="sm" variant="ghost" onClick={() => setConfirmRevoke(true)}>Revoke access</Button>
          )}
          {open && confirmRevoke && (
            <>
              <Button size="sm" variant="danger" pending={pending} onClick={() => void act(() => revokePortal(partyId))}>
                Confirm revoke
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setConfirmRevoke(false)}>Cancel</Button>
            </>
          )}
        </div>
      )}
      {!canInvite && access.status === 'NOT_INVITED' && (
        <p className="text-xs text-muted-foreground">Customer service or an administrator can invite this client.</p>
      )}
    </div>
  );
}
