import { useEffect, useState } from 'react';
import { getParty } from '@/api/party';

/**
 * Resolves and shows a party's display name for a read-only reference (e.g. a
 * beneficiary row), the same `GET /parties/{id}` lookup `PartyPicker` already
 * uses to label a pre-existing selection. Falls back to the raw id while
 * loading or if the lookup fails, rather than showing nothing.
 */
export function PartyName({ partyId }: { partyId: string }) {
  const [label, setLabel] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLabel(null);
    getParty(partyId)
      .then((party) => {
        if (!cancelled) setLabel(party.displayName ?? party.partyId ?? partyId);
      })
      .catch(() => {
        // Leave label null -- falls back to the raw id below.
      });
    return () => {
      cancelled = true;
    };
  }, [partyId]);

  return <span className={label ? 'text-sm' : 'font-mono text-xs'}>{label ?? partyId}</span>;
}
