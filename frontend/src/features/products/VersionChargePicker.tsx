import { useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import { listAccountCharges, type AccountChargeView } from '@/api/accountCharges';
import { InlineError } from '@/components/InlineError';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { toApiError, type ApiError } from '@/lib/apiError';
import { NewCharge } from './AccountChargesPage';
import { chargeLine } from './chargeText';

/**
 * The Account charges a product version takes (2026-10-22, product V34): ticked from the one list staff keep under
 * Account charges, or created here and ticked. Its policies are charged by these unless one is given its own.
 */
export function VersionChargePicker({ value, onChange }: { value: string[]; onChange: (ids: string[]) => void }) {
  const [charges, setCharges] = useState<AccountChargeView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    let live = true;
    listAccountCharges(true).then(
      (c) => { if (live) { setCharges(c); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  if (error) return <InlineError sticky error={error} lead="Could not load the charges" />;
  if (!charges) return <LoadingBlock label="Loading charges" />;

  const toggle = (id: string) => onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
  return (
    <div className="space-y-3">
      {charges.length === 0 ? (
        <p className="text-sm text-muted-foreground">No account charges yet.</p>
      ) : (
        <fieldset aria-label="Account charges" className="divide-y divide-border rounded-lg border border-border">
          {charges.map((c) => (
            <label key={c.chargeId} className="flex cursor-pointer items-start gap-3 px-3 py-2.5 hover:bg-hover">
              <Checkbox className="mt-0.5" checked={value.includes(c.chargeId)} onChange={() => toggle(c.chargeId)} />
              <span className="min-w-0 text-sm">
                {c.name}
                <span className="block text-xs text-muted-foreground">{chargeLine(c)}</span>
              </span>
            </label>
          ))}
        </fieldset>
      )}
      {creating ? (
        <NewCharge
          onCreated={(created) => {
            setCreating(false);
            setReload((n) => n + 1);
            if (created) onChange([...value, created.chargeId]);
          }}
          onCancel={() => setCreating(false)}
        />
      ) : (
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => setCreating(true)}>
          <Plus className="size-4" /> New charge
        </Button>
      )}
    </div>
  );
}
