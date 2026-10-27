import { can } from '@/auth/access';
import { Banknote, CalendarClock, FileText, Inbox } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Legend } from '@/components/charts/ChartTip';
import { ColumnChart } from '@/components/charts/ColumnChart';
import { Donut } from '@/components/charts/Donut';
import { ShareBars } from '@/components/charts/ShareBars';
import { TrendChart } from '@/components/charts/TrendChart';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { humanizeStatus } from '@/lib/status';
import { formatDate } from '@/lib/dates';
import { formatMoney, formatMoneyCompact } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaims, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import type { PanelProps } from './DashboardPage';
import { DashboardRail } from './DashboardRail';
import { describeRange } from './range';
import { countPoints, donutParts, monthCategories, moneyPoints, trendOf, versus } from './series';
import { StatRow, type Stat } from './StatTile';

const CLAIM_TYPE: Record<string, string> = {
  DEATH: 'Death',
  DISABILITY: 'Disability',
  CRITICAL_ILLNESS: 'Critical illness',
  MATURITY: 'Maturity',
};

/**
 * The claims dashboard (2026-10-14; ranged and charted 2026-10-23). Stages are a count of one status, now; what was
 * paid and how long it took are over the range, the server's own totals and averages. The queue is the reader's work.
 */
export function ClaimsDashboardPanel({ range }: PanelProps) {
  const resource = useDashboardStore(selectClaims);
  const load = useDashboardStore((s) => s.load);
  const { from, to } = range;

  useEffect(() => {
    void load('claims', { from, to });
  }, [load, from, to]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting claims" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('claims', { from, to })} />;
  }
  const view = resource.data;
  if (!view) return null;

  const p = view.period;
  const span = describeRange(p.from, p.to);
  const manager = view.queueStatus === 'SETTLEMENT_REQUESTED';
  const list = (status: string) => `/staff/claims?status=${status}`;
  const paid = moneyPoints(view.paidSeries);
  const categories = monthCategories(view.paidSeries.map((m) => m.month));

  const stats: Stat[] = [
    {
      label: manager ? 'Awaiting your decision' : 'Awaiting assessment',
      icon: Inbox,
      color: 'chart-2',
      value: countValue(manager ? view.settlementRequested : view.registered),
      hint: manager ? 'assessed and recommended' : 'registered, not yet assessed',
      to: list(manager ? 'SETTLEMENT_REQUESTED' : 'REGISTERED'),
    },
    {
      label: 'Registered',
      icon: FileText,
      color: 'chart-3',
      value: countValue(view.registeredInPeriod),
      hint: span,
      trend: view.registeredSeries.map((c) => c.count),
    },
    {
      label: 'Benefits paid',
      icon: Banknote,
      color: 'chart-1',
      value: view.paid ? formatMoneyCompact(view.paid) : null,
      full: formatMoney(view.paid),
      change: view.paidChangePercent,
      versus: versus(p),
      hint: span,
      trend: trendOf(paid),
      to: list('SETTLED'),
    },
    {
      label: 'Days to settle',
      icon: CalendarClock,
      color: 'chart-4',
      value: view.averageDaysToSettle === null ? null : `${view.averageDaysToSettle} days`,
      hint: `registration to payment, ${span}`,
    },
  ];

  const stages = [
    { status: 'REGISTERED', label: 'Registered, awaiting an assessor', n: view.registered, color: 'chart-2' as const },
    { status: 'UNDER_ASSESSMENT', label: 'Under assessment', n: view.underAssessment, color: 'chart-3' as const },
    { status: 'SETTLEMENT_REQUESTED', label: 'Awaiting a settlement decision', n: view.settlementRequested, color: 'chart-4' as const },
    { status: 'APPROVED', label: 'Approved, payout not made', n: view.approved, color: 'chart-1' as const },
    { status: 'REOPENED', label: 'Reopened', n: view.reopened, color: 'chart-5' as const },
  ];
  const next = view.queue[0];

  return (
    <div className="space-y-5">
      <StatRow stats={stats} />
      <div className="grid gap-5 xl:grid-cols-12">
        <div className="xl:col-span-8">
          <Panel
            title="Benefits paid"
            subtitle={`${span}, approved amounts of claims settled each month, TZS`}
            actions={<Link to={list('SETTLED')} className="text-xs text-muted-foreground hover:text-foreground hover:underline">Open settled claims</Link>}
          >
            <div className="px-4 pb-4">
              <TrendChart
                categories={categories}
                series={[{ label: 'Paid', color: 'chart-1', area: true, points: paid }]}
                description={`Benefits paid by month, ${span}. Total ${formatMoney(view.paid)}.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-4">
          <Panel title="Paid by cause" subtitle={span}>
            <div className="px-4 pb-4">
              {view.paidByType.length === 0 ? (
                <p className="text-xs text-muted-foreground">No claim was settled in these months.</p>
              ) : (
                <Donut
                  parts={donutParts(view.paidByType, (k) => CLAIM_TYPE[k] ?? humanizeStatus(k))}
                  total={formatMoneyCompact(view.paid).replace(/^TZS /, '')}
                  totalLabel="TZS paid"
                  description={`Benefits paid by claim type, ${span}.`}
                />
              )}
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-6">
          <Panel title="Registered and settled" subtitle={`${span}, claims by month`}>
            <div className="space-y-3 px-4 pb-4">
              <Legend items={[{ label: 'Registered', color: 'chart-2' }, { label: 'Settled', color: 'chart-1' }]} />
              <ColumnChart
                height={210}
                categories={categories}
                partialKey={p.to === p.currentMonth ? p.currentMonth : undefined}
                series={[
                  { label: 'Registered', color: 'chart-2', points: countPoints(view.registeredSeries).map((c) => ({ ...c, value: c.value ?? 0 })) },
                  { label: 'Settled', color: 'chart-1', points: countPoints(view.settledSeries).map((c) => ({ ...c, value: c.value ?? 0 })) },
                ]}
                description={`Claims registered and settled by month, ${span}: ${countValue(view.registeredInPeriod) ?? 'none'} registered, ${countValue(view.settledInPeriod) ?? 'none'} settled.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-6">
          <Panel title="Where open claims are" subtitle="As at now. Each opens its list.">
            <div className="px-4 pb-4">
              <ShareBars
                label="Open claims by stage"
                bars={stages.map((s) => ({
                  key: s.status,
                  label: s.label,
                  value: s.n ?? 0,
                  display: countValue(s.n) ?? '—',
                  color: s.color,
                }))}
              />
              <div className="mt-3 flex flex-wrap gap-2">
                {stages.filter((s) => s.n).map((s) => (
                  <Link key={s.status} to={list(s.status)} className="rounded-md bg-control px-2.5 py-1 text-xs font-medium hover:bg-control-hover">
                    {s.label}
                  </Link>
                ))}
              </div>
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-7">
          <Panel
            emphasis
            title={manager ? 'Awaiting your settlement decision' : 'Awaiting assessment'}
            subtitle={
              manager
                ? 'Oldest first. Approving pays; you cannot decide a claim you assessed.'
                : 'Oldest first. Registered claims nobody has assessed yet.'
            }
          >
            {view.queue.length === 0 ? (
              <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing waiting.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-band text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-2.5 font-medium">Claim</th>
                      <th className="px-4 py-2.5 font-medium">Policy</th>
                      <th className="px-4 py-2.5 font-medium">Claimant</th>
                      <th className="px-4 py-2.5 font-medium">Status</th>
                      <th className="px-4 py-2.5">
                        <span className="sr-only">Open</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.queue.map((c) => (
                      <tr key={c.claimId} className="h-11 border-t border-border first:border-t-0">
                        <td className="px-4">
                          {CLAIM_TYPE[c.claimType] ?? c.claimType}
                          {c.dateOfEvent && (
                            <span className="block text-xs text-muted-foreground">
                              event {formatDate(c.dateOfEvent)}
                            </span>
                          )}
                        </td>
                        <td className="px-4 font-mono text-xs">{c.policyNumber}</td>
                        <td className="px-4">
                          <PartyName partyId={c.claimantPartyId} />
                        </td>
                        <td className="px-4">
                          <StatusBadge kind="claim" value={c.status} />
                        </td>
                        <td className="px-4 text-right">
                          <Link
                            to={`/staff/claims/${c.claimId}`}
                            aria-label={`Open ${c.claimId}`}
                            className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                          >
                            {manager ? 'Decide' : 'Assess'}
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </div>
        <div className="xl:col-span-5">
          <DashboardRail
            alerts={[
              ...(view.approved
                ? [{ tone: 'warning' as const, text: <><b>{countValue(view.approved)} approved claims</b> have not been paid.</> }]
                : []),
              ...(view.openDocumentRequests
                ? [{ tone: 'calm' as const, text: <><b>{countValue(view.openDocumentRequests)} document requests</b> are still unanswered.</> }]
                : []),
            ]}
            actions={[
              ...(can('claims.register')
                ? [{ label: 'Register a claim', description: 'Identify the claimant, then the policy', to: '/staff/claims/new' }]
                : []),
              ...(next
                ? [
                    {
                      label: manager ? 'Decide the next settlement' : 'Assess the next claim',
                      description: 'The one that has waited longest',
                      to: `/staff/claims/${next.claimId}`,
                    },
                  ]
                : []),
              { label: 'Approved, not yet paid', description: 'Payouts still to make', to: list('APPROVED') },
            ]}
            absent={[]}
          />
        </div>
        <div className="xl:col-span-12">
          <ActivityFeed items={view.recentActivity} />
        </div>
      </div>
    </div>
  );
}
