import { can } from '@/auth/access';
import { CheckCircle2, Clock3, FolderOpen, Percent } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getArrivals } from '@/api/proposalAmendments';
import { Legend } from '@/components/charts/ChartTip';
import { ColumnChart } from '@/components/charts/ColumnChart';
import { Donut } from '@/components/charts/Donut';
import { ShareBars } from '@/components/charts/ShareBars';
import { TrendChart } from '@/components/charts/TrendChart';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { humanizeStatus } from '@/lib/status';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectUnderwriting, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import type { PanelProps } from './DashboardPage';
import { DashboardRail } from './DashboardRail';
import { describeRange } from './range';
import { lastDayOf } from './reportLinks';
import { dayPoints, donutParts, monthCategories, trendOf, versus } from './series';
import { StatRow, type Stat } from './StatTile';

/** The decision panel's own words for each outcome. An outcome not listed shows its literal. */
const OUTCOME_LABEL: Record<string, string> = {
  ACCEPT: 'Accepted',
  LOADED: 'Accepted with a loading',
  DECLINED: 'Declined',
  POSTPONED: 'Postponed',
};

/** The case list filtered to exactly what a figure counted: decided inside these months. */
function decidedLink(from: string, to: string, outcome?: string): string {
  const params = new URLSearchParams({ decidedFrom: `${from}-01`, decidedTo: lastDayOf(to) });
  if (outcome) params.set('outcome', outcome);
  return `/staff/underwriting?${params.toString()}`;
}

/**
 * The underwriter's dashboard (2026-10-14; ranged and charted 2026-10-23). Counts link to their queue; every total,
 * rate and average over the range is the server's.
 */
