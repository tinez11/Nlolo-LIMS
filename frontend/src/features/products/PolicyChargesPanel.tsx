import { useEffect, useState } from 'react';
import { getPolicyAccountCharges, type AccountChargeView } from '@/api/accountCharges';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { CHARGE_WHEN, chargeSize } from './chargeText';
import { remember, remembered } from '@/lib/remembered';

/** The account charges a savings policy was issued on (2026-10-09), or that it is on its product's own. */
export function PolicyChargesPanel({ policyNumber }: { policyNumber: string }) {
  const [charges, setCharges] = useState<AccountChargeView[] | null>(() =>
    remembered<AccountChargeView[]>(`account-charges:${policyNumber}`),
  );
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getPolicyAccountCharges(policyNumber).then(
      (c) => { if (live) { setCharges(remember(`account-charges:${policyNumber}`, c)); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [policyNumber, reload]);

  if (error) return <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />;
  if (!charges) return <LoadingBlock />;
  if (charges.length === 0) {
    return <p className="text-sm text-muted-foreground">Charged by its product's own charges.</p>;
  }
  return (
    <ul className="divide-y divide-border">
      {charges.map((c) => (
        <li key={c.chargeId} className="py-2 text-sm">
          {c.name}
          <span className="block text-xs text-muted-foreground">{CHARGE_WHEN[c.when]} · {chargeSize(c)}</span>
        </li>
      ))}
    </ul>
  );
}
