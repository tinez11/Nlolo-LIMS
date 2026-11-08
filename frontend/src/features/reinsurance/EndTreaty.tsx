import { useState } from 'react';
import { endTreaty } from '@/api/reinsurance';
import type { TreatyView } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import type { ApiError } from '@/lib/apiError';
import { formatDate, todayIso } from '@/lib/dates';

/**
 * Ends a treaty early (2026-11-08): the last day it takes new policies, and the last month its bordereaux bill.
 * Each policy is ceded to one treaty, so a new treaty can only start once the one in force has an end.
 */
export function EndTreaty({ treaty, onEnded }: { treaty: TreatyView; onEnded: () => void }) {
  const [open, setOpen] = useState(false);
  const [endsOn, setEndsOn] = useState(todayIso());
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function end() {
    setBusy(true);
    setError(null);
    try {
      await endTreaty(treaty.treatyId, endsOn);
      setOpen(false);
      onEnded();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
      setArmed(false);
    }
  }

  if (!open) {
    return (
      <div className="px-4 pb-3">
        <Button size="sm" variant="outline" onClick={() => setOpen(true)}>End treaty</Button>
      </div>
    );
  }
  return (
    <div className="space-y-2 px-4 pb-3">
      <FormField required label="Last day it takes new policies" hint="Today at the earliest.">
        <DatePicker value={endsOn} onChange={(iso) => iso && setEndsOn(iso)} />
      </FormField>
      {error && <InlineError error={error} />}
      {armed ? (
        <ConfirmAct
          heading="End this treaty?"
          tone="danger"
          consequence={
            <>
              No policy issued after <strong>{formatDate(endsOn)}</strong> is ceded to {treaty.reinsurerName}, and its
              bordereaux stop after that month — premium and commission included.
            </>
          }
          reversal="An end date can be brought forward later, never pushed back."
          confirmLabel="End treaty"
          busy={busy}
          onConfirm={() => void end()}
          onCancel={() => setArmed(false)}
        />
      ) : (
        <div className="flex gap-2">
          <Button size="sm" onClick={() => setArmed(true)} disabled={!endsOn}>Continue</Button>
          <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
        </div>
      )}
    </div>
  );
}
