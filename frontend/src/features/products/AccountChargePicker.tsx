import { useEffect, useState } from 'react';
import { listAccountCharges, type AccountChargeView } from '@/api/accountCharges';
import { InlineError } from '@/components/InlineError';
import { LoadingBlock } from '@/components/states';
import { Checkbox } from '@/components/ui/checkbox';
import { toApiError, type ApiError } from '@/lib/apiError';
import { chargeLine } from './chargeText';
import { useVersionCharges } from './useVersionCharges';

/**
 * Tick the account charges a savings policy will be charged by (2026-10-09, product V32). None ticked means the product's
 * charges (V34), named here when the version is known. Only charges still offered are listed.
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
  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  return (
    <fieldset className="space-y-1.5">
      <legend className="mb-1 text-xs font-medium text-muted-foreground">{legend}</legend>
      {charges.length === 0 ? (
        <p className="text-xs text-muted-foreground">{productsOwn}</p>
      ) : (
        <>
          {charges.map((c) => (
            <label key={c.chargeId} className="flex items-start gap-2 text-sm">
              <Checkbox className="mt-0.5" checked={value.includes(c.chargeId)} onChange={() => toggle(c.chargeId)} />
              <span>
                {c.name}
                <span className="block text-xs text-muted-foreground">{chargeLine(c)}</span>
              </span>
            </label>
          ))}
          <p className="text-xs text-subtle-foreground">None ticked: {productsOwn.charAt(0).toLowerCase() + productsOwn.slice(1)}</p>
        </>
      )}
    </fieldset>
  );
}
