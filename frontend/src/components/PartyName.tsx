import { useEffect, useState } from 'react';
import { getParty } from '@/api/party';
import { cn } from '@/lib/cn';

/**
 * Resolves a party id to the person's name for a read-only reference.
 *
 * Why this exists at all: staff decide things about people, and a decision
 * screen that says `3f9a2c1e-8b4d-…` instead of a name forces a second tab
 * before the reviewer even knows whose claim they are holding. Every read-only
 * party reference in the console goes through here.
 *
 * The id is never thrown away -- it stays on the `title` (and, with `withId`,
 * on a second line) because it is still what someone pastes into a support
 * thread or a backend query.
 *
 * Falls back to the raw id while loading and if the lookup fails, rather than
 * showing nothing or inventing a placeholder name. A 404 here is not
 * necessarily absence: `GET /parties/{id}` returns 404 for a party the caller
 * may not read, so the id is the honest thing to show.
 */

/**
 * Module-level cache, shared by every instance.
 *
 * A 20-row queue previously meant 20 requests for what is frequently the same
 * handful of parties, and a re-render meant 20 more. `undefined` means never
 * looked up; `null` means looked up and unresolvable, which is cached too so an
 * id nobody can read does not get retried once per row.
 */
const NAMES = new Map<string, string | null>();
const PENDING = new Map<string, Promise<string | null>>();

function resolveParty(partyId: string): Promise<string | null> {
  const cached = NAMES.get(partyId);
  if (cached !== undefined) return Promise.resolve(cached);

  // Deduplicate concurrent lookups: twelve rows mounting in the same tick for
  // one party must produce one request, not twelve.
  const inFlight = PENDING.get(partyId);
  if (inFlight) return inFlight;

  const request = getParty(partyId)
    .then((party) => {
      const name = party.displayName ?? null;
      NAMES.set(partyId, name);
      return name;
    })
    .catch(() => {
      NAMES.set(partyId, null);
      return null;
    })
    .finally(() => {
      PENDING.delete(partyId);
    });

  PENDING.set(partyId, request);
  return request;
}

/**
 * Exported for tests, which must not inherit another test's cached lookups: the
 * cache is module state, so without this a spec that stubs `getParty`
 * differently from the one before it would silently read the earlier answer.
 *
 * The disable below is deliberate. This is a test hook, not a shared constant,
 * and moving it to its own module would put the cache one import away from the
 * only component that owns it -- which is exactly how the cache would end up
 * with a second copy.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function __clearPartyNameCache() {
  NAMES.clear();
  PENDING.clear();
}

export function PartyName({
  partyId,
  className,
  withId = false,
}: {
  partyId: string;
  className?: string;
  /** Also render the raw id beneath, for screens where it is copied often. */
  withId?: boolean;
}) {
  // Read the cache during render, not in an effect: a name another row already
  // resolved paints on first frame, with no flash of the raw id.
  const cached = NAMES.get(partyId);
  const [, bumpAfterResolve] = useState(0);

  useEffect(() => {
    if (NAMES.has(partyId)) return;
    let cancelled = false;
    void resolveParty(partyId).then(() => {
      if (!cancelled) bumpAfterResolve((n) => n + 1);
    });
    return () => {
      cancelled = true;
    };
  }, [partyId]);

  const name = cached ?? null;

  if (!name) {
    return <span className={cn('font-mono text-xs', className)}>{partyId}</span>;
  }

  if (withId) {
    return (
      <span className={cn('inline-block', className)}>
        <span className="text-sm">{name}</span>
        <span className="mt-0.5 block font-mono text-xs text-subtle-foreground select-all">
          {partyId}
        </span>
      </span>
    );
  }

  return (
    <span className={cn('text-sm', className)} title={partyId}>
      {name}
    </span>
  );
}
