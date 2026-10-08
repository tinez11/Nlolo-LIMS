import { useState } from 'react';
import { recordAccidentalDeath } from '@/api/funeral';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';

/**
 * Whether a funeral claim's death was accidental (2026-10-08), and the switch for claims staff while the claim is
 * still open. A product may pay an accident inside its waiting period; the server refuses a natural death there with
 * "record the death as accidental if it was" -- and no screen could, except by declining and registering again.
 */
export function AccidentalDeathField({ claimId, accidental, canChange, onChanged }: {
  claimId: string;
  accidental: boolean;
  /** A claims assessor or manager, on a claim not yet decided. */
  canChange: boolean;
  onChanged: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function change() {
    setPending(true);
    setError(null);
    try {
      await recordAccidentalDeath(claimId, !accidental);
      onChanged();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <Field
        label="Cause of death"
        value={
          <span className="flex flex-wrap items-center justify-end gap-2">
            {accidental ? 'Accidental' : 'Natural'}
            {canChange && (
              <Button type="button" size="sm" variant="ghost" pending={pending} onClick={() => void change()}>
                {accidental ? 'Record as natural' : 'Record as accidental'}
              </Button>
            )}
          </span>
        }
        {...(accidental ? { note: 'Paid inside the waiting period where the product waives it for an accident' } : {})}
      />
      {error && <div className="pb-2"><InlineError error={error} /></div>}
    </>
  );
}
