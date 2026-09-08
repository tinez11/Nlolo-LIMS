import type { AccountStatus } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';

/**
 * An account's status, in both views.
 *
 * A chart of accounts is reference data in which `ACTIVE` is the overwhelming norm --
 * thirty-seven of thirty-eight rows in the seeded tenant -- so a badge on every row
 * put a column of identical teal pills on screen and gave the one row that is
 * genuinely notable, the retired one, a *quieter* slate pill than its neighbours. That
 * is the Stamp Rule inverted: colour is supposed to report state, and a screen where
 * nothing notable is happening is supposed to be achromatic.
 *
 * So the norm is plain muted text and everything else keeps the badge. `INACTIVE`
 * stamps, and so does a literal this build has never heard of -- `StatusBadge` gives
 * that one its ring, its trailing `?` and its title, which is exactly the case where
 * the column needs to shout.
 */
export function AccountStatusCell({ status }: { status: AccountStatus }) {
  if (status === 'ACTIVE') return <span className="text-xs text-muted-foreground">Active</span>;
  return <StatusBadge kind="account" value={status} />;
}
