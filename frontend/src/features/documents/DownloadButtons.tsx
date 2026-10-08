import { Download } from 'lucide-react';
import { useState } from 'react';
import type { DocumentFormat } from '@/api/documents';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';

/** "Download PDF" and "Download Excel" for one customer document; the reader chooses. */
export function DownloadButtons({ what, onDownload }: { what: string; onDownload: (format: DocumentFormat) => Promise<void> }) {
  const [busy, setBusy] = useState<DocumentFormat | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  async function run(format: DocumentFormat) {
    setBusy(format);
    setError(null);
    try {
      await onDownload(format);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-1">
      <Button size="sm" variant="ghost" pending={busy === 'pdf'} aria-label={`Download the ${what} as PDF`}
        onClick={() => void run('pdf')}>
        <Download />
        PDF
      </Button>
      <Button size="sm" variant="ghost" pending={busy === 'xlsx'} aria-label={`Download the ${what} as Excel`}
        onClick={() => void run('xlsx')}>
        <Download />
        Excel
      </Button>
      {error && <InlineError error={error} />}
    </div>
  );
}
