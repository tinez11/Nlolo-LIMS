import { useEffect, useState } from 'react';
import { getAgent } from '@/api/distribution';
import { PartyName } from '@/components/PartyName';
import { cn } from '@/lib/cn';

/**
 * Resolves a distribution agent id to the agent's name.
 *
 * Two hops, because an agent has no name of its own: the distribution module
 * stores a licence number and a `partyId`, and the NAME lives on the party
 * record. `GET /agents/{id}` gives the partyId, {@link PartyName} turns that
 * into a person -- each hop cached by its own module, so a page showing one
 * agent on five rows makes two requests in total.
 *
 * This is why `agentOfRecordId` was printed raw everywhere it appeared -- a
 * policy's rail, the policy drawer, the field receipts register. `PartyName`
 * could not be used directly: it takes a PARTY id, and an agent id is not one.
 * Passing the wrong id there would have 404ed and silently fallen back to
 * printing the id, which looks exactly like no lookup at all.
 *
 * Falls back to the licence number, then to the raw id -- and does so quietly on
 * a 403 as well as a 404, since `GET /agents/{id}` is closed to the customers
 * realm and force-scoped to an agent's own profile in the agents realm.
 */

const AGENTS = new Map<string, { partyId: string | null; licenseNumber: string | null } | null>();
const PENDING = new Map<string, Promise<unknown>>();

function resolveAgent(agentId: string) {
  const cached = AGENTS.get(agentId);
  if (cached !== undefined) return Promise.resolve(cached);

  const inFlight = PENDING.get(agentId);
  if (inFlight) return inFlight;

  const request = getAgent(agentId)
    .then((agent) => {
      const resolved = {
        partyId: agent.partyId ?? null,
        licenseNumber: agent.licenseNumber ?? null,
      };
      AGENTS.set(agentId, resolved);
      return resolved;
    })
    .catch(() => {
      AGENTS.set(agentId, null);
      return null;
    })
    .finally(() => {
      PENDING.delete(agentId);
    });

  PENDING.set(agentId, request);
  return request;
}

/** Exported for tests, which must not inherit another test's cached lookups. */
// eslint-disable-next-line react-refresh/only-export-components
export function __clearAgentNameCache() {
  AGENTS.clear();
  PENDING.clear();
}

export function AgentName({
  agentId,
  className,
  withLicense = true,
}: {
  agentId: string;
  className?: string;
  /** Show the licence number beside the name. Off where space is tight. */
  withLicense?: boolean;
}) {
  const cached = AGENTS.get(agentId);
  const [, bumpAfterResolve] = useState(0);

  useEffect(() => {
    if (AGENTS.has(agentId)) return;
    let cancelled = false;
    void resolveAgent(agentId).then(() => {
      if (!cancelled) bumpAfterResolve((n) => n + 1);
    });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  if (!cached) {
    return <span className={cn('font-mono text-xs', className)}>{agentId}</span>;
  }

  // A licensed agent with no party record is not a state this platform creates,
  // but the licence number is a real identifier and beats falling back to a uuid.
  if (!cached.partyId) {
    return cached.licenseNumber ? (
      <span className={cn('text-sm', className)} title={agentId}>
        {cached.licenseNumber}
      </span>
    ) : (
      <span className={cn('font-mono text-xs', className)}>{agentId}</span>
    );
  }

  return (
    <span className={cn('text-sm', className)} title={agentId}>
      <PartyName partyId={cached.partyId} />
      {withLicense && cached.licenseNumber && (
        <span className="ml-1.5 font-mono text-[11px] text-subtle-foreground">
          {cached.licenseNumber}
        </span>
      )}
    </span>
  );
}
