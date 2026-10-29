import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { Field } from '@/components/Field';
import { PartyName } from '@/components/PartyName';
import { PolicyProduct } from '@/components/PolicyProduct';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { AdjustmentRow, WithdrawalRow } from '@/features/accounts/AccountPanel';
import { formatMoney } from '@/lib/money';
import { fromList } from '@/lib/returnCrumbs';
import { useAccumulationStore } from '@/store/accumulationStore';
import { useApprovalsStore } from '@/store/approvalsStore';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectDetail, usePolicyStore } from '@/store/policyStore';

/**
 * A savings-account withdrawal or adjustment, reviewed and signed from the approvals inbox (2026-10-29, the user: finance
 * should not have to open each policy to approve). The policy's own facts lead -- who holds it, the product, the
 * account's balance and state -- then the request itself, drawn by the same row the policy's Account tab uses, so its
 * checks, its confirmation and its two-person rule are the ones on the policy, not a second copy of them.
 */
export function AccountApprovalReview({
  kind,
  policyNumber,
  reference,
  onClose,
}: {
  kind: 'account-withdrawal' | 'account-adjustment';
  policyNumber: string;
  reference: string;
  onClose: () => void;
}) {
  const loadDetail = usePolicyStore((s) => s.loadDetail);
  const policy = usePolicyStore(selectDetail(policyNumber));
  const loadAccount = useAccumulationStore((s) => s.loadAccount);
  const loadMovements = useAccumulationStore((s) => s.loadMovements);
  const account = useAccumulationStore((s) => s.account[policyNumber]);
  const withdrawals = useAccumulationStore((s) => s.withdrawals[policyNumber]);
  const adjustments = useAccumulationStore((s) => s.adjustments[policyNumber]);
  const loadApprovals = useApprovalsStore((s) => s.loadApprovals);
  const viewerSubject = readIdentity(useAuth().user?.access_token)?.subject ?? undefined;
  const isFinance = can('accounts.approve');

  useEffect(() => {
    void loadDetail(policyNumber);
    void loadAccount(policyNumber);
    void loadMovements(policyNumber);
  }, [policyNumber, loadDetail, loadAccount, loadMovements]);

  const withdrawal = withdrawals?.data?.find((w) => w.withdrawalId === reference) ?? null;
  const adjustment = adjustments?.data?.find((a) => a.adjustmentId === reference) ?? null;
  const decided =
    kind === 'account-withdrawal' ? withdrawal !== null && withdrawal.status !== 'REQUESTED' : adjustment !== null && adjustment.status !== 'PROPOSED';

  // Once decided, the inbox's own count and rows are stale: reread them.
  useEffect(() => {
    if (decided) void loadApprovals();
  }, [decided, loadApprovals]);

  const loading =
    // Not on the policy record: its holder and product fill in when it lands, and the request does not wait for them.
    !account || isInitialLoad(account) || (kind === 'account-withdrawal' ? !withdrawals || isInitialLoad(withdrawals) : !adjustments || isInitialLoad(adjustments));

  return (
    <section aria-label={`Review ${policyNumber}`} className="space-y-3 border-b border-border bg-band/40 px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold">
          {kind === 'account-withdrawal' ? 'Withdrawal' : 'Adjustment'} on {policyNumber}
        </h3>
        <div className="flex items-center gap-1">
          <Button asChild size="sm" variant="ghost">
            <Link to={fromList(`/staff/policies/${encodeURIComponent(policyNumber)}?tab=account`, 'approvals')}>Open the policy</Link>
          </Button>
          <Button size="sm" variant="ghost" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>

      {account?.status === 'error' && account.error && !account.data ? (
        <ErrorPanel error={account.error} onRetry={() => void loadAccount(policyNumber)} />
      ) : loading ? (
        <LoadingBlock label="Loading the policy" />
      ) : (
        <>
          {/* The facts a signature rests on: whose money, on what product, and what the account holds now. */}
          <dl className="grid grid-cols-1 gap-x-6 rounded-lg border border-border bg-surface px-4 py-1 sm:grid-cols-2">
            <Field
              label="Policyholder"
              value={policy.data?.policyholderPartyId ? <PartyName partyId={policy.data.policyholderPartyId} /> : '—'}
            />
            <Field label="Product" value={policy.data ? <PolicyProduct policy={policy.data} /> : '—'} />
            <Field label="Account balance" value={account?.data ? formatMoney(account.data.balance) : '—'} emphasis />
            <Field
              label="Account"
              value={account?.data ? <StatusBadge kind="savingsAccount" value={account.data.status} /> : '—'}
            />
            {account?.data && <Field label="Paid in" value={formatMoney(account.data.totals.paidIn)} />}
            {account?.data && <Field label="Paid out" value={formatMoney(account.data.totals.paidOut)} />}
          </dl>

          <div className="overflow-hidden rounded-lg border border-border bg-surface">
            {kind === 'account-withdrawal' && withdrawal && (
              <WithdrawalRow withdrawal={withdrawal} viewerSubject={viewerSubject} isFinance={isFinance} />
            )}
            {kind === 'account-adjustment' && adjustment && (
              <AdjustmentRow adjustment={adjustment} viewerSubject={viewerSubject} isFinance={isFinance} />
            )}
            {((kind === 'account-withdrawal' && !withdrawal) || (kind === 'account-adjustment' && !adjustment)) && (
              <p className="px-4 py-3 text-xs text-muted-foreground">
                This request is no longer waiting on the account — it may have been decided by someone else.
              </p>
            )}
          </div>
        </>
      )}
    </section>
  );
}
