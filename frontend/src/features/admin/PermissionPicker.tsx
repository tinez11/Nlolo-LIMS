import type { PermissionInfo } from '@/api/access';
import { Checkbox } from '@/components/ui/checkbox';
import { cn } from '@/lib/cn';

/**
 * Permissions as the person thinks of them (2026-10-26): grouped by area, each with what it lets someone do. Used for
 * a role's permissions and for the single permissions one person holds on top of their roles.
 *
 * `fromRoles` are shown ticked and fixed, saying which role gives them -- so the single permissions an administrator
 * adds are only ever what the roles do not already give.
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
  const areas: { area: string; label: string; items: PermissionInfo[] }[] = [];
  for (const p of catalogue) {
    const last = areas[areas.length - 1];
    if (last && last.area === p.area) last.items.push(p);
    else areas.push({ area: p.area, label: p.areaLabel, items: [p] });
  }

  function toggle(code: string, on: boolean) {
    const next = new Set(selected);
    if (on) next.add(code);
    else next.delete(code);
    onChange(next);
  }

  return (
    <div className="grid gap-x-8 gap-y-5 sm:grid-cols-2 xl:grid-cols-3">
      {areas.map(({ area, label, items }) => (
        <fieldset key={area} className="min-w-0">
          <legend className="mb-1.5 text-eyebrow text-muted-foreground uppercase">{label}</legend>
          <ul className="space-y-1">
            {items.map((p) => {
              const roles = fromRoles.get(p.code);
              const viaRole = roles !== undefined && roles.length > 0;
              return (
                <li key={p.code}>
                  <label className={cn('flex cursor-pointer items-start gap-2 rounded-md px-1 py-1 hover:bg-hover',
                    (disabled || viaRole) && 'cursor-default hover:bg-transparent')}>
                    <Checkbox
                      className="mt-0.5"
                      checked={viaRole || selected.has(p.code)}
                      disabled={disabled || viaRole}
                      onChange={(e) => toggle(p.code, e.target.checked)}
                    />
                    <span className="min-w-0">
                      <span className="block text-sm">{p.label}</span>
                      <span className="block text-xs text-muted-foreground">
                        {viaRole ? `From ${roles.join(', ')}` : p.description}
                      </span>
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        </fieldset>
      ))}
    </div>
  );
}
