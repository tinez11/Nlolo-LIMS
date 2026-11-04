import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import type { BalanceWithdrawalView } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { PayoutRouteFields } from '@/features/payments/PayoutRouteFields';
import { methodWords } from '@/features/payments/paymentWords';
import {
  BLANK_ROUTE,
  describeRoute,
  payoutRouteSchema,
  routeBody,
  type PayoutRouteValues,
} from '@/features/payments/payoutRoute';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { barredAsOwn } from '@/lib/ownWork';
import { usePolicyStore } from '@/store/policyStore';

/** Still waiting on a person or the rail; the rest is history the Statement already tells. */
const WAITING: readonly BalanceWithdrawalView['status'][] = ['AWAITING_PAYEE', 'REQUESTED', 'APPROVED'];
const NONE: BalanceWithdrawalView[] = [];

function waitingFor(w: BalanceWithdrawalView): string {
  if (w.status === 'AWAITING_PAYEE') return 'the policy ended with money on its balance; waiting for finance to name who receives it';
  if (w.status === 'REQUESTED') return 'waiting for a second person to approve it';
  if (w.method === 'EFT') return 'taken from the balance; waiting for finance to make the bank transfer';
  if (w.method === 'CHEQUE') return 'taken from the balance; waiting for finance to write the cheque';
  return 'taken from the balance; waiting for the mobile-money provider to confirm the payment';
}

/**
 * Withdrawals from the policy's balance still in progress (money movement step 3a): one asked waits for a second person
 * (payouts.approve) -- or the person who asked, holding payouts.approve-own -- and one the platform raised when the
 * policy ended waits for finance (payouts.manage) to name who receives it.
 */
export function BalanceWithdrawals({ policyNumber }: { policyNumber: string }) {
  const withdrawals = usePolicyStore((s) => s.balanceWithdrawals[policyNumber]);
  const load = usePolicyStore((s) => s.loadBalanceWithdrawals);

  useEffect(() => {
    void load(policyNumber);
  }, [policyNumber, load]);

  const waiting = (withdrawals?.data ?? NONE).filter((w) => WAITING.includes(w.status));
  if (waiting.length === 0) return null;
  return (
    <section aria-label="Withdrawals in progress" className="divide-y divide-border rounded-lg border border-border">
      {waiting.map((w) => (
        <WithdrawalRow key={w.withdrawalId} withdrawal={w} />
      ))}
    </section>
  );
}

function WithdrawalRow({ withdrawal }: { withdrawal: BalanceWithdrawalView }) {
  const decide = usePolicyStore((s) => s.decideBalanceWithdrawal);
  const acting = usePolicyStore((s) => s.actingOnWithdrawal[withdrawal.withdrawalId]);
  const auth = useAuth();
  const viewer = readIdentity(auth.user?.access_token)?.subject;
  const [armed, setArmed] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const own = barredAsOwn(viewer, withdrawal.requestedBy, 'payouts');
  const busy = acting?.status === 'loading';
  const to = withdrawal.method ? describeRoute(withdrawal) : null;

  return (
    <div className="space-y-2 px-4 py-3">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="text-sm font-medium">Withdrawal</span>
        <span className="text-sm font-medium tabular-nums">{formatMoney(withdrawal.amount)}</span>
        <StatusBadge kind="balanceWithdrawal" value={withdrawal.status} />
      </div>
      <p className="text-xs text-muted-foreground">
        {to && withdrawal.method && <>{methodWords(withdrawal.method)} to {to} · </>}
        {withdrawal.reason === 'ASKED' && <>asked by <StaffName subject={withdrawal.requestedBy} /> · </>}
        {waitingFor(withdrawal)}
      </p>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}

      {withdrawal.status === 'AWAITING_PAYEE' && can('payouts.manage') && (
        <PayeeForm policyNumber={withdrawal.policyNumber} withdrawalId={withdrawal.withdrawalId} busy={busy} />
      )}

      {withdrawal.status === 'REQUESTED' && can('payouts.approve') && (
        <>
          {own && <p className="text-xs text-muted-foreground">You asked for this withdrawal, so someone else must approve it.</p>}
          {armed ? (
            <ConfirmAct
              heading="Approve this withdrawal?"
              consequence={
                <>
                  <strong>{formatMoney(withdrawal.amount)}</strong> leaves the balance now and is paid
                  {to ? <> to {to}</> : null}.
                </>
              }
              reversal="If the payment fails, the money goes back on the balance."
              confirmLabel={`Pay ${formatMoney(withdrawal.amount)}`}
              tone="danger"
              busy={busy}
              onConfirm={() => {
                void decide(withdrawal.policyNumber, withdrawal.withdrawalId, null);
                setArmed(false);
              }}
              onCancel={() => setArmed(false)}
            />
          ) : rejecting ? (
            <div className="space-y-2">
              <FormField label="Why is it rejected?">
                <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
              </FormField>
              <div className="flex gap-2">
                <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                  onClick={() => void decide(withdrawal.policyNumber, withdrawal.withdrawalId, reason.trim())}>
                  Reject withdrawal
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
                  Cancel
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">Nothing has left the balance, so nothing is reversed.</p>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={own} onClick={() => setArmed(true)}>
                Approve
              </Button>
              <Button size="sm" variant="ghost" disabled={own} onClick={() => setRejecting(true)}>
                Reject
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** Who receives a withdrawal the platform raised when the policy ended; a second person then approves it. */
function PayeeForm({ policyNumber, withdrawalId, busy }: { policyNumber: string; withdrawalId: string; busy: boolean }) {
  const name = usePolicyStore((s) => s.nameBalanceWithdrawalPayee);
  const form = useForm<PayoutRouteValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(payoutRouteSchema),
    defaultValues: BLANK_ROUTE,
  });
  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="Name the payee"
      onSubmit={form.handleSubmit((values) => void name(policyNumber, withdrawalId, routeBody(values)))}
    >
      <PayoutRouteFields form={form} />
      <Button type="submit" size="sm" disabled={busy}>
        Name the payee
      </Button>
    </form>
  );
}
