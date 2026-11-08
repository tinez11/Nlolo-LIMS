import type { PolicyView } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { isDepositAccount } from './policyValue';

/**
 * A policy's sum assured in a list column (2026-11-08): a savings account or fixed deposit has none -- its figure is
 * the opening deposit, and it pays the account value on death -- so it reads as a dash, and says why to a screen reader.
 */
export function CoverAmount({ policy }: { policy: Pick<PolicyView, 'portfolioCode' | 'sumAssured'> }) {
  if (isDepositAccount(policy)) {
    return (
      <span className="text-muted-foreground" title="A savings account: no cover above its value">
        —<span className="sr-only">No cover above the account value</span>
      </span>
    );
  }
  return <>{formatMoney(policy.sumAssured)}</>;
}
