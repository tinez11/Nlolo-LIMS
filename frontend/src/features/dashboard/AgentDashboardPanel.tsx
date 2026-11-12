import { ArrowDown, ArrowUp, BadgeCheck, Coins, Users } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { cn } from '@/lib/cn';
import { selectOwnAgent, selectOwnTeam, useDistributionStore } from '@/store/distributionStore';
import { getNeedsAction } from '@/api/proposalAmendments';
import { listMyFollowUp, type FollowUpPage } from '@/api/followUp';
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

  // Me / My team (2026-11-12, analytics plan 3): a supervisor sees their team's sales by default and can narrow to
  // their own; an agent with no downline sees no switch. In the address (?show=me) so a reload keeps it.
  const [params, setParams] = useSearchParams();
  const own = useDistributionStore(selectOwnAgent);
  const team = useDistributionStore(selectOwnTeam);
  const loadOwnAgent = useDistributionStore((s) => s.loadOwnAgent);
  const loadOwnTeam = useDistributionStore((s) => s.loadOwnTeam);
  useEffect(() => {
    void loadOwnAgent();
    void loadOwnTeam();
  }, [loadOwnAgent, loadOwnTeam]);
  const hasTeam = (team.data?.length ?? 0) > 1;
  const onlyMe = hasTeam && params.get('show') === 'me';
  const agentId = onlyMe ? own.data?.agentId : undefined;

  useEffect(() => {
    void load('agent', { from, to, ...(agentId ? { agentId } : {}) });
  }, [load, from, to, agentId]);

  const [needsAction, setNeedsAction] = useState<number | null>(null);
  useEffect(() => {
    let live = true;
    getNeedsAction().then(
      (items) => { if (live) setNeedsAction(items.filter((n) => n.actionable).length); },
      () => undefined,
    );
    return () => { live = false; };
  }, []);

  // The five most overdue on the team (2026-11-11): who to call first, with the whole list one link away.
  const [followUp, setFollowUp] = useState<FollowUpPage | null | 'error'>(null);
  useEffect(() => {
    let live = true;
    listMyFollowUp({ view: 'OVERDUE', soonDays: 7, page: 0, pageSize: 5 }).then(
      (page) => { if (live) setFollowUp(page); },
      () => { if (live) setFollowUp('error'); },
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

  const paid = v.customersPaid;
  // The lit figure (2026-11-12, the user: "too much explanation and info"): three figures, each said once, the
  // span said once in the subtitle. In full: an agent checks them against their own records to the shilling.
  const change = v.premiumSoldChangePercent;
  const sales: { label: string; value: string; note?: ReactNode }[] = [
    {
      label: 'Premium a year',
      value: v.premiumSold ? formatMoney(v.premiumSold) : '—',
      note: change && (
        <span data-delta={change.startsWith('-') ? 'bad' : 'good'} className="flex items-center gap-1">
          {change.startsWith('-') ? <ArrowDown className="size-3" aria-hidden /> : <ArrowUp className="size-3" aria-hidden />}
          {change.startsWith('-') ? `${change.slice(1)}% down` : `${change}% up`} {versus(p)}
        </span>
      ),
    },
    { label: 'Cover sold', value: v.coverSold ? formatMoney(v.coverSold) : '—' },
    { label: 'Policies sold', value: countValue(v.sold) ?? '—' },
  ];
  // Apart, never added into premium (D2, D3) -- and only when there is any.
  const apart = [
    Number(v.singlePremiumsSold?.amount ?? 0) > 0 && `Single premiums ${formatMoney(v.singlePremiumsSold)}`,
    Number(v.savingsDepositsTaken?.amount ?? 0) > 0 && `Savings deposits ${formatMoney(v.savingsDepositsTaken)}`,
  ].filter(Boolean).join(' · ');

  return (
    <div className="space-y-5">
      <Panel
        title={onlyMe ? 'Your sales' : hasTeam ? 'Your team’s sales' : 'Your sales'}
        subtitle={span}
        actions={
          <div className="flex items-center gap-3">
            <Link
              to={`/agents/sales${onlyMe ? '?show=me' : ''}`}
              className="text-xs text-muted-foreground hover:text-foreground hover:underline"
            >
              Sales report
            </Link>
          {hasTeam && (
            <div role="group" aria-label="Whose sales" className="inline-flex gap-0.5 rounded-lg bg-control p-0.5">
              {[
                { me: true, label: 'Me' },
                { me: false, label: 'My team' },
              ].map((o) => (
                <button
                  key={o.label}
                  type="button"
                  aria-pressed={onlyMe === o.me}
                  onClick={() => {
                    const next = new URLSearchParams(params);
                    if (o.me) next.set('show', 'me');
                    else next.delete('show');
                    setParams(next, { replace: true });
                  }}
                  className={cn(
                    'rounded-md px-3 py-1.5 text-xs font-semibold text-muted-foreground transition-colors hover:text-foreground pointer-coarse:py-3',
                    onlyMe === o.me && 'bg-surface text-foreground shadow-raise',
                  )}
                >
                  {o.label}
                </button>
              ))}
            </div>
          )}
          </div>
        }
      >
        <div className="space-y-2 px-4 pb-4">
          <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3">
            {sales.map((s) => (
              <div key={s.label} className="flex min-w-0 flex-col gap-1">
                <dt className="text-sm text-muted-foreground">{s.label}</dt>
                <dd className="min-w-0 whitespace-nowrap text-display">{s.value}</dd>
                {s.note && <dd className="text-xs text-subtle-foreground">{s.note}</dd>}
              </div>
            ))}
          </dl>
          {apart && <p className="text-xs text-muted-foreground">{apart}</p>}
        </div>
      </Panel>
      <StatRow stats={stats} />
      <Panel
        title="Your customers to follow up"
        subtitle={
          followUp && followUp !== 'error'
            ? `${paid?.ratePercent ? `${paid.ratePercent}% of this month’s premiums paid · ` : ''}${countValue(followUp.overdue)} overdue, ${countValue(followUp.dueToday)} due today`
            : 'The most overdue first'
        }
        actions={<Link to="/agents/follow-up" className="text-xs text-muted-foreground hover:text-foreground hover:underline">See them all</Link>}
      >
        {followUp === null ? (
          <p className="px-4 pb-4 text-xs text-muted-foreground">Reading your list…</p>
        ) : followUp === 'error' ? (
          <p className="px-4 pb-4 text-xs text-muted-foreground">The list could not be read.</p>
        ) : followUp.rows.length === 0 ? (
          <p className="px-4 pb-4 text-sm text-muted-foreground">Nobody on your team is behind on a premium.</p>
        ) : (
          <ul className="divide-y divide-border">
            {followUp.rows.map((r) => (
              <li key={r.invoiceId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-4 py-2.5 text-sm">
                <span className="min-w-0">
                  <span className="font-medium">{r.clientName ?? r.policyNumber}</span>
                  {r.clientPhone && (
                    <a href={`tel:${r.clientPhone}`} className="ml-2 text-xs text-muted-foreground hover:text-foreground hover:underline">
                      {r.clientPhone}
                    </a>
                  )}
                </span>
                <span className="flex items-center gap-3 whitespace-nowrap">
                  <span className="font-medium">
                    {formatMoney({ amount: r.owed.toFixed(2), currencyCode: r.currency })}
                  </span>
                  {/* The list's own rule: past 30 days is danger, otherwise warning. */}
                  <span className={r.daysLate > 30
                    ? 'rounded-full bg-status-danger-bg px-2 py-0.5 text-xs font-medium text-status-danger-fg'
                    : 'rounded-full bg-status-warning-bg px-2 py-0.5 text-xs font-medium text-status-warning-fg'}>
                    {r.daysLate === 1 ? '1 day late' : `${r.daysLate} days late`}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
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
          <Panel title="Your business now">
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
