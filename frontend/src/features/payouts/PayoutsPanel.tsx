import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { PayoutInstalmentView, PayoutKind } from '@/api/types';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { StatusBadge } from '@/components/StatusBadge';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';

/** What each kind of payout IS, in a clerk's words rather than the enum's. */
const KIND_LABEL: Record<PayoutKind, string> = {
  SURVIVAL: 'Survival benefit',
  MATURITY: 'Maturity',
  INCOME: 'Income',
  RETURN_OF_PREMIUM: 'Premium return',
};

/**
 * One policy's whole payout schedule — every dated amount the contract owes while the life assured
 * is alive, written at issue (guide §16).
 *
 * The NO-PAGER shape is inherited: `GET /policies/{n}/payouts` returns a bare unpaged array, so the
 * whole schedule is on screen and there is nothing to page through. A twenty-year money-back plan
 * is a few dozen rows.
 *
 * Read-only on purpose. Reviewing and approving a payout is finance's, and it happens from the
 * payouts queue where the person doing it is working a list — not incidentally, while they happen
 * to have a policy open for some other reason. The row links there instead.
 */
export function PayoutsPanel({ policyNumber }: { policyNumber: string }) {
  const payouts = useBenefitPayoutStore((s) => s.byPolicy[policyNumber]);
  const load = useBenefitPayoutStore((s) => s.loadForPolicy);

  useEffect(() => {
    void load(policyNumber);
  }, [policyNumber, load]);

  if (!payouts || isInitialLoad(payouts)) return <LoadingBlock />;
  if (payouts.status === 'error' && payouts.error && payouts.data === null) {
    return <ErrorPanel error={payouts.error} onRetry={() => void load(policyNumber)} />;
  }

  const rows = payouts.data ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        title="No payouts scheduled"
        description="This policy's product pays nothing while the life assured is alive. Term and whole-life cover pays on death; an endowment or money-back plan carries a schedule."
      />
    );
  }

  return (
    <div className="divide-y divide-border">
      {rows.map((payout) => (
        <PayoutRow key={payout.instalmentId} payout={payout} />
      ))}
    </div>
  );
}

function PayoutRow({ payout }: { payout: PayoutInstalmentView }) {
  // A restatement means a paid-up conversion shrank it. BOTH figures are shown: the current one is
  // what will be paid, and hiding what the contract originally promised would make a reduced
  // benefit look like the agreed one.
  const restated =
    payout.restatementReason != null
    && payout.originalAmount?.amount !== payout.currentAmount?.amount;

  return (
    <div className="px-4 py-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <span className="text-sm font-medium">{KIND_LABEL[payout.kind]}</span>
          <span className="ml-2">
            <StatusBadge kind="payoutInstalment" value={payout.status} />
          </span>
          <p className="text-xs text-muted-foreground">Due {formatDate(payout.dueDate)}</p>
        </div>
        <div className="shrink-0 text-right">
          <span className="text-sm font-medium">
            {/* A premium return has no amount until it falls due: it is a percentage of what was
                actually collected, which is not a settled figure before then. */}
            {payout.currentAmount ? formatMoney(payout.currentAmount) : 'Valued when due'}
          </span>
          {restated && (
            <p className="text-xs text-muted-foreground">
              reduced from {formatMoney(payout.originalAmount)}
            </p>
          )}
        </div>
      </div>

      {/* Why it is held or cancelled, in the server's own words. Paraphrasing would give a clerk
          and the person who wrote the rule two different accounts of the same decision. */}
      {payout.statusReason && (
        <p className="mt-1 text-xs text-muted-foreground">{payout.statusReason}</p>
      )}
      {restated && payout.restatementReason && (
        <p className="mt-1 text-xs text-muted-foreground">{payout.restatementReason}</p>
      )}

      <p className="mt-1">
        <Link
          to={`/payouts/${encodeURIComponent(payout.instalmentId)}`}
          className="text-xs underline underline-offset-2"
        >
          Open payout
        </Link>
      </p>
    </div>
  );
}
