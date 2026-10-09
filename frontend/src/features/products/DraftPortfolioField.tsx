import { useState } from 'react';
import { changeProductPortfolio } from '@/api/accountCharges';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { PORTFOLIO_CODES, PORTFOLIO_LABEL } from '@/lib/ifrs17';

/**
 * Correct a DRAFT product's IFRS 17 portfolio (2026-10-09). A product that keeps a savings account is refused at publish
 * unless it is SAV, DEP or PEN/DANN; this is how one created in the defaulted portfolio is put right. Once a version is
 * published the portfolio is fixed -- its policies are classified by it.
 */
export function DraftPortfolioField({ productId, current, onChanged }: {
  productId: string; current: string | undefined; onChanged: () => void;
}) {
  const [value, setValue] = useState(current ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await changeProductPortfolio(productId, value);
      onChanged();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-2 py-2">
      <div className="flex flex-wrap items-end gap-2">
        <FormField label="IFRS 17 portfolio (draft — can still be changed)" className="min-w-56 flex-1">
          <Select value={value} onChange={(e) => setValue(e.target.value)}>
            {PORTFOLIO_CODES.map((p) => <option key={p} value={p}>{PORTFOLIO_LABEL[p]}</option>)}
          </Select>
        </FormField>
        <Button size="sm" disabled={value === current} pending={saving} onClick={() => void save()}>Change</Button>
      </div>
      {error && <InlineError error={error} lead="The portfolio was not changed" />}
    </div>
  );
}
