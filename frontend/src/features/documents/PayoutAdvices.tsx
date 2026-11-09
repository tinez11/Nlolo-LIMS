import { Download } from 'lucide-react';
import { useEffect, useState } from 'react';
import { downloadPayoutAdvice, listPayoutAdvices, type PayoutAdviceLine } from '@/api/documents';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { formatMoney } from '@/lib/money';

/**
 * Every payment made on a policy, each with its payout advice to download (2026-11-09, receipts step 4: the user did
 * not know where to find a receipt after money was paid out). The premium receipt's counterpart, for staff on the
 * policy's Billing tab and for the customer in their documents.
 */
export function PayoutAdvices({ policyNumber, emptyText = 'Nothing has been paid out on this policy yet.' }: {
  policyNumber: string;
  emptyText?: string;
}) {
  const [advices, setAdvices] = useState<PayoutAdviceLine[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [downloadError, setDownloadError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    listPayoutAdvices(policyNumber).then(
      (a) => { if (live) setAdvices(a); },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [policyNumber]);

  async function download(a: PayoutAdviceLine) {
    setDownloadError(null);
    try {
      saveBlob(await downloadPayoutAdvice(policyNumber, a.disbursementId), `${a.adviceNumber}.pdf`);
    } catch (e) {
      setDownloadError(toApiError(e));
    }
  }

  if (error) return <div className="px-4 pb-4"><InlineError lead="The payments could not be read" error={error} /></div>;
  if (advices === null) return <p className="px-4 pb-4 text-xs text-muted-foreground">Loading…</p>;
  if (advices.length === 0) return <p className="px-4 pb-4 text-sm text-muted-foreground">{emptyText}</p>;
  return (
    <div className="space-y-2 px-4 pb-3">
      {downloadError && <InlineError lead="The advice could not be produced" error={downloadError} />}
      <ul className="divide-y divide-border" aria-label="Payments made">
        {advices.map((a) => (
          <li key={a.disbursementId} className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-sm">
            <span className="min-w-0">
              <span className="block font-medium">
                {a.what} · {formatMoney({ amount: String(a.amount), currencyCode: a.currency })}
              </span>
              <span className="block text-xs text-muted-foreground">
                Paid {formatDate(a.paidOn)} by {a.method.toLowerCase()}{a.paidTo ? ` to ${a.paidTo}` : ''}
              </span>
              <span className="block text-xs text-subtle-foreground">{a.adviceNumber}</span>
            </span>
            <Button size="sm" variant="ghost" aria-label={`Download payout advice ${a.adviceNumber}`}
              onClick={() => void download(a)}>
              <Download />
              Advice
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
