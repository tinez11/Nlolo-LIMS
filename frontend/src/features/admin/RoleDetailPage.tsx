import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { can } from '@/auth/access';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Textarea } from '@/components/ui/input';
import { useAccessStore } from '@/store/accessStore';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectActing, useIamStore } from '@/store/iamStore';
import { PendingChange } from './PendingChange';
import { PermissionPicker } from './PermissionPicker';
import { roleStatus } from './RolesPage';

/**
 * One role and what it carries (2026-10-26). A role you hold is read-only -- another administrator changes it -- and
 * a change that gives its holders Administration waits for a second administrator.
 */
export function RoleDetailPage() {
  const { roleId = '' } = useParams();
  const roles = useIamStore((s) => s.roles);
  const loadRoles = useIamStore((s) => s.loadRoles);
  const catalogue = useIamStore((s) => s.catalogue);
  const loadCatalogue = useIamStore((s) => s.loadCatalogue);
  const acting = useIamStore(selectActing(roleId));
  const saveRole = useIamStore((s) => s.saveRole);
  const resetActing = useIamStore((s) => s.resetActing);
  const myRoles = useAccessStore((s) => s.mine.data?.roles);
  const pending = useIamStore((s) => s.pending);
  const loadPending = useIamStore((s) => s.loadPending);

  useEffect(() => {
    void loadRoles();
    void loadCatalogue();
    void loadPending();
    resetActing(roleId);
  }, [roleId, loadRoles, loadCatalogue, loadPending, resetActing]);

  const role = roles.data?.find((r) => r.roleId === roleId);
  const [draft, setDraft] = useState<{ name: string; description: string; active: boolean; permissions: Set<string> } | null>(null);

  if (isInitialLoad(roles)) return <LoadingBlock label="Loading role" />;
  if (!role) {
    return roles.error
      ? <div className="p-6"><ErrorPanel error={roles.error} onRetry={() => void loadRoles()} /></div>
      : <div className="p-6 text-sm text-muted-foreground">No such role.</div>;
  }

  const holdsIt = (myRoles ?? []).some((m) => m.code === role.code);
  const editable = can('roles.manage') && !holdsIt;
  const current = draft ?? { name: role.name, description: role.description ?? '', active: role.active,
    permissions: new Set(role.permissions) };
  const outcome = acting.status === 'success' && acting.data !== true ? acting.data : null;

  async function save() {
    await saveRole(roleId, { name: current.name, description: current.description, active: current.active,
      permissions: [...current.permissions] });
    if (useIamStore.getState().acting[roleId]?.status === 'success') setDraft(null);
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Roles', to: '/staff/roles' }]}
        title={role.name}
        description={role.description ?? undefined}
        actions={<StatusBadge kind="staffRole" value={roleStatus(role)} />}
      />
      <DetailLayout record={
        <Panel title="Role">
          <dl className="px-4 pb-2">
            <Field label="Code" value={<span className="font-mono">{role.code}</span>} />
            <Field label="Kind" value={role.builtIn ? 'Built-in' : 'Made here'} />
            <Field label="Held by" value={`${role.holders} active ${role.holders === 1 ? 'person' : 'people'}`} />
            <Field label="Permissions" value={role.permissions.length} />
          </dl>
        </Panel>
      }>
        {(pending.data ?? []).filter((c) => c.roleId === roleId).map((c) => (
          <PendingChange key={c.changeId} change={c} subjectIsMe={holdsIt} />
        ))}
        {holdsIt && (
          <p className="rounded-md border border-border bg-hover px-4 py-2.5 text-sm" role="status">
            You hold this role, so another administrator changes what it carries.
          </p>
        )}
        <Panel title="About">
          <div className="space-y-4 px-4 pb-4">
            <FormField label="Name">
              <Input value={current.name} disabled={!editable}
                onChange={(e) => setDraft({ ...current, name: e.target.value })} />
            </FormField>
            <FormField label="What it is for">
              <Textarea className="min-h-16 py-2" value={current.description} disabled={!editable}
                onChange={(e) => setDraft({ ...current, description: e.target.value })} />
            </FormField>
            <CheckboxField label="In use: people holding it have its permissions" checked={current.active} disabled={!editable}
              onChange={(e) => setDraft({ ...current, active: e.target.checked })} />
          </div>
        </Panel>
        <Panel title="Permissions" subtitle="What everyone holding this role may see and do.">
          <div className="px-4 pb-4">
            <PermissionPicker catalogue={catalogue.data ?? []} selected={current.permissions} disabled={!editable}
              onChange={(permissions) => setDraft({ ...current, permissions })} />
          </div>
        </Panel>
        {editable && (
          <div className="space-y-2">
            {acting.status === 'error' && acting.error && <InlineError error={acting.error} />}
            {outcome && (
              <p role="status" className="text-sm">
                {outcome.applied
                  ? 'Saved. Everyone holding it has the change from their next action.'
                  : 'Saved for approval: it gives Administration, so a second administrator approves it.'}
              </p>
            )}
            <div className="flex gap-2">
              <Button variant="primary" pending={acting.status === 'loading'} disabled={draft === null || !current.name.trim()}
                onClick={() => void save()}>
                Save role
              </Button>
              {draft && <Button variant="ghost" onClick={() => setDraft(null)}>Discard</Button>}
            </div>
          </div>
        )}
      </DetailLayout>
    </>
  );
}
