import { useEffect, useState } from 'react';
import { listAccountCharges, type AccountChargeView } from '@/api/accountCharges';
import { InlineError } from '@/components/InlineError';
import { LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { CHARGE_WHEN, chargeSize } from './chargeText';

/**
 * Tick the account charges a savings policy will be charged by (2026-10-09, product V32). None ticked means the product's
 * own charges. Only charges still offered are listed.
 */
export function AccountChargePicker({ value, onChange, legend = 'Account charges' }: {
  value: string[]; onChange: (chargeIds: string[]) => void; legend?: string;
}) {
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

  if (error) return <InlineError error={error} lead="Could not load the charges" />;
  if (!charges) return <LoadingBlock label="Loading charges" />;

  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  return (
    <fieldset className="space-y-1.5">
      <legend className="mb-1 text-xs font-medium text-muted-foreground">{legend}</legend>
      {charges.length === 0 ? (
        <p className="text-xs text-muted-foreground">No charges have been set up — the product's own charges apply.</p>
      ) : (
        <>
          {charges.map((c) => (
            <label key={c.chargeId} className="flex items-start gap-2 text-sm">
              <input type="checkbox" className="mt-1" checked={value.includes(c.chargeId)} onChange={() => toggle(c.chargeId)} />
              <span>
                {c.name}
                <span className="block text-xs text-muted-foreground">{CHARGE_WHEN[c.when]} · {chargeSize(c)}</span>
              </span>
            </label>
          ))}
          <p className="text-xs text-subtle-foreground">None ticked: the product's own charges apply.</p>
        </>
      )}
    </fieldset>
  );
}
