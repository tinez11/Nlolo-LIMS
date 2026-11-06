import { useState } from 'react';
import { useAuth } from 'react-oidc-context';
import type { CommissionStatementView } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { StaffName } from '@/components/StaffName';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatMoney } from '@/lib/money';
import { barredAsOwn } from '@/lib/ownWork';
import { useDistributionStore } from '@/store/distributionStore';

/**
 * A statement asked to be paid and waiting for a second person (money movement step 4): whom it pays and who asked,
 * then Release or Refuse for someone holding payouts.approve. The person who asked is barred unless they hold
 * payouts.approve-own, as the server is.
 */
export function PayoutDecision({ agentId, statement }: { agentId: string; statement: CommissionStatementView }) {
  const statementId = statement.statementId ?? '';
  const decide = useDistributionStore((s) => s.decidePayout);
  const deciding = useDistributionStore((s) => s.decidingPayout[statementId]);
  const auth = useAuth();
  const viewer = readIdentity(auth.user?.access_token)?.subject;
  const [armed, setArmed] = useState(false);
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState('');
  const own = barredAsOwn(viewer, statement.payoutRequestedBy, 'payouts');
  const busy = deciding?.status === 'loading';
  const payee = statement.payeeRef ?? 'the agent';

  return (
    <div className="mt-2 space-y-2 border-t border-border pt-2">
      <p className="text-xs text-muted-foreground">
        Payout asked to {payee}
        {statement.payoutRequestedBy && <> by <StaffName subject={statement.payoutRequestedBy} /></>} · waiting for a
        second person to release it
      </p>
      {can('payouts.approve') && (
        <>
          {deciding?.status === 'error' && deciding.error && <InlineError error={deciding.error} />}
          {own && <p className="text-xs text-muted-foreground">You asked for this payout, so someone else must release it.</p>}
          {armed ? (
            <ConfirmAct
              heading="Release this commission payout?"
              consequence={
                <>
                  <strong>{formatMoney(statement.totalAmount)}</strong>, less any tax withheld, is paid to {payee}.
                </>
              }
              reversal="If the payment fails, the statement can be asked to pay again."
              confirmLabel="Release payout"
              tone="danger"
              busy={busy}
              onConfirm={() => {
                void decide(agentId, statementId, null);
                setArmed(false);
              }}
              onCancel={() => setArmed(false)}
            />
          ) : refusing ? (
            <div className="space-y-2">
              <FormField required label="Why is it refused?">
                <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
              </FormField>
              <div className="flex gap-2">
                <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                  onClick={() => void decide(agentId, statementId, reason.trim())}>
                  Refuse payout
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRefusing(false)}>
                  Cancel
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">Nothing has been paid; the statement can be asked to pay again.</p>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={own} onClick={() => setArmed(true)}>
                Release
              </Button>
              <Button size="sm" variant="ghost" disabled={own} onClick={() => setRefusing(true)}>
                Refuse
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
