import { useState, type ReactNode } from 'react';
import { Plus, X } from 'lucide-react';
import type { AccountChargeView } from '@/api/accountCharges';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { chargeLine } from './chargeText';

/**
 * The charges picked, each removable, and "Add charge" for the rest (the user, 2026-10-22: a list of every charge with a
 * box beside each was not friendly). Shared by a product version's Charges step and a policy's case and issue forms.
 */
export function ChargeChooser({ charges, value, onChange, empty, extra }: {
  charges: AccountChargeView[];
  value: string[];
  onChange: (ids: string[]) => void;
  /** Shown when nothing is picked. */
  empty: ReactNode;
  /** Below the list of charges to add, e.g. a way to create one. */
  extra?: ReactNode;
}) {
  const [adding, setAdding] = useState(false);
  const [query, setQuery] = useState('');
  const picked = value.map((id) => charges.find((c) => c.chargeId === id)).filter((c): c is AccountChargeView => !!c);
  const q = query.trim().toLowerCase();
  const rest = charges.filter((c) => !value.includes(c.chargeId)
    && (q === '' || c.name.toLowerCase().includes(q) || chargeLine(c).toLowerCase().includes(q)));

  return (
    <div className="space-y-2">
      {picked.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul aria-label="Charges picked" className="divide-y divide-border rounded-lg border border-border">
          {picked.map((c) => (
            <li key={c.chargeId} className="flex items-center gap-3 px-3 py-2">
              <span className="min-w-0 flex-1 text-sm">
                {c.name}
                <span className="block text-xs text-muted-foreground">{chargeLine(c)}</span>
              </span>
              <Button type="button" size="icon" variant="ghost" aria-label={`Remove ${c.name}`}
                onClick={() => onChange(value.filter((v) => v !== c.chargeId))}>
                <X className="size-4" />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {adding ? (
        <div className="space-y-2 rounded-lg border border-border p-2">
          <Input inputSize="sm" aria-label="Find a charge" placeholder="Find a charge" value={query} autoFocus
            onChange={(e) => setQuery(e.target.value)} />
          {rest.length === 0 ? (
            <p className="px-1 py-2 text-xs text-muted-foreground">{charges.length === 0 ? 'No account charges yet.' : 'No other charges.'}</p>
          ) : (
            <ul aria-label="Charges to add" className="max-h-64 overflow-y-auto">
              {rest.map((c) => (
                <li key={c.chargeId}>
                  <button type="button" className="w-full rounded-md px-2 py-1.5 text-left text-sm hover:bg-hover"
                    onClick={() => { onChange([...value, c.chargeId]); setAdding(false); setQuery(''); }}>
                    {c.name}
                    <span className="block text-xs text-muted-foreground">{chargeLine(c)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="flex items-center gap-2">
            {extra}
            <Button type="button" size="sm" variant="ghost" onClick={() => { setAdding(false); setQuery(''); }}>Done</Button>
          </div>
        </div>
      ) : (
        <Button type="button" size="sm" variant="secondary" onClick={() => setAdding(true)}>
          <Plus className="size-4" /> Add charge
        </Button>
      )}
    </div>
  );
}
