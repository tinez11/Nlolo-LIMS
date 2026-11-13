import { useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { decideTreaty } from '@/api/reinsurance';
import type { TreatyView } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { StaffName } from '@/components/StaffName';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { barredAsOwn, SIGNING_OWN } from '@/lib/ownWork';

/**
 * A new treaty cedes nothing until a second person approves it (2026-11-12, finance finding 170: it moves premium to
 * an outside company every month). Its proposer approves alone only holding reinsurance.approve-own; anyone may
 * withdraw their own.
 */
export function TreatyDecision({ treaty, onDecided }: { treaty: TreatyView; onDecided: () => void }) {
  const viewer = readIdentity(useAuth().user?.access_token)?.subject ?? undefined;
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const mine = viewer != null && viewer === treaty.createdBy;
  const barred = barredAsOwn(viewer, treaty.createdBy, 'reinsurance');

  async function decide(rejectReason: string | null) {
    setBusy(true);
    setError(null);
    try {
      await decideTreaty(treaty.treatyId, rejectReason);
      onDecided();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2 border-t border-border px-4 py-3">
      <p className="text-xs text-muted-foreground">
        Proposed by <StaffName subject={treaty.createdBy} />. It cedes nothing until a second person approves it.
      </p>
      {error && <InlineError error={error} />}
      {can('reinsurance.approve') && (
        <>
          {barred && <p className="text-xs text-muted-foreground">You proposed this treaty, so someone else must approve it.</p>}
          {mine && !barred && <p className="text-xs text-muted-foreground">{SIGNING_OWN}</p>}
          {rejecting ? (
            <div className="space-y-2">
              <FormField required label="Why is it rejected?">
                <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
              </FormField>
              <div className="flex gap-2">
                <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                  onClick={() => void decide(reason.trim())}>
                  Reject treaty
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" disabled={barred || busy} pending={busy} onClick={() => void decide(null)}>
                Approve treaty
              </Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRejecting(true)}>
                {mine ? 'Withdraw' : 'Reject'}
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