export function UnderwritingDashboardPanel({ range }: PanelProps) {
  const resource = useDashboardStore(selectUnderwriting);
  const load = useDashboardStore((s) => s.load);
  const { from, to } = range;

  useEffect(() => {
    void load('underwriting', { from, to });
  }, [load, from, to]);

  const [backFromAgents, setBackFromAgents] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    getArrivals({ resubmittedOnly: true, pageSize: 1 }).then(
      (p) => { if (live) setBackFromAgents(p.page.totalElements); },
      () => undefined,
    );
    return () => { live = false; };
  }, []);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting cases" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('underwriting', { from, to })} />;
  }
  const view = resource.data;
  if (!view) return null;

  const p = view.period;
  const span = describeRange(p.from, p.to);
  const days = dayPoints(view.turnaroundSeries);
  const stats: Stat[] = [
    {
      label: 'Open cases',
      icon: FolderOpen,
      color: 'chart-2',
      value: countValue(view.openCases),
      hint: 'waiting for a first review',
      to: '/staff/underwriting?status=OPEN',
    },
    {
      label: 'Decided',
      icon: CheckCircle2,
      color: 'chart-1',
      value: countValue(view.decided),
      change: view.decidedChangePercent,
      versus: versus(p),
      hint: span,
      trend: view.outcomeSeries.map((m) => m.accepted),
      to: decidedLink(p.from, p.to),
    },
    {
      label: 'Days to decide',
      icon: Clock3,
      color: 'chart-3',
      value: view.averageDays === null ? null : `${view.averageDays} days`,
      hint: `on average, ${span}`,
      trend: trendOf(days),
    },
    {
      label: 'Accepted',
      icon: Percent,
      color: 'chart-4',
      value: view.acceptanceRate === null ? null : `${view.acceptanceRate}%`,
      hint: `of cases decided, ${span}`,
    },
  ];

  const queueStages = [
    { key: 'open', label: 'Open, not yet reviewed', n: view.openCases, color: 'chart-2' as const },
    { key: 'review', label: 'In review', n: view.casesInReview, color: 'chart-1' as const },
    { key: 'senior', label: 'Waiting on a senior', n: view.casesReferred, color: 'chart-4' as const },
    { key: 'back', label: 'Back from agents', n: backFromAgents, color: 'chart-3' as const },
    { key: 'issue', label: 'Accepted, awaiting issue', n: view.casesAwaitingIssue, color: 'chart-5' as const },
  ];
  const next = view.queue[0];
  const categories = monthCategories(view.outcomeSeries.map((m) => m.month));

  return (
    <div className="space-y-5">
      <StatRow stats={stats} />
      <div className="grid gap-5 xl:grid-cols-12">
        <div className="xl:col-span-8">
          <Panel
            title="Decisions"
            subtitle={`${span}, by month`}
            actions={<Link to={decidedLink(p.from, p.to)} className="text-xs text-muted-foreground hover:text-foreground hover:underline">Open the cases</Link>}
          >
            <div className="space-y-3 px-4 pb-4">
              <Legend items={[
                { label: 'Accepted', color: 'chart-1' },
                { label: 'Postponed', color: 'chart-3' },
                { label: 'Declined', color: 'chart-5' },
              ]} />
              <ColumnChart
                stacked
                categories={categories}
                partialKey={p.to === p.currentMonth ? p.currentMonth : undefined}
                series={[
                  { label: 'Accepted', color: 'chart-1', points: view.outcomeSeries.map((m) => ({ key: m.month, value: m.accepted, display: countValue(m.accepted) ?? '0' })) },
                  { label: 'Postponed', color: 'chart-3', points: view.outcomeSeries.map((m) => ({ key: m.month, value: m.postponed, display: countValue(m.postponed) ?? '0' })) },
                  { label: 'Declined', color: 'chart-5', points: view.outcomeSeries.map((m) => ({ key: m.month, value: m.declined, display: countValue(m.declined) ?? '0' })) },
                ]}
                description={`Underwriting decisions by month, ${span}: accepted, postponed and declined. ${countValue(view.decided) ?? 'None'} decided.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-4">
          <Panel title="How cases ended" subtitle={span}>
            <div className="px-4 pb-4">
              {view.outcomeTotals.length === 0 ? (
                <p className="text-xs text-muted-foreground">Nothing was decided in these months.</p>
              ) : (
                <Donut
                  parts={donutParts(view.outcomeTotals, (k) => OUTCOME_LABEL[k] ?? humanizeStatus(k))}
                  total={countValue(view.decided) ?? ''}
                  totalLabel="decided"
                  description={`How the ${countValue(view.decided)} cases decided in ${span} ended.`}
                />
              )}
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-7">
          <Panel title="Days to decide" subtitle="Average calendar days from opening to decision, by month">
            <div className="px-4 pb-4">
              <TrendChart
                height={200}
                categories={categories}
                series={[{ label: 'Average days', color: 'chart-2', area: true, points: days }]}
                description={`Average days to decide by month, ${span}. Over the range: ${view.averageDays ?? 'no decisions'} days.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-5">
          <Panel title="Where open cases are" subtitle="As at now">
            <div className="px-4 pb-4">
              <ShareBars
                label="Open cases by stage"
                bars={queueStages.map((s) => ({
                  key: s.key,
                  label: s.label,
                  value: s.n ?? 0,
                  display: countValue(s.n) ?? '—',
                  color: s.color,
                }))}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-7">
          <Panel emphasis title="Next in your queue" subtitle="Open cases that have waited longest, oldest first.">
            {view.queue.length === 0 ? (
              <p className="px-4 pb-4 text-xs text-muted-foreground">No open cases.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-band text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-2.5 font-medium">Applicant</th>
                      <th className="px-4 py-2.5 font-medium">Product</th>
                      <th className="px-4 py-2.5 text-right font-medium">Sum assured</th>
                      <th className="px-4 py-2.5 font-medium">Status</th>
                      <th className="px-4 py-2.5">
                        <span className="sr-only">Open</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.queue.map((c) => (
                      <tr key={c.caseId} className="h-11 border-t border-border first:border-t-0">
                        <td className="px-4">
                          <PartyName partyId={c.applicantPartyId} />
                        </td>
                        <td className="px-4">{c.productId ? <ProductName productId={c.productId} /> : '—'}</td>
                        <td className="px-4 text-right whitespace-nowrap">
                          {c.sumAssured ? formatMoney(c.sumAssured) : '—'}
                        </td>
                        <td className="px-4">
                          <StatusBadge kind="underwritingCase" value={c.status} />
                        </td>
                        <td className="px-4 text-right">
                          <Link
                            to={`/staff/underwriting/${c.caseId}`}
                            aria-label={`Review ${c.caseId}`}
                            className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                          >
                            Review
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
            alerts={
              view.casesAwaitingIssue
                ? [
                    {
                      tone: 'warning',
                      text: (
                        <>
                          <b>{countValue(view.casesAwaitingIssue)} accepted cases</b> have no policy yet. Issuing is the
                          next step.
                        </>
                      ),
                    },
                  ]
                : []
            }
            actions={[
              ...(next
                ? [
                    {
                      label: 'Review the next case',
                      description: 'The open case that has waited longest',
                      to: `/staff/underwriting/${next.caseId}`,
                    },
                  ]
                : []),
              ...(can('underwriting.propose')
                ? [{ label: 'Open a case', description: 'For a registered client', to: '/staff/underwriting/new' }]
                : []),
              ...(can('policies.issue')
                ? [{ label: 'Issue a policy', description: 'From an accepted case', to: '/staff/policies/new' }]
                : []),
              { label: 'All open cases', description: 'The full queue', to: '/staff/underwriting?status=OPEN' },
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
