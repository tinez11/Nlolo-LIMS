import { Download } from 'lucide-react';
import { useState } from 'react';
import { downloadQuotation, type QuotationRequest } from '@/api/documents';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { saveBlob } from '@/lib/download';

/**
 * The quotation PDF (2026-10-16) -- for the applicant to sign and keep, before any case exists.
 *
 * <p>Sends the form's inputs, never the figures on screen: the server quotes again from the applicant's own record,
 * because a document someone signs has to carry the premium the policy will charge. Nothing is stored by asking.
 */
export function DownloadQuotationButton({ request }: { request: QuotationRequest | null }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function run() {
    if (!request) return;
    setBusy(true);
    setError(null);
    try {
      saveBlob(await downloadQuotation(request), `quotation-${request.productVersionId.slice(0, 8)}.pdf`);
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
        disabled={!request}
        aria-label="Download the quotation as PDF"
        onClick={() => void run()}
      >
        <Download />
        Quotation (PDF)
      </Button>
      <span className="text-xs text-muted-foreground">The figures above, for the applicant to sign and keep.</span>
      {error && <InlineError error={error} />}
    </div>
  );
}
