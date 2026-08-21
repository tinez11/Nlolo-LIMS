'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useSubmitGuard } from '@/hooks/use-submit-guard';
import { mapApiError, type ApiProblem } from '@/lib/problem';

export type BeneficiaryInput = {
  partyId?: string;
  freeformDesignee?: string;
  sharePercentage: string;
};

/**
 * Sums share strings without floats: the backend requires shares to sum to EXACTLY 100%, and this
 * project's whole design philosophy (see lib/money.ts) treats decimal-like values as strings to
 * avoid float rounding. Each share is converted to integer "cents" (whole * 100 + fraction) and
 * those integers are summed -- `parseFloat`/`Number()` is never called on the COMBINED total, only
 * on the already-format-validated whole/fraction parts of a single row.
 */
function sharesSumToHundred(rows: BeneficiaryInput[]): boolean {
  let total = 0;
  for (const row of rows) {
    if (!/^\d+(\.\d{1,2})?$/.test(row.sharePercentage)) return false;
    const [whole, fraction = ''] = row.sharePercentage.split('.');
    total += Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  }
  return total === 10_000;
}

function emptyRow(): BeneficiaryInput {
  return { freeformDesignee: '', sharePercentage: '' };
}

export function BeneficiaryForm({
  initial, onSave,
}: { initial: BeneficiaryInput[]; onSave: (rows: BeneficiaryInput[]) => Promise<void> }) {
  const [rows, setRows] = useState(initial);
  const [error, setError] = useState<string | null>(null);

  const { submit, isSubmitting } = useSubmitGuard(async () => {
    const badExclusivity = rows.some(
      (r) => Boolean(r.partyId) === Boolean(r.freeformDesignee),
    );
    if (badExclusivity) {
      setError('Each beneficiary needs either a registered person or a name, not both.');
      return;
    }
    if (!sharesSumToHundred(rows)) {
      setError('Beneficiary shares must add up to 100%.');
      return;
    }
    setError(null);
    try {
      await onSave(rows);
    } catch (rejection) {
      const problem = (rejection as { problem?: ApiProblem }).problem ?? null;
      setError(mapApiError(problem));
    }
  });

  function updateRow(index: number, patch: Partial<BeneficiaryInput>) {
    setRows(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }

  return (
    <form onSubmit={(event) => { event.preventDefault(); void submit(); }} className="space-y-3">
      {rows.map((row, index) => (
        <div key={index} className="flex flex-wrap items-center gap-2">
          <Input
            aria-label={`Beneficiary ${index + 1} name`}
            placeholder="Name (freeform designee)"
            value={row.freeformDesignee ?? ''}
            onChange={(e) => updateRow(index, {
              freeformDesignee: e.target.value,
              partyId: e.target.value ? undefined : row.partyId,
            })}
          />
          <Input
            aria-label={`Beneficiary ${index + 1} party ID`}
            placeholder="Registered person (party ID)"
            value={row.partyId ?? ''}
            onChange={(e) => updateRow(index, {
              partyId: e.target.value,
              freeformDesignee: e.target.value ? undefined : row.freeformDesignee,
            })}
          />
          <Input
            aria-label={`Beneficiary ${index + 1} share`}
            placeholder="Share %"
            className="w-24"
            value={row.sharePercentage}
            onChange={(e) => updateRow(index, { sharePercentage: e.target.value })}
          />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={rows.length <= 1}
            onClick={() => setRows(rows.filter((_, i) => i !== index))}
          >
            Remove
          </Button>
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" onClick={() => setRows([...rows, emptyRow()])}>
        Add beneficiary
      </Button>
      {error && <p role="alert" className="text-destructive text-sm">{error}</p>}
      <div>
        <Button type="submit" disabled={isSubmitting}>Save beneficiaries</Button>
      </div>
    </form>
  );
}
