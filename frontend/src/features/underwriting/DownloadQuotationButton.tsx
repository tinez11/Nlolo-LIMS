import { Download } from 'lucide-react';
import { useState } from 'react';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { saveBlob } from '@/lib/download';

/**
 * A quotation PDF (2026-10-16) -- for the applicant, or an association, to sign and keep before anything exists.
 *
 * <p>{@code download} sends the form's inputs, never the figures on screen: the server quotes again from the
 * applicant's own record, because a document someone signs has to carry the premium the policy will charge. Null
 * until there is enough to ask. Nothing is stored by asking. Shared by the individual quotation and the group funeral
 * scheme's (2026-10-19), so the two download the same way.
 */
export function DownloadQuotationButton({
  download,
  fileName,
  description = 'The figures above, for the applicant to sign and keep.',
}: {
  download: (() => Promise<Blob>) | null;
  fileName: string;
  description?: string | undefined;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function run() {
    if (!download) return;
    setBusy(true);
    setError(null);
    try {
      saveBlob(await download(), fileName);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 px-4 py-3">
      <Button
        size="sm"
        variant="ghost"
        pending={busy}
        disabled={!download}
        aria-label="Download the quotation as PDF"
        onClick={() => void run()}
      >
        <Download />
        Quotation (PDF)
      </Button>
      <span className="text-xs text-muted-foreground">{description}</span>
      {error && <InlineError error={error} />}
    </div>
  );
}
