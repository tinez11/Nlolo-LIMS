import { useEffect, useState } from 'react';
import { listAccountCharges, type AccountChargeView } from '@/api/accountCharges';
import { InlineError } from '@/components/InlineError';
import { LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { ChargeChooser } from './ChargeChooser';
import { useVersionCharges } from './useVersionCharges';

/**
 * The account charges a savings policy will be charged by (2026-10-09, product V32), added one at a time. None added means
 * the product's charges (V34), named here when the version is known. Only charges still offered can be added.
 */
export function AccountChargePicker({ value, onChange, legend = 'Account charges', productId, versionId }: {
  value: string[]; onChange: (chargeIds: string[]) => void; legend?: string; productId?: string | undefined;
  versionId?: string | undefined;
}) {
  const productCharges = useVersionCharges(productId, versionId);
  const [charges, setCharges] = useState<AccountChargeView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    listAccountCharges(true).then(
      (c) => { if (live) setCharges(c); },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, []);

  if (error) return <InlineError sticky error={error} lead="Could not load the charges" />;
  if (!charges) return <LoadingBlock label="Loading charges" />;

  const productsOwn = productCharges === null ? "The product's charges apply."
    : productCharges.length === 0 ? 'The product takes no charges.'
      : `The product's charges apply: ${productCharges.map((c) => c.name).join(', ')}.`;
  return (
    <fieldset className="space-y-2">
      <legend className="mb-1 text-xs font-medium text-muted-foreground">{legend}</legend>
      <ChargeChooser charges={charges} value={value} onChange={onChange} empty={productsOwn} />
      {value.length > 0 && <p className="text-xs text-subtle-foreground">These replace the product's charges.</p>}
    </fieldset>
  );
}
