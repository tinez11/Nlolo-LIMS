import { describe, expect, it } from 'vitest';
import type { PermissionInfo } from '@/api/access';
import { NAV_GROUPS } from '@/screens';
import { groupPermissions, PERMISSION_GROUPS } from './permissionGroups';

describe('permission groups', () => {
  it('use the sidebar groups, by the same labels and in the same order', () => {
    const nav = NAV_GROUPS.staff.filter((g) => PERMISSION_GROUPS.some((p) => p.id === g.id));
    expect(PERMISSION_GROUPS.map((g) => [g.id, g.label])).toEqual(nav.map((g) => [g.id, g.label]));
  });

  it('places every catalogue area in exactly one group', () => {
    const areas = PERMISSION_GROUPS.flatMap((g) => g.areas);
    expect(new Set(areas).size).toBe(areas.length);
    expect(areas).toHaveLength(19);
  });

  it('lists a group in the order its areas are given, and drops groups with nothing', () => {
    const p = (code: string, area: string) => ({ code, area, areaLabel: area, label: code, description: '' }) as PermissionInfo;
    const grouped = groupPermissions([p('schemes.view', 'SCHEMES'), p('underwriting.view', 'UNDERWRITING')]);
    expect(grouped).toEqual([
      { id: 'new-business', label: 'New business', items: [expect.objectContaining({ code: 'underwriting.view' }),
        expect.objectContaining({ code: 'schemes.view' })] },
    ]);
  });
});
