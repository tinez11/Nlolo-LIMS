import { useEffect, useState } from 'react';
import { DUNNING_LEVELS, LAPSE_RECOMMENDATION_LEVEL, searchArrears, searchFieldReceipts } from '@/api/billing';
import { searchClaims } from '@/api/claims';
import { PARTY_AREAS, searchParties } from '@/api/party';
import { listAwaitingEftExecution } from '@/api/payments';
import { listCases } from '@/api/underwriting';
import type { Realm } from '@/auth/realms';
import { navFor, type BadgeKey } from '@/screens';
import type { readIdentity } from '@/auth/claims';

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
  /* The manager-side counterpart. Assessment is finished on these and a settlement decision
     -- approve or repudiate -- is the only thing standing between the claimant and their
     money, so it is the queue with the most waiting on it and it had no counter at all. */
  'claims-settlement-pending': {
    load: async () =>
      (await searchClaims({ status: 'SETTLEMENT_REQUESTED', pageSize: 1 })).page.totalElements ?? 0,
    title: (n) => `${plural(n, 'claim', 'claims')} awaiting a settlement decision`,
  },
  /* Level 5 is not 'the worst level' -- it is the level that publishes PolicyLapseRecommended,
     which policy consumes to lapse the contract. A count here is policies about to be lost. */
  'arrears-at-lapse': {
    load: async () =>
      (
        await searchArrears({
          minDunningLevel: LAPSE_RECOMMENDATION_LEVEL,
          resolved: false,
          pageSize: 1,
        })
      ).page.totalElements ?? 0,
    title: (n) =>
      `${plural(n, 'case', 'cases')} at dunning level ${DUNNING_LEVELS[DUNNING_LEVELS.length - 1]} -- lapse recommended`,
  },
  /* The state a live Prometheus alert already fires on. The alert names a count and no
     receipt; this at least puts the same count where somebody can click it. */
  'receipts-overdue': {
    load: async () =>
      (await searchFieldReceipts({ status: 'RECONCILIATION_OVERDUE', pageSize: 1 })).page
        .totalElements ?? 0,
    title: (n) => `${plural(n, 'receipt', 'receipts')} past the reconciliation SLA`,
  },
  /* Money the insurer owes and has not paid. No count endpoint and none wanted: the queue is
     what is waiting on a human being, so it is short by construction, and a length is honest
     where a totalElements from a paged search would not be. */
  'eft-awaiting': {
    load: async () => (await listAwaitingEftExecution()).length,
    title: (n) => `${plural(n, 'bank transfer', 'bank transfers')} nobody has made yet`,
  },
};

/**
 * The badge keys actually ON SCREEN for this identity -- nothing else is fetched.
 *
 * Derived from `navFor`, the same function that builds the sidebar, rather than from a
 * second walk over SCREENS. That is what keeps a finance-only count from being requested by
 * a staff member who cannot see the Finance group: the request would 403, the catch below
 * would swallow it, and the only trace would be a failed request on every page load.
 */
function declaredFor(realm: Realm, identity: ReturnType<typeof readIdentity>): BadgeKey[] {
  return [
    ...new Set(
      navFor(realm, identity)
        .flatMap((group) => group.items)
        .map((item) => item.badge)
        .filter((badge): badge is BadgeKey => badge !== undefined),
    ),
  ];
}

export function useNavBadges(
  realm: Realm,
  identity: ReturnType<typeof readIdentity>,
): Partial<Record<BadgeKey, NavBadge>> {
  const [badges, setBadges] = useState<Partial<Record<BadgeKey, NavBadge>>>({});

  useEffect(() => {
    let live = true;
    const keys = declaredFor(realm, identity);
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
  }, [realm, identity]);

  return badges;
}

/**
 * Where each count leads: the list it was counted from, already filtered to the waiting work, so
 * the Today page opens the queue rather than the whole register (2026-10-09, review C5). The query
 * names are the ones each list screen reads back from its URL.
 */
export const BADGE_TARGETS: Record<BadgeKey, string> = {
  'kyc-pending-individuals': 'clients/individuals?kycStatus=PENDING',
  'kyc-pending-organisations': 'clients/organisations?kycStatus=PENDING',
  'underwriting-open': 'underwriting?status=OPEN',
  'claims-unassessed': 'claims?status=REGISTERED',
  'claims-settlement-pending': 'claims?status=SETTLEMENT_REQUESTED',
  'arrears-at-lapse': `arrears?minDunningLevel=${LAPSE_RECOMMENDATION_LEVEL}`,
  'receipts-overdue': 'field-receipts?status=RECONCILIATION_OVERDUE',
  'eft-awaiting': 'bank-transfers',
};

export interface WorkWaiting {
  key: BadgeKey;
  count: number;
  title: string;
  to: string;
}

/**
 * The same counts as the sidebar badges, in sidebar order, with a loading state the badges do not
 * need: the Today page must tell "still counting" from "nothing waiting". A count that failed to
 * load is left out rather than shown as zero -- zero would be a claim the platform did not make.
 */
export function useWorkWaiting(
  realm: Realm,
  identity: ReturnType<typeof readIdentity>,
): { loading: boolean; rows: WorkWaiting[] } {
  const [state, setState] = useState<{ loading: boolean; rows: WorkWaiting[] }>({ loading: true, rows: [] });

  useEffect(() => {
    let live = true;
    const keys = declaredFor(realm, identity);
    void Promise.all(
      keys.map(async (key) => {
        try {
          const count = await LOADERS[key].load();
          return { key, count, title: LOADERS[key].title(count), to: BADGE_TARGETS[key] };
        } catch {
          return null;
        }
      }),
    ).then((rows) => {
      if (live) setState({ loading: false, rows: rows.filter((row) => row !== null) });
    });
    return () => {
      live = false;
    };
  }, [realm, identity]);

  return state;
}
