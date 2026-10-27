import { useState } from 'react';
import type { PermissionInfo } from '@/api/access';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { Checkbox } from '@/components/ui/checkbox';
import { groupPermissions } from './permissionGroups';

/**
 * Permissions as the person meets the work (2026-10-26): grouped by the sidebar's own groups, one table each, one row
 * per permission -- what it is, what it allows, and where it comes from. Used for a role's permissions and for the
 * single permissions one person holds on top of their roles.
 *
 * `fromRoles` are ticked and fixed, naming the role that gives them, so the single permissions an administrator adds
 * are only ever what the roles do not already give.
 */
export function PermissionPicker({
  catalogue,
  selected,
  onChange,
  fromRoles = new Map(),
  disabled = false,
}: {
  catalogue: readonly PermissionInfo[];
  selected: ReadonlySet<string>;
  onChange: (next: Set<string>) => void;
  /** Permission code -> the role names that already give it. */
  fromRoles?: ReadonlyMap<string, string[]>;
  disabled?: boolean;
}) {
  const [heldOnly, setHeldOnly] = useState(false);
  const held = (code: string) => selected.has(code) || (fromRoles.get(code)?.length ?? 0) > 0;
  const groups = groupPermissions(catalogue)
    .map((g) => ({ ...g, rows: heldOnly ? g.items.filter((p) => held(p.code)) : g.items, heldCount: g.items.filter((p) => held(p.code)).length }))
    .filter((g) => g.rows.length > 0);

  function toggle(code: string, on: boolean) {
    const next = new Set(selected);
    if (on) next.add(code);
    else next.delete(code);
    onChange(next);
  }

  const columns: Column<PermissionInfo>[] = [
    {
      key: 'held',
      header: <span className="sr-only">Held</span>,
      className: 'w-10',
      render: (p) => {
        const viaRole = (fromRoles.get(p.code)?.length ?? 0) > 0;
        return (
          <Checkbox
            aria-label={p.label}
            checked={viaRole || selected.has(p.code)}
            disabled={disabled || viaRole}
            onChange={(e) => toggle(p.code, e.target.checked)}
          />
        );
      },
    },
    // Fixed widths, the same in every group, so the columns line up down the whole list.
    { key: 'label', header: 'Permission', className: 'w-56', render: (p) => <span className="font-medium">{p.label}</span> },
    { key: 'allows', header: 'What it allows', secondary: true, render: (p) => <span className="text-muted-foreground">{p.description}</span> },
    {
      key: 'from',
      header: 'Comes from',
      className: 'w-44',
      nowrap: true,
      render: (p) => {
        const roles = fromRoles.get(p.code);
        if (roles?.length) return roles.join(', ');
        if (selected.has(p.code)) return 'Single permission';
        return <span className="text-subtle-foreground">—</span>;
      },
    },
  ];
  // On a role there is nowhere else a permission could come from, so the column says nothing.
  const shown = fromRoles.size > 0 ? columns : columns.filter((c) => c.key !== 'from');

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-1.5">
        <FilterChip label="All permissions" active={!heldOnly} onClick={() => setHeldOnly(false)} />
        <FilterChip label="Held only" active={heldOnly} onClick={() => setHeldOnly(true)} />
      </div>
      {groups.length === 0 && <p className="text-sm text-muted-foreground">Nothing held yet.</p>}
      {groups.map((g) => (
        <section key={g.id} aria-label={g.label} className="overflow-hidden rounded-md border border-border">
          <div className="flex items-baseline justify-between gap-3 px-3 py-2">
            <p className="text-eyebrow text-muted-foreground uppercase">{g.label}</p>
            <p className="text-xs text-muted-foreground tabular-nums">{g.heldCount} of {g.items.length} held</p>
          </div>
          <DataTable columns={shown} rows={g.rows} rowKey={(p) => p.code} caption={`${g.label} permissions`} />
        </section>
      ))}
    </div>
  );
}
