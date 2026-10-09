import { Download } from 'lucide-react';
import { useState } from 'react';
import { downloadDepositSchedule } from '@/api/documents';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { saveBlob } from '@/lib/download';

/** A fixed-term deposit's schedule as a PDF (2026-10-09) -- for staff to send, or the customer to keep. */
export function DepositScheduleDownload({ policyNumber }: { policyNumber: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      saveBlob(await downloadDepositSchedule(policyNumber), `deposit-schedule-${policyNumber}.pdf`);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button size="sm" variant="ghost" pending={busy} aria-label="Download the deposit schedule as PDF" onClick={() => void run()}>
        <Download />
        Deposit schedule (PDF)
      </Button>
      {error && <InlineError error={error} />}
    </div>
  );
}
