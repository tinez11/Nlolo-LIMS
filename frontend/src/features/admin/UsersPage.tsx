import { Plus, Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import type { StaffUser } from '@/api/access';
import { can } from '@/auth/access';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useIamStore } from '@/store/iamStore';

const SHOWS = ['ACTIVE', 'DEACTIVATED', 'AWAITING_APPROVAL'] as const;
type Show = (typeof SHOWS)[number];

/** A user's status as the badge reads it. */
export function staffUserStatus(u: Pick<StaffUser, 'active'>): string {
  return u.active ? 'ACTIVE' : 'DEACTIVATED';
}

/**
 * Everyone who signs in to the staff console (2026-10-26): their role, branch and when they were last in. A change to
 * someone's access that waits for a second administrator is found with the Awaiting approval filter and decided on
 * that person's page, as a manual journal is approved on its own page.
 */
export function UsersPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const showParam = params.get('show');
  const show: Show | undefined = showParam && (SHOWS as readonly string[]).includes(showParam) ? (showParam as Show) : undefined;
  const qParam = params.get('q') ?? '';
  const [qInput, setQInput] = useState(qParam);

  const users = useIamStore((s) => s.users);
  const loadUsers = useIamStore((s) => s.loadUsers);

  useEffect(() => {
    void loadUsers(qParam || undefined);
  }, [loadUsers, qParam]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setQInput(qParam);
  }, [qParam]);

  useEffect(() => {
    if (qInput === qParam) return;
    const timer = setTimeout(() => update({ q: qInput || undefined }), 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qInput]);

  function update(next: { show?: Show | undefined; q?: string | undefined }) {
    const merged = new URLSearchParams(params);
    if ('show' in next) {
      if (next.show) merged.set('show', next.show);
      else merged.delete('show');
    }
    if ('q' in next) {
      if (next.q) merged.set('q', next.q);
      else merged.delete('q');
    }
    setParams(merged);
  }

  const rows = (users.data ?? []).filter((u) =>
    show === 'ACTIVE' ? u.active : show === 'DEACTIVATED' ? !u.active : show === 'AWAITING_APPROVAL' ? u.pendingApproval : true);

  const count: Stat = {
    label: 'users',
    value: users.data ? rows.length : null,
    pending: isInitialLoad(users),
    hint: users.status === 'error' && users.data === null ? 'could not load' : show || qParam ? 'matching this filter' : 'in this tenant',
  };

  const columns: Column<StaffUser>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (u) => (
        <span>
          <span className="block font-medium">{u.displayName ?? u.username}</span>
          <span className="block text-xs text-muted-foreground">{u.username}</span>
        </span>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      render: (u) => (u.roles[0] ? (
        <span>
          {u.roles[0].name}
          {u.roles.length > 1 && <span className="ml-1 text-xs text-subtle-foreground">+{u.roles.length - 1}</span>}
        </span>
      ) : <span className="text-muted-foreground">—</span>),
    },
    { key: 'branch', header: 'Branch', secondary: true, render: (u) => u.homeBranch ?? '—' },
    {
      key: 'status',
      header: 'Status',
      render: (u) => (
        <span className="flex flex-wrap gap-1">
          <StatusBadge kind="staffUser" value={staffUserStatus(u)} />
          {u.pendingApproval && <StatusBadge kind="accessChange" value="PENDING" label="Awaiting approval" />}
        </span>
      ),
    },
    {
      key: 'seen',
      header: 'Last in',
      secondary: true,
      render: (u) => <span className="whitespace-nowrap text-muted-foreground">{u.lastSeenAt ? formatDate(u.lastSeenAt) : '—'}</span>,
    },
  ];

  function renderBody() {
    if (isInitialLoad(users)) return <TableSkeleton columns={columns.length} />;
    if (users.status === 'error' && users.error && users.data === null) {
      return <ErrorPanel error={users.error} onRetry={() => void loadUsers(qParam || undefined)} />;
    }
    if (rows.length === 0) {
      return (
        <EmptyState
          title={show || qParam ? 'No users match' : 'No users yet'}
          description={show || qParam ? 'Nobody matches this filter.' : 'A user appears here the first time they sign in, or when added.'}
          {...(show || qParam ? { action: <Button size="sm" onClick={() => update({ show: undefined, q: undefined })}>Clear filter</Button> } : {})}
        />
      );
    }
    return (
      <DataTable columns={columns} rows={rows} rowKey={(u) => u.userId} onRowActivate={(u) => navigate(u.userId)}
        caption="Users" />
    );
  }

  return (
    <>
      <PageHeader
        title="Users"
        description="Everyone who signs in to this console, and what they hold. Select one to change their access."
        count={<CountLine {...count} />}
        actions={can('users.manage') ? (
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              Add user
            </Link>
          </Button>
        ) : undefined}
      />
      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-center gap-1.5 gap-y-2 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={show === undefined} onClick={() => update({ show: undefined })} />
            <FilterChip label={<StatusBadge kind="staffUser" value="ACTIVE" />} bare active={show === 'ACTIVE'}
              onClick={() => update({ show: 'ACTIVE' })} />
            <FilterChip label={<StatusBadge kind="staffUser" value="DEACTIVATED" />} bare active={show === 'DEACTIVATED'}
              onClick={() => update({ show: 'DEACTIVATED' })} />
            <FilterChip label={<StatusBadge kind="accessChange" value="PENDING" label="Awaiting approval" />} bare
              active={show === 'AWAITING_APPROVAL'} onClick={() => update({ show: 'AWAITING_APPROVAL' })} />
            <div className="relative ml-auto w-full max-w-55">
              <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                inputSize="sm"
                value={qInput}
                onChange={(e) => setQInput(e.target.value)}
                data-list-filter
                placeholder="Search by name, login or email"
                aria-label="Search by name, login or email"
                className="pl-7 pr-2.5"
              />
            </div>
          </div>
          {renderBody()}
        </div>
      </div>
    </>
  );
}
