import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

const VIEWS = [
  { key: 'instalments', label: 'Benefits due', to: '/staff/payouts' },
  { key: 'all', label: 'All payments out', to: '/staff/payouts/all' },
] as const;

/**
 * Payouts, two ways (money movement 6d, design §5.1): the instalments benefitpayout owes living policyholders, and
 * every payout the rail has made or tried -- claims, withdrawals, loans, commission, refunds -- by the payments
 * register's money out. Links, not toggles: each view is its own page with its own URL. Drawn like the filter chips
 * so it reads as part of the same toolbar family.
 */
export function PayoutViews({ current }: { current: (typeof VIEWS)[number]['key'] }) {
  return (
    <nav aria-label="Paying out views" className="mb-3 flex flex-wrap gap-1.5">
      {VIEWS.map((v) => (
        <Link
          key={v.key}
          to={v.to}
          aria-current={current === v.key ? 'page' : undefined}
          className={cn(
            'inline-flex min-h-8 items-center rounded-full px-3 text-xs transition-colors active:duration-0 pointer-coarse:min-h-11',
            current === v.key
              ? 'bg-accent text-accent-foreground'
              : 'bg-control hover:bg-control-hover active:bg-control-press',
          )}
        >
          {v.label}
        </Link>
      ))}
    </nav>
  );
}
