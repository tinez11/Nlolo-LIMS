import { Coins, FilePlus2, Hourglass, Scale } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Legend } from '@/components/charts/ChartTip';
import { Donut } from '@/components/charts/Donut';
import { ShareBars } from '@/components/charts/ShareBars';
import { TrendChart } from '@/components/charts/TrendChart';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney, formatMoneyCompact } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectFinance, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import type { PanelProps } from './DashboardPage';
import { DashboardRail } from './DashboardRail';
import { describeRange, shiftMonth } from './range';
import { lastDayOf, reportLink } from './reportLinks';
import { donutParts, monthCategories, moneyPoints, trendOf, versus } from './series';
import { StatRow, type Stat } from './StatTile';

const BANDS = [
  { name: 'NOT_DUE', label: 'Not yet due' },
  { name: 'D1_30', label: '1 to 30 days' },
  { name: 'D31_60', label: '31 to 60 days' },
  { name: 'D61_90', label: '61 to 90 days' },
  { name: 'OVER_90', label: 'Over 90 days' },
] as const;
const COLORS = ['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5'] as const;

/**
 * The finance dashboard (2026-10-14; ranged and charted 2026-10-23). Every money figure is the server's: the range's
 * collections and their change, each product line's share, what is owed by age. The browser draws.
 */
