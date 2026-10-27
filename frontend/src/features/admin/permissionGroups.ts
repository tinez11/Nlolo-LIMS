import type { PermissionInfo } from '@/api/access';

/**
 * Where each permission area sits in the staff sidebar (2026-10-26), so the permission lists read in the order a person
 * meets the work -- the menu's own groups and labels, not the catalogue's. permissionGroups.test pins these to
 * screens.tsx NAV_GROUPS.
 */
export const PERMISSION_GROUPS: readonly { id: string; label: string; areas: readonly string[] }[] = [
  { id: 'clients', label: 'Clients', areas: ['CLIENTS'] },
  { id: 'new-business', label: 'New business', areas: ['UNDERWRITING', 'SCHEMES'] },
  { id: 'policies-claims', label: 'Policies & claims', areas: ['POLICIES', 'CLAIMS', 'ACCOUNTS'] },
  { id: 'collections', label: 'Collections', areas: ['COLLECTIONS'] },
  { id: 'paying-out', label: 'Paying out', areas: ['PAYOUTS'] },
  { id: 'ledger', label: 'Ledger', areas: ['LEDGER'] },
  { id: 'valuation', label: 'Valuation & close', areas: ['IFRS17', 'FUNDS'] },
  { id: 'reinsurance-returns', label: 'Reinsurance & returns', areas: ['REINSURANCE', 'RETURNS'] },
  { id: 'distribution', label: 'Distribution', areas: ['DISTRIBUTION'] },
  { id: 'reports', label: 'Reports', areas: ['REPORTS'] },
  { id: 'records', label: 'Records', areas: ['AUDIT'] },
  { id: 'communications', label: 'Communications', areas: ['COMMUNICATIONS'] },
  { id: 'configuration', label: 'Configuration', areas: ['PRODUCTS'] },
  { id: 'administration', label: 'Administration', areas: ['ADMINISTRATION'] },
];

/** The catalogue in menu order, each group's permissions in catalogue order within their areas. */
export function groupPermissions(catalogue: readonly PermissionInfo[]): { id: string; label: string; items: PermissionInfo[] }[] {
  return PERMISSION_GROUPS
    .map((g) => ({ id: g.id, label: g.label, items: g.areas.flatMap((a) => catalogue.filter((p) => p.area === a)) }))
    .filter((g) => g.items.length > 0);
}
