import { useEffect, useState } from 'react';
import { getPolicyAccountCharges, type AccountChargeView } from '@/api/accountCharges';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { chargeLine } from './chargeText';
import { useVersionCharges } from './useVersionCharges';
import { remember, remembered } from '@/lib/remembered';

/** The account charges a savings policy was issued on (2026-10-09), or else its product's (2026-10-22, V34). */
export function PolicyChargesPanel({ policyNumber, productId, versionId }: {
  policyNumber: string; productId?: string | undefined; versionId?: string | undefined;
}) {
  const productCharges = useVersionCharges(productId, versionId);
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
  if (charges.length > 0) return <ChargeList charges={charges} />;
  if (productCharges && productCharges.length > 0) {
    return (
      <>
        <p className="pt-1 text-xs text-muted-foreground">The product's charges</p>
        <ChargeList charges={productCharges} />
      </>
    );
  }
  return <p className="text-sm text-muted-foreground">Charged by its product's charges.</p>;
}

function ChargeList({ charges }: { charges: AccountChargeView[] }) {
  return (
    <ul className="divide-y divide-border">
      {charges.map((c) => (
        <li key={c.chargeId} className="py-2 text-sm">
          {c.name}
          <span className="block text-xs text-muted-foreground">{chargeLine(c)}</span>
        </li>
      ))}
    </ul>
  );
}
