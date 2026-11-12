import { ArrowDown, ArrowUp, ClipboardList, Coins, HeartPulse, TrendingDown } from 'lucide-react';
import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOverview, useDashboardStore } from '@/store/dashboardStore';
import type { PanelProps } from './DashboardPage';
import { describeRange } from './range';
import { versus } from './series';
import { StatRow, type Stat } from './StatTile';

/** The server's change as an arrow and words; growing is good news for both figures that carry one. */
function Change({ percent, against }: { percent: string | null | undefined; against: string }): ReactNode {
  if (!percent) return null;
  const down = percent.startsWith('-');
  return (
    <span data-delta={down ? 'bad' : 'good'} className="flex items-center gap-1">
      {down ? <ArrowDown className="size-3" aria-hidden /> : <ArrowUp className="size-3" aria-hidden />}
      {down ? `${percent.slice(1)}% down` : `${percent}% up`} {against}
    </span>
  );
}

/**
 * The management overview (2026-11-12, analytics plan 5): how the business is doing, on one tab. Three lit figures --
 * what came in, what was sold, what is on the books -- then four cards, each opening exactly what it counted. Every
 * figure is the server's, read there from the report that owns it; each is said once, and the months once, in the
 * subtitle (the user, 2026-11-12: "too much explanation and info").
 */
export function ManagementDashboardPanel({ range }: PanelProps) {
  const resource = useDashboardStore(selectOverview);
  const load = useDashboardStore((s) => s.load);
  const { from, to } = range;

  useEffect(() => {
    void load('overview', { from, to });
  }, [load, from, to]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Reading the business" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('overview', { from, to })} />;
  }
  const v = resource.data;
  if (!v) return null;

  const p = v.period;
  const against = versus(p);
  const collections = v.collections;

  // In full: a manager checks premiums against the bank to the shilling.
  const band: { label: string; value: string; note?: ReactNode; to: string }[] = [
    {
      label: 'Premiums collected',
      value: v.collected ? formatMoney(v.collected) : 'Not available',
      note: <Change percent={v.collectedChangePercent} against={against} />,
      to: '/staff/reports/premium-collections',
    },
    {
      label: 'New policies',
      value: countValue(v.newPolicies) ?? 'Not available',
      note: <Change percent={v.newPoliciesChangePercent} against={against} />,
      to: '/staff/reports/new-business',
    },
    {
      label: 'Policies in force',
      value: countValue(v.policiesInForce) ?? 'Not available',
      to: '/staff/reports/policies-in-force',
    },
  ];

  const waiting = v.casesToDecide == null || v.claimsToDecide == null ? null : v.casesToDecide + v.claimsToDecide;
  const stats: Stat[] = [
    {
      label: 'Collection rate',
      icon: Coins,
      color: 'chart-1',
      value: collections?.ratePercent ? `${collections.ratePercent}%` : null,
      ...(collections
        ? { hint: `${formatMoney({ amount: collections.overdueOwed.toFixed(2), currencyCode: collections.currency })} overdue` }
        : {}),
      to: '/staff/follow-up',
    },
    {
      label: 'Lapsed',
      icon: TrendingDown,
      color: 'chart-3',
      value: countValue(v.lapsed),
      to: '/staff/reports/policies-in-force',
    },
    {
      label: 'Claims paid',
      icon: HeartPulse,
      color: 'chart-2',
      value: v.claimsPaid ? formatMoney(v.claimsPaid) : null,
      ...(v.openClaims == null ? {} : { hint: `${countValue(v.openClaims)} still open` }),
      to: '/staff/reports/claims-in-progress',
    },
    {
      label: 'Waiting for a decision',
      icon: ClipboardList,
      color: 'chart-4',
      value: countValue(waiting),
      ...(waiting == null ? {} : { hint: `${countValue(v.casesToDecide)} proposals · ${countValue(v.claimsToDecide)} claims` }),
      to: '/staff/underwriting',
    },
  ];

  return (
    <div className="space-y-5">
      <Panel title="The business" subtitle={describeRange(p.from, p.to)}>
        <div className="px-4 pb-4">
          <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3">
            {band.map((b) => (
              <div key={b.label} className="flex min-w-0 flex-col gap-1">
                <dt className="text-sm text-muted-foreground">
                  <Link to={b.to} className="hover:text-foreground hover:underline">{b.label}</Link>
                </dt>
                <dd className="min-w-0 whitespace-nowrap text-display">{b.value}</dd>
                {b.note && <dd className="text-xs text-subtle-foreground">{b.note}</dd>}
              </div>
            ))}
          </dl>
        </div>
      </Panel>
      <StatRow stats={stats} />
    </div>
  );
}
