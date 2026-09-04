import { useEffect, useState } from 'react';
import { searchClaims } from '@/api/claims';
import { PARTY_AREAS, searchParties } from '@/api/party';
import { listCases } from '@/api/underwriting';
import type { Realm } from '@/auth/realms';
import { NAV_GROUPS, SCREENS, type BadgeKey } from '@/screens';

/**
 * Counts of work waiting, for the sidebar.
 *
 * Every count is `totalElements` from a real paged search filtered to that
 * queue's unstarted state — so the number means "this many are waiting for
 * someone", not "this many exist". `pageSize: 1` because only the total is
 * wanted; the items are discarded.
 *
 * The `title` is not decoration. A bare "3" beside Claims reads as "3 claims",
 * which is wrong and would be a number with nothing behind it (PLAN.md §6). The
 * tooltip says what was actually counted.
 *
 * A failed count renders NO badge rather than a zero: "nothing waiting" and
 * "could not ask" must not look identical.
 */
export interface NavBadge {
  count: number;
  title: string;
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

const LOADERS: Record<BadgeKey, { load: () => Promise<number>; title: (n: number) => string }> = {
  /*
   * Two KYC counts, not one, because the register is two areas.
   *
   * A single "clients awaiting KYC" badge on one of the two items would be a number
   * that does not belong to the list it sits beside: click it and the filtered area
   * shows fewer rows than the badge promised, with the rest sitting in the other area
   * behind a different nav item. Each area counts its own backlog, and the two are
   * visible at the same time, so nothing is hidden by the split.
   */
  'kyc-pending-individuals': {
    load: async () =>
      (
        await searchParties({
          kycStatus: 'PENDING',
          partyTypes: PARTY_AREAS.individuals,
          pageSize: 1,
        })
      ).page.totalElements ?? 0,
    title: (n) => `${plural(n, 'individual', 'individuals')} awaiting KYC verification`,
  },
  'kyc-pending-organisations': {
    load: async () =>
      (
        await searchParties({
          kycStatus: 'PENDING',
          partyTypes: PARTY_AREAS.organisations,
          pageSize: 1,
        })
      ).page.totalElements ?? 0,
    title: (n) =>
      `${plural(n, 'company or group', 'companies and groups')} awaiting KYC verification`,
  },
  'underwriting-open': {
    load: async () => (await listCases({ status: 'OPEN', pageSize: 1 })).page.totalElements ?? 0,
    title: (n) => `${plural(n, 'case', 'cases')} open and not yet under review`,
  },
  'claims-unassessed': {
    load: async () => (await searchClaims({ status: 'REGISTERED', pageSize: 1 })).page.totalElements ?? 0,
    title: (n) => `${plural(n, 'claim', 'claims')} registered and not yet assessed`,
  },
};

/** The badge keys a realm's visible nav actually declares — nothing else is fetched. */
function declaredFor(realm: Realm): BadgeKey[] {
  const groups = new Set(NAV_GROUPS[realm].map((g) => g.id));
  return [
    ...new Set(
      SCREENS[realm]
        .map((screen) => screen.reach)
        .filter((reach) => reach !== 'drill-in' && groups.has(reach.group) && reach.badge)
        .map((reach) => (reach as { badge: BadgeKey }).badge),
    ),
  ];
}

export function useNavBadges(realm: Realm): Partial<Record<BadgeKey, NavBadge>> {
  const [badges, setBadges] = useState<Partial<Record<BadgeKey, NavBadge>>>({});

  useEffect(() => {
    let live = true;
    const keys = declaredFor(realm);
    if (keys.length === 0) return;

    void Promise.all(
      keys.map(async (key) => {
        try {
          const count = await LOADERS[key].load();
          return [key, { count, title: LOADERS[key].title(count) }] as const;
        } catch {
          // Deliberately silent and badge-less. A count is a convenience; a
          // failed one must not surface an error panel over the whole console,
          // and must not render as "0".
          return null;
        }
      }),
    ).then((settled) => {
      if (!live) return;
      setBadges(Object.fromEntries(settled.filter((entry) => entry !== null)));
    });

    return () => {
      live = false;
    };
    // Loaded once per realm mount. These are ambient counts, not live state --
    // re-fetching on every navigation would add three requests to every click
    // for a number nobody is watching change.
  }, [realm]);

  return badges;
}
