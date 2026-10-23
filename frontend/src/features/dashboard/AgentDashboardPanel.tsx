import { BadgeCheck, Coins, FilePlus2, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getNeedsAction } from '@/api/proposalAmendments';
import { Legend } from '@/components/charts/ChartTip';
import { ColumnChart } from '@/components/charts/ColumnChart';
import { Donut } from '@/components/charts/Donut';
import { TrendChart } from '@/components/charts/TrendChart';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAgent, useDashboardStore } from '@/store/dashboardStore';
import type { PanelProps } from './DashboardPage';
import { DashboardRail } from './DashboardRail';
import { describeRange } from './range';
import { countPoints, donutParts, monthCategories, versus } from './series';
import { StatRow, type Stat } from './StatTile';

/**
 * The agent's dashboard (2026-10-14; ranged and charted 2026-10-23), and their landing page. Scoped by the server to
 * the clients they registered and their team's book: commission statements over the range, what the team sold and
 * what it holds now. Still leads with work -- returned proposals and KYC -- because those are what the agent can move.
 */
export function AgentDashboardPanel({ range }: PanelProps) {
  const resource = useDashboardStore(selectAgent);
  const load = useDashboardStore((s) => s.load);
  const { from, to } = range;

  useEffect(() => {
    void load('agent', { from, to });
  }, [load, from, to]);

  const [needsAction, setNeedsAction] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    getNeedsAction().then(
      (items) => { if (live) setNeedsAction(items.filter((n) => n.actionable).length); },
      () => undefined,
    );
    return () => { live = false; };
  }, []);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting your clients" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('agent', { from, to })} />;
  }
  const v = resource.data;
  if (!v) return null;

  const p = v.period;
  const span = describeRange(p.from, p.to);
  const categories = monthCategories(v.soldSeries.length ? v.soldSeries.map((m) => m.month) : v.commissionSeries.map((m) => m.month));
  const stats: Stat[] = [
    {
      label: 'Commission',
      icon: Coins,
      color: 'chart-1',
      // In full, never abbreviated: an agent checks it against their statement to the shilling.
      value: v.commissionInPeriod ? formatMoney(v.commissionInPeriod) : null,
      hint: `statements, ${span}`,
      trend: v.commissionSeries.map((c) => Number(c.paid?.amount ?? 0) + Number(c.pending?.amount ?? 0)),
      to: '/agents/commission',
    },
    {
      label: 'Policies sold',
      icon: FilePlus2,
      color: 'chart-2',
      value: countValue(v.sold),
      change: v.soldChangePercent,
      versus: versus(p),
      hint: span,
      trend: v.soldSeries.map((c) => c.count),
    },
    {
      label: 'Policies in my book',
      icon: Users,
      color: 'chart-4',
      value: countValue(v.policiesInBook),
      hint: 'you and your downline',
      to: '/agents/policies',
    },
    {
      label: 'Needs your action',
      icon: BadgeCheck,
      color: 'chart-3',
      value: countValue(needsAction),
      hint: 'proposals returned or postponed',
      to: '/agents/proposals?view=needs-action',
    },
  ];

  return (
    <div className="space-y-5">
      <StatRow stats={stats} />
      <div className="grid gap-5 xl:grid-cols-12">
        <div className="xl:col-span-8">
          <Panel
            title="Commission"
            subtitle={`${span}, your statements by month`}
            actions={<Link to="/agents/commission" className="text-xs text-muted-foreground hover:text-foreground hover:underline">Open statements</Link>}
          >
            <div className="space-y-3 px-4 pb-4">
              <Legend items={[{ label: 'Paid', color: 'chart-1' }, { label: 'Still to be paid', color: 'chart-3' }]} />
              <ColumnChart
                stacked
                categories={monthCategories(v.commissionSeries.map((m) => m.month))}
                partialKey={p.to === p.currentMonth ? p.currentMonth : undefined}
                series={[
                  { label: 'Paid', color: 'chart-1', points: v.commissionSeries.map((c) => ({ key: c.month, value: Number(c.paid?.amount ?? 0), display: formatMoney(c.paid) })) },
                  { label: 'Still to be paid', color: 'chart-3', points: v.commissionSeries.map((c) => ({ key: c.month, value: Number(c.pending?.amount ?? 0), display: formatMoney(c.pending) })) },
                ]}
                description={`Commission by month, ${span}: ${formatMoney(v.commissionInPeriod)} in all.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-4">
          <Panel title="My book" subtitle="Policies in force today, by product">
            <div className="px-4 pb-4">
              {v.bookByProduct.length === 0 ? (
                <p className="text-xs text-muted-foreground">No policies in force on your team yet.</p>
              ) : (
                <Donut
                  parts={donutParts(v.bookByProduct)}
                  total={countValue(v.policiesInBook) ?? ''}
                  totalLabel="policies"
                  description="Your team's policies in force, by product."
                />
              )}
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-7">
          <Panel title="Policies sold" subtitle={`${span}, by month`}>
            <div className="px-4 pb-4">
              <TrendChart
                height={200}
                categories={categories}
                series={[{ label: 'Sold', color: 'chart-2', area: true, points: countPoints(v.soldSeries) }]}
                description={`Policies sold by month, ${span}: ${countValue(v.sold) ?? 'none'}.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-5">
          <Panel title="Your business now" subtitle="Each opens its list">
            <ul className="divide-y divide-border">
              {[
                { n: v.clients, text: 'My clients', to: '/agents/clients' },
                { n: v.clientsAwaitingKyc, text: 'Clients awaiting KYC', to: '/agents/clients?kycStatus=PENDING' },
                { n: v.policiesInBook, text: 'Policies in my book', to: '/agents/policies' },
                { n: v.applicationsInUnderwriting, text: 'Applications in underwriting', to: undefined },
              ].map((row) => (
                <li key={row.text}>
                  {row.to && row.n !== null ? (
                    <Link
                      to={row.to}
                      aria-label={`${countValue(row.n)} ${row.text}`}
                      className="flex items-center justify-between gap-4 px-4 py-2.5 text-sm hover:bg-hover"
                    >
                      <span>{row.text}</span>
                      <span className="text-title tabular-nums">{countValue(row.n)}</span>
                    </Link>
                  ) : (
                    <div className="flex items-center justify-between gap-4 px-4 py-2.5 text-sm">
                      <span>{row.text}</span>
                      <span className="text-title tabular-nums">{countValue(row.n) ?? '—'}</span>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </Panel>
        </div>
        <div className="xl:col-span-7">
          <Panel emphasis title="Clients still needing KYC documents" subtitle="Send the ID scan; the office decides. Newest first.">
            {v.awaitingKyc.length === 0 ? (
              <p className="px-4 pb-4 text-xs text-muted-foreground">Every client you registered has been verified.</p>
            ) : (
              <ul className="divide-y divide-border">
                {v.awaitingKyc.map((c) => (
                  <li key={c.partyId} className="flex items-center gap-3 px-4 py-2.5">
                    <span className="min-w-0 flex-1 truncate text-sm">{c.displayName}</span>
                    <StatusBadge kind="kyc" value={c.kycStatus} />
                    <Link
                      to={`/agents/parties/${c.partyId}?tab=kyc`}
                      aria-label={`Send documents for ${c.displayName}`}
                      className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                    >
                      Send documents
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
        <div className="xl:col-span-5">
          <DashboardRail
            alerts={[
              ...(v.clientsAwaitingKyc
                ? [{ tone: 'warning' as const, text: <><b>{countValue(v.clientsAwaitingKyc)} clients</b> cannot hold a policy until their KYC is verified.</> }]
                : []),
              ...(v.clientsKycRejected
                ? [{
                    tone: 'danger' as const,
                    text: (
                      <>
                        <b>{countValue(v.clientsKycRejected)} {v.clientsKycRejected === 1 ? 'client has' : 'clients have'}</b>{' '}
                        had documents rejected.{' '}
                        <Link to="/agents/clients?kycStatus=REJECTED" className="underline">Send clearer ones.</Link>
                      </>
                    ),
                  }]
                : []),
            ]}
            actions={[
              { label: 'Quote a prospect', description: 'Price a product before they are a client', to: '/agents/quotation' },
              { label: 'Register a client', description: 'Then send their ID documents', to: '/agents/customers/new' },
              { label: 'My policies', description: 'Your book and your downline’s', to: '/agents/policies' },
              { label: 'My commission', description: 'Statements and payouts', to: '/agents/commission' },
            ]}
            absent={[]}
          />
        </div>
      </div>
    </div>
  );
}