export function FinanceDashboardPanel({ range }: PanelProps) {
  const resource = useDashboardStore(selectFinance);
  const load = useDashboardStore((s) => s.load);

  const { from, to } = range;
  useEffect(() => {
    void load('finance', { from, to });
  }, [load, from, to]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Reading the ledger" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('finance', range)} />;
  }
  const v = resource.data;
  if (!v) return null;

  const p = v.period;
  const span = describeRange(p.from, p.to);
  const collected = moneyPoints(v.collectedSeries);
  const stats: Stat[] = [
    {
      label: 'Premiums collected',
      icon: Coins,
      color: 'chart-1',
      value: v.collected ? formatMoneyCompact(v.collected) : null,
      full: formatMoney(v.collected),
      change: v.collectedChangePercent,
      versus: versus(p),
      hint: span,
      trend: trendOf(collected),
      to: reportLink('premium-collections', { from: `${p.from}-01`, to: lastDayOf(p.to) }),
    },
    {
      label: 'Owed today',
      icon: Hourglass,
      color: 'chart-3',
      value: v.outstanding ? formatMoneyCompact(v.outstanding) : null,
      full: formatMoney(v.outstanding),
      hint: 'every unpaid invoice, as at today',
      to: '/staff/arrears',
    },
    {
      label: 'New policies',
      icon: FilePlus2,
      color: 'chart-4',
      value: countValue(v.newPolicies),
      change: v.newPoliciesChangePercent,
      versus: versus(p),
      hint: span,
      trend: v.newPoliciesSeries.map((c) => c.count),
    },
    {
      label: 'Net result, year to date',
      icon: Scale,
      color: 'chart-2',
      value: v.netResult ? formatMoneyCompact(v.netResult) : null,
      full: formatMoney(v.netResult),
      hint: `income statement, ${describeRange(v.resultFrom, v.resultTo)}`,
      to: reportLink('income-statement', { monthFrom: v.resultFrom, monthTo: v.resultTo }),
    },
  ];

  const working = v.receivables.find((a) => a.currency === 'TZS');
  const waiting: { n: number | null | undefined; text: string; to?: string; tone: 'warn' | 'calm' }[] = [
    { n: v.journalsAwaitingApproval, text: 'Journals waiting for approval', tone: 'warn' },
    { n: v.receiptsOverdue, text: 'Field receipts past their deadline', to: '/staff/field-receipts?status=RECONCILIATION_OVERDUE', tone: 'warn' },
    { n: v.transfersAwaiting, text: 'Bank transfers not yet made', to: '/staff/bank-transfers', tone: 'calm' },
    { n: v.arrearsAtLapse, text: 'Policies in arrears at the lapse level', to: '/staff/arrears?minDunningLevel=5', tone: 'warn' },
  ];

  return (
    <div className="space-y-5">
      <StatRow stats={stats} />
      <div className="grid gap-5 xl:grid-cols-12">
        <div className="xl:col-span-8">
          <Panel
            title="Premiums collected"
            subtitle={`${span}, by month, against the same months a year earlier`}
            actions={
              <Link
                to={reportLink('premium-collections', { from: `${p.from}-01`, to: lastDayOf(p.to) })}
                className="text-xs text-muted-foreground hover:text-foreground hover:underline"
              >
                Open the report
              </Link>
            }
          >
            <div className="space-y-3 px-4 pb-4">
              <Legend items={[
                { label: describeRange(p.from, p.to), color: 'chart-1', line: true },
                { label: describeRange(p.compareFrom, p.compareTo), color: 'chart-2', dashed: true },
              ]} />
              <TrendChart
                categories={monthCategories(v.collectedSeries.map((m) => m.month))}
                series={[
                  { label: span, color: 'chart-1', area: true, points: collected },
                  {
                    label: describeRange(p.compareFrom, p.compareTo),
                    color: 'chart-2',
                    dashed: true,
                    points: moneyPoints(v.collectedCompareSeries, (m) => shiftMonth(m, 12)),
                  },
                ]}
                description={`Premiums collected by month, ${span}, against ${describeRange(p.compareFrom, p.compareTo)}. `
                  + `Total ${formatMoney(v.collected)}.`}
              />
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-4">
          <Panel title="By product line" subtitle={`Share of ${span}'s collections`}>
            <div className="px-4 pb-4">
              {v.collectedByProductLine.length === 0 ? (
                <p className="text-xs text-muted-foreground">Nothing was collected in these months.</p>
              ) : (
                <Donut
                  parts={donutParts(v.collectedByProductLine)}
                  total={formatMoneyCompact(v.collected).replace(/^TZS /, '')}
                  totalLabel="TZS collected"
                  description={`Collections by product line, ${span}.`}
                />
              )}
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-6">
          <Panel
            title="Who owes, and for how long"
            subtitle="Unpaid invoices as at today, by how overdue"
            actions={<Link to="/staff/arrears" className="text-xs text-muted-foreground hover:text-foreground hover:underline">Open arrears</Link>}
          >
            <div className="px-4 pb-4">
              {working ? (
                <ShareBars
                  label="Owed by how overdue"
                  bars={BANDS.map((band, i) => {
                    const b = working.buckets.find((x) => x.name === band.name);
                    return {
                      key: band.name,
                      label: band.label,
                      note: `${countValue(b?.cases ?? 0)} invoices`,
                      value: b?.outstanding ? Number(b.outstanding.amount) : 0,
                      display: b?.outstanding ? formatMoneyCompact(b.outstanding) : 'TZS 0',
                      color: COLORS[i]!,
                    };
                  })}
                />
              ) : v.outstanding ? (
                // Read, and nothing is owed in TZS: the answer a reader came for, not a failure.
                <p className="text-sm text-muted-foreground">Nothing is owed. Every invoice raised has been paid.</p>
              ) : (
                <p className="text-xs text-muted-foreground">Billing could not be read.</p>
              )}
            </div>
          </Panel>
        </div>
        <div className="xl:col-span-6">
          <Panel emphasis title="Needs a look" subtitle="Each opens its list, already filtered">
            <ul className="divide-y divide-border">
              {waiting.map((row) => {
                const n = countValue(row.n);
                const chip = (
                  <span className={row.n ? (row.tone === 'warn' ? 'rounded-full bg-status-warning-bg px-2.5 py-0.5 text-xs font-bold text-status-warning-fg'
                    : 'rounded-full bg-status-active-bg px-2.5 py-0.5 text-xs font-bold text-status-active-fg')
                    : 'rounded-full bg-control px-2.5 py-0.5 text-xs font-bold text-muted-foreground'}>
                    {n ?? '—'}
                  </span>
                );
                return (
                  <li key={row.text}>
                    {row.to && row.n ? (
                      <Link to={row.to} className="flex items-center justify-between gap-4 px-4 py-2.5 text-sm hover:bg-hover">
                        <span>{row.text}</span>
                        {chip}
                      </Link>
                    ) : (
                      <div className="flex items-center justify-between gap-4 px-4 py-2.5 text-sm">
                        <span>{row.text}</span>
                        {chip}
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          </Panel>
        </div>
        <div className="xl:col-span-7">
          <ActivityFeed items={v.recentActivity} />
        </div>
        <div className="xl:col-span-5">
          <DashboardRail
            alerts={[]}
            actions={[
              { label: 'Match field receipts', description: 'Agent-collected cash to payments', to: '/staff/field-receipts' },
              { label: 'Make bank transfers', description: 'Approved payouts awaiting EFT', to: '/staff/bank-transfers' },
              { label: 'Prepare a journal', description: 'Approval follows segregation of duties', to: '/staff/manual-journals/new' },
              { label: 'Run a statement', description: 'Trial balance, P&L, balance sheet', to: '/staff/reports' },
            ]}
            absent={[]}
          />
        </div>
      </div>
    </div>
  );
}
