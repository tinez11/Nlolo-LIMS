import { useEffect, useState } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { getReceiptByNumber } from '@/api/receipts';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';

/**
 * A receipt by the number a customer quotes (receipts step 1, 2026-11-05): looked up, then opened as the receipt page
 * itself, so "Go to… RC-2026-000014" lands where every other road to that money lands.
 */
export function ReceiptByNumberPage() {
  const { receiptNumber = '' } = useParams();
  const [found, setFound] = useState<{ number: string; id: string } | null>(null);
  const [error, setError] = useState<{ number: string; error: ApiError } | null>(null);

  useEffect(() => {
    let live = true;
    getReceiptByNumber(receiptNumber).then(
      (r) => { if (live) setFound({ number: receiptNumber, id: r.receiptId }); },
      (e: unknown) => { if (live) setError({ number: receiptNumber, error: toApiError(e) }); },
    );
    return () => { live = false; };
  }, [receiptNumber]);

  if (found?.number === receiptNumber) return <Navigate replace to={`/staff/receipts/${encodeURIComponent(found.id)}`} />;
  if (error?.number === receiptNumber) {
    return (
      <>
        <PageHeader title={`Receipt ${receiptNumber}`} />
        <div className="px-6">
          <ErrorPanel error={error.error} />
        </div>
      </>
    );
  }
  return <LoadingBlock label={`Finding receipt ${receiptNumber}`} />;
}
