import { Plus } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import type { RoleView } from '@/api/access';
import { can } from '@/auth/access';
import { DataTable, type Column } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useIamStore } from '@/store/iamStore';

/** A role's status as the badge reads it. */
export function roleStatus(r: Pick<RoleView, 'active'>): string {
  return r.active ? 'IN_USE' : 'OFF';
}

/**
 * Every role (2026-10-26): the eight built-in roles each tenant starts with, then any made here. Select one to see and
 * change what it carries.
 */
export function RolesPage() {
  const navigate = useNavigate();
  const roles = useIamStore((s) => s.roles);
  const loadRoles = useIamStore((s) => s.loadRoles);

  useEffect(() => {
    void loadRoles();
  }, [loadRoles]);

  const count: Stat = {
    label: 'roles',
    value: roles.data?.length ?? null,
    pending: isInitialLoad(roles),
    hint: roles.status === 'error' && roles.data === null ? 'could not load' : 'in this tenant',
  };

  const columns: Column<RoleView>[] = [
    {
      key: 'name',
      header: 'Role',
      render: (r) => (
        <span>
          <span className="block font-medium">{r.name}</span>
          {r.description && <span className="block text-xs text-muted-foreground">{r.description}</span>}
        </span>
      ),
    },
    { key: 'kind', header: 'Kind', secondary: true, render: (r) => (r.builtIn ? 'Built-in' : 'Made here') },
    { key: 'permissions', header: 'Permissions', align: 'right', render: (r) => r.permissions.length },
    { key: 'holders', header: 'Held by', align: 'right', render: (r) => r.holders },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge kind="staffRole" value={roleStatus(r)} /> },
  ];

  return (
    <>
      <PageHeader
        title="Roles"
        description="Each role is a set of permissions. Change what a role carries and everyone holding it changes with it."
        count={<CountLine {...count} />}
        actions={can('roles.manage') ? (
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              New role
            </Link>
          </Button>
        ) : undefined}
      />
      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card">
          {isInitialLoad(roles) ? (
            <TableSkeleton columns={columns.length} />
          ) : roles.status === 'error' && roles.error && roles.data === null ? (
            <ErrorPanel error={roles.error} onRetry={() => void loadRoles()} />
          ) : (
            <DataTable columns={columns} rows={roles.data ?? []} rowKey={(r) => r.roleId}
              onRowActivate={(r) => navigate(r.roleId)} caption="Roles" />
          )}
        </div>
      </div>
    </>
  );
}
