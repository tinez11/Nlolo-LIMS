import type { TreatyView } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { humanizeStatus } from '@/lib/status';

/**
 * How a treaty is described on the list, the drawer and the detail page -- one place, so the three
 * cannot drift apart.
 */

export type TreatyStanding = TreatyView['standing'];

/** Chip order: what staff look for first. */
export const TREATY_STANDINGS: readonly TreatyStanding[] = ['IN_FORCE', 'NOT_STARTED', 'ENDED'];

/** `QUOTA_SHARE` → `Quota share`, `XOL` stays `XOL`. */
export function treatyTypeLabel(type: TreatyView['treatyType']): string {
  return humanizeStatus(type);
}

/**
 * The term that actually decides what a treaty takes, in words.
 *
 * Every treaty stores a retention limit, but a quota share never uses it: it cedes a fixed
 * percentage of each policy, and its retention is stored as a 0.00 placeholder. Showing "Retention
 * limit TZS 0.00" for those read as "this treaty retains nothing", which is the opposite of true.
 */
export function treatyCedes(t: Pick<TreatyView, 'treatyType' | 'cessionPercent' | 'retentionLimit'>): string {
  switch (t.treatyType) {
    case 'QUOTA_SHARE':
      return t.cessionPercent ? `${t.cessionPercent}% of each policy` : '—';
    case 'SURPLUS':
      return `Cover above ${formatMoney(t.retentionLimit)}`;
    case 'XOL':
      return `Losses above ${formatMoney(t.retentionLimit)}`;
  }
}

/** Case-insensitive match on the reinsurer's name; a blank query matches everything. */
export function matchesReinsurer(t: Pick<TreatyView, 'reinsurerName'>, query: string): boolean {
  const q = query.trim().toLowerCase();
  return q === '' || t.reinsurerName.toLowerCase().includes(q);
}
