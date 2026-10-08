import { Download } from 'lucide-react';
import { useState } from 'react';
import { downloadPolicySchedule } from '@/api/documents';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { saveBlob } from '@/lib/download';

/** The policy schedule PDF (2026-10-08, customer portal step 3) -- for staff to print or send to the client. */
export function PolicyScheduleDownload({ policyNumber }: { policyNumber: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      saveBlob(await downloadPolicySchedule(policyNumber), `policy-schedule-${policyNumber}.pdf`);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 px-4 py-3">
      <Button size="sm" variant="ghost" pending={busy} aria-label="Download the policy schedule as PDF" onClick={() => void run()}>
        <Download />
        Policy schedule (PDF)
      </Button>
      <span className="text-xs text-muted-foreground">What the policy covers, who it pays and its premium, on one page.</span>
      {error && <InlineError error={error} />}
    </div>
  );
}
