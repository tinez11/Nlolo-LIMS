import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { can } from '@/auth/access';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { OneTimePassword } from '@/components/OneTimePassword';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { formatDate, formatInstant } from '@/lib/dates';
import { useAccessStore } from '@/store/accessStore';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectActing, selectUser, useIamStore } from '@/store/iamStore';
import { describeChange } from './changeText';
import { PendingChange } from './PendingChange';
import { PermissionPicker } from './PermissionPicker';
import { staffUserStatus } from './UsersPage';

/**
 * One staff member (2026-10-26): their roles, the single permissions they hold on top, and every change to them.
 * Nobody changes their own access -- the page is read-only on your own record, and the server refuses it regardless.
 * A change waiting for a second administrator is decided here, as a manual journal is approved on its own page.
 */
export function UserDetailPage() {
  const { userId = '' } = useParams();
  const me = useAccessStore((s) => s.mine.data?.userId);
  const detail = useIamStore(selectUser(userId));
  const loadUser = useIamStore((s) => s.loadUser);
  const roles = useIamStore((s) => s.roles);
  const loadRoles = useIamStore((s) => s.loadRoles);
  const catalogue = useIamStore((s) => s.catalogue);
  const loadCatalogue = useIamStore((s) => s.loadCatalogue);
  const acting = useIamStore(selectActing(userId));
  const saveAccess = useIamStore((s) => s.saveAccess);
  const setActive = useIamStore((s) => s.setActive);
  const resetPassword = useIamStore((s) => s.resetPassword);
  const resetActing = useIamStore((s) => s.resetActing);
  const issued = useIamStore((s) => s.issued[userId]);
  const clearIssued = useIamStore((s) => s.clearIssued);

  const [chosenRoles, setChosenRoles] = useState<Set<string> | null>(null);
  const [chosenPermissions, setChosenPermissions] = useState<Set<string> | null>(null);
  const [confirm, setConfirm] = useState<'deactivate' | 'reactivate' | 'password' | null>(null);

  useEffect(() => {
    void loadUser(userId);
    void loadRoles();
    void loadCatalogue();
    resetActing(userId);
  }, [userId, loadUser, loadRoles, loadCatalogue, resetActing]);

  // Shown once: leaving the page forgets the password, as the portal panel does.
  useEffect(() => () => clearIssued(userId), [userId, clearIssued]);

  const user = detail.data?.user;
  const self = user?.userId === me;
  const editable = can('users.manage') && !self && user?.active === true;
  const roleCodes = useMemo(() => chosenRoles ?? new Set(user?.roles.map((r) => r.code) ?? []), [chosenRoles, user]);
  const extra = chosenPermissions ?? new Set(user?.extraPermissions ?? []);
  const dirty = chosenRoles !== null || chosenPermissions !== null;

  // What the chosen roles already give: single permissions are only ever what they do not.
  const fromRoles = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const r of roles.data ?? []) {
      if (!roleCodes.has(r.code) || !r.active) continue;
      for (const p of r.permissions) map.set(p, [...(map.get(p) ?? []), r.name]);
    }
    return map;
  }, [roles.data, roleCodes]);

  if (isInitialLoad(detail)) return <LoadingBlock label="Loading user" />;
  if (!user) {
    return detail.error
      ? <div className="p-6"><ErrorPanel error={detail.error} onRetry={() => void loadUser(userId)} /></div>
      : null;
  }

  const pending = (detail.data?.history ?? []).filter((c) => c.status === 'PENDING');
  const outcome = acting.status === 'success' && acting.data !== true ? acting.data : null;

  async function save() {
    const singles = [...extra].filter((p) => !fromRoles.has(p));
    await saveAccess(userId, [...roleCodes], singles);
    if (useIamStore.getState().acting[userId]?.status === 'success') {
      setChosenRoles(null);
      setChosenPermissions(null);
    }
  }

  async function act() {
    const which = confirm;
    setConfirm(null);
    if (which === 'password') await resetPassword(userId);
    else if (which) await setActive(userId, which === 'reactivate');
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Users', to: '/staff/users' }]}
        title={user.displayName ?? user.username}
        description={user.roles[0]?.name ?? 'No role'}
        actions={<StatusBadge kind="staffUser" value={staffUserStatus(user)} />}
      />

      <DetailLayout record={renderRecord()}>
        {issued?.temporaryPassword && (
          <OneTimePassword password={issued.temporaryPassword}>
            Give it to them in person or by phone. They sign in as {user.username} and must choose their own password
            straight away. It is not kept anywhere: if it is lost, send a new password.
          </OneTimePassword>
        )}
        {issued?.delivery === 'EMAIL_LINK' && (
          <p role="status" className="text-sm">A link to set their password was emailed to {user.email}.</p>
        )}

        {pending.map((c) => <PendingChange key={c.changeId} change={c} subjectIsMe={self} />)}

        {self && (
          <p className="rounded-md border border-border bg-hover px-4 py-2.5 text-sm" role="status">
            This is you. Another administrator changes your access.
          </p>
        )}

        <Panel title="Roles" subtitle="Each role is a set of permissions. Most people hold one.">
          <div className="grid grid-cols-1 gap-1 px-4 pb-4 sm:grid-cols-2">
            {(roles.data ?? []).filter((r) => r.active || roleCodes.has(r.code)).map((r) => (
              <CheckboxField
                key={r.roleId}
                label={r.name}
                checked={roleCodes.has(r.code)}
                disabled={!editable}
                onChange={(e) => {
                  const next = new Set(roleCodes);
                  if (e.target.checked) next.add(r.code);
                  else next.delete(r.code);
                  setChosenRoles(next);
                }}
              />
            ))}
          </div>
        </Panel>

        <Panel title="Single permissions" subtitle="Added to what their roles give. There is no taking away: remove the role instead.">
          <div className="px-4 pb-4">
            <PermissionPicker catalogue={catalogue.data ?? []} selected={extra} fromRoles={fromRoles} disabled={!editable}
              onChange={setChosenPermissions} />
          </div>
        </Panel>

        {editable && (
          <div className="space-y-2">
            {acting.status === 'error' && acting.error && <InlineError error={acting.error} />}
            {outcome && (
              <p role="status" className="text-sm">
                {outcome.applied
                  ? 'Saved. It applies from their next action.'
                  : 'Saved for approval: it gives Administration, so a second administrator approves it here.'}
              </p>
            )}
            <div className="flex gap-2">
              <Button variant="primary" pending={acting.status === 'loading'} disabled={!dirty} onClick={() => void save()}>
                Save access
              </Button>
              {dirty && (
                <Button variant="ghost" onClick={() => { setChosenRoles(null); setChosenPermissions(null); }}>Discard</Button>
              )}
            </div>
          </div>
        )}

        <Panel title="History" subtitle="Every change to their access, newest first">
          <ul className="divide-y divide-border">
            {(detail.data?.history ?? []).map((c) => (
              <li key={c.changeId} className="flex items-start gap-3 px-4 py-2.5">
                <div className="min-w-0 flex-1">
                  <p className="text-sm">{describeChange(c)}</p>
                  <p className="text-xs text-muted-foreground">
                    {formatInstant(c.requestedAt)} · {c.requestedByName ?? 'the platform'}
                    {c.decidedByName && ` · decided by ${c.decidedByName}`}
                  </p>
                </div>
                <StatusBadge kind="accessChange" value={c.status} />
              </li>
            ))}
          </ul>
        </Panel>

        {can('users.manage') && !self && (
          <Panel title="Lifecycle">
            <div className="space-y-2 px-4 pb-4">
              {confirm ? (
                <ConfirmAct
                  heading={confirm === 'password' ? 'Send a new password?' : confirm === 'deactivate'
                    ? `Deactivate ${user.displayName ?? user.username}?` : `Reactivate ${user.displayName ?? user.username}?`}
                  consequence={confirm === 'password'
                    ? 'Their current password stops working. With an email they are sent a link; without one you are given a one-time password to hand over.'
                    : confirm === 'deactivate'
                      ? 'Their login closes and every session they have open ends now. They keep their roles, unused.'
                      : 'Their login opens again with the roles they had.'}
                  reversal={confirm === 'deactivate' ? 'Reactivate them here.' : 'Nothing to undo.'}
                  confirmLabel={confirm === 'password' ? 'Send new password' : confirm === 'deactivate' ? 'Deactivate' : 'Reactivate'}
                  tone={confirm === 'deactivate' ? 'danger' : 'primary'}
                  onConfirm={() => void act()}
                  onCancel={() => setConfirm(null)}
                />
              ) : (
                <div className="flex flex-wrap gap-2">
                  {user.active && <Button size="sm" onClick={() => setConfirm('password')}>Send a new password</Button>}
                  <Button size="sm" onClick={() => setConfirm(user.active ? 'deactivate' : 'reactivate')}>
                    {user.active ? 'Deactivate' : 'Reactivate'}
                  </Button>
                </div>
              )}
            </div>
          </Panel>
        )}
      </DetailLayout>
    </>
  );

  function renderRecord() {
    if (!user) return null;
    return (
      <Panel title="User">
        <dl className="px-4 pb-2">
          <Field label="Signs in as" value={user.username} />
          <Field label="Email" value={user.email ?? '—'} />
          <Field label="Phone" value={user.phone ?? '—'} />
          <Field label="Home branch" value={user.homeBranch ?? '—'} />
          <Field label="Last in" value={user.lastSeenAt ? formatDate(user.lastSeenAt) : '—'} />
          <Field label="Roles" value={user.roles.length ? user.roles.map((r) => r.name).join(', ') : '—'} />
        </dl>
      </Panel>
    );
  }
}
