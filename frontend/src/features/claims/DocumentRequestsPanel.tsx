import { useEffect, useState } from 'react';
import {
  listDocumentRequests,
  requestClaimDocument,
  withdrawDocumentRequest,
  type ClaimDocumentRequestView,
} from '@/api/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';

const STATUS_TEXT: Record<string, string> = { OPEN: 'Waiting', RECEIVED: 'Received', WITHDRAWN: 'Withdrawn' };

/**
 * Documents asked of the claimant (2026-10-08, the customer portal design step 4). An assessor or manager names what is
 * needed and why; the claimant sees it on their claim in the portal and answers by uploading it there, which marks it
 * received here. The reason is shown to the claimant, so it is written to them.
 */
export function DocumentRequestsPanel({ claimId, canRequest }: { claimId: string; canRequest: boolean }) {
  const [requests, setRequests] = useState<ClaimDocumentRequestView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [document, setDocument] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    listDocumentRequests(claimId).then(
      (r) => { if (live) { setRequests(r); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [claimId, reload]);

  async function run(key: string, action: () => Promise<unknown>) {
    setBusy(key);
    setActionError(null);
    try {
      await action();
      setReload((n) => n + 1);
      return true;
    } catch (e) {
      setActionError(toApiError(e));
      return false;
    } finally {
      setBusy(null);
    }
  }

  async function ask() {
    const ok = await run('ask', () => requestClaimDocument(claimId, document.trim(), reason.trim() || null));
    if (ok) { setDocument(''); setReason(''); }
  }

  if (error) return <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />;
  if (!requests) return <LoadingBlock />;

  return (
    <div className="space-y-3">
      {canRequest && (
        <div className="flex flex-wrap items-end gap-2 px-4 pt-3">
          <FormField label="Document" className="min-w-48 flex-1">
            <Input value={document} onChange={(e) => setDocument(e.target.value)} placeholder="e.g. Burial permit" />
          </FormField>
          <FormField label="Why (the claimant reads this)" className="min-w-48 flex-[2]">
            <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          </FormField>
          <Button variant="primary" disabled={!document.trim()} pending={busy === 'ask'} onClick={() => void ask()}>
            Request document
          </Button>
        </div>
      )}
      {actionError && <div className="px-4"><InlineError error={actionError} lead="Could not update the request" /></div>}
      {requests.length === 0 ? (
        <p className="px-4 pb-3 text-xs text-muted-foreground">Nothing has been asked of the claimant.</p>
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {requests.map((r) => (
            <li key={r.requestId} className="flex items-center justify-between gap-3 px-4 py-2.5">
              <div className="min-w-0">
                <p className="text-sm">{r.document} <span className="text-xs text-muted-foreground">· {STATUS_TEXT[r.status] ?? r.status}</span></p>
                {r.reason && <p className="text-xs text-muted-foreground">{r.reason}</p>}
                <p className="text-xs text-subtle-foreground">
                  Asked by {r.requestedByName?.trim() || 'staff'} · {formatInstant(r.requestedAt)}
                  {r.receivedAt && ` · received ${formatInstant(r.receivedAt)}`}
                </p>
              </div>
              {canRequest && r.status === 'OPEN' && (
                <Button size="sm" variant="ghost" pending={busy === r.requestId}
                  onClick={() => void run(r.requestId, () => withdrawDocumentRequest(claimId, r.requestId))}>
                  Withdraw
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
