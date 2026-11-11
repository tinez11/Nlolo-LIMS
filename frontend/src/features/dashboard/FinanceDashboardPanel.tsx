import { Coins, FilePlus2, Hourglass, Scale } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Legend } from '@/components/charts/ChartTip';
import { Donut } from '@/components/charts/Donut';
import { ShareBars } from '@/components/charts/ShareBars';
import { TrendChart } from '@/components/charts/TrendChart';
import { Panel } from '@/components/Panel';
import { can } from '@/auth/access';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney, formatMoneyCompact } from '@/lib/money';
import { useApprovalsStore } from '@/store/approvalsStore';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectFinance, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import type { PanelProps } from './DashboardPage';
import { DashboardRail } from './DashboardRail';
import { describeRange, keyFor, monthOf, rangeFor, shiftMonth } from './range';
import type { CollectionFiguresView } from '@/api/dashboard';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/dates';
import { lastDayOf, reportLink } from './reportLinks';
import { donutParts, monthCategories, moneyPoints, trendOf, versus } from './series';
import { StatRow, type Stat } from './StatTile';

// The PRD's six lateness bands (2026-11-11), the follow-up list's own, after "not yet overdue" (due today).
const BANDS = [
  { name: 'NOT_DUE', label: 'Not yet overdue' },
  { name: 'D1_7', label: '1 to 7 days' },
  { name: 'D8_14', label: '8 to 14 days' },
  { name: 'D15_30', label: '15 to 30 days' },
  { name: 'D31_60', label: '31 to 60 days' },
  { name: 'D61_90', label: '61 to 90 days' },
  { name: 'OVER_90', label: 'Over 90 days' },
] as const;

const SPANS = [
  { key: 'month', label: 'This month' },
  { key: 'quarter', label: 'This quarter' },
  { key: 'ytd', label: 'This year' },
] as const;

/** The server's figure as money; formatting only. */
function tzs(amount: number | null | undefined, currency = 'TZS'): string | null {
  return amount == null ? null : formatMoneyCompact({ amount: amount.toFixed(2), currencyCode: currency });
}

/**
 * Collections, first (2026-11-11, the user: "collections for finance should be somewhere topwise"): of the premiums
 * due in the range, how much is paid and the rate, and what is overdue or due today. The month / quarter / year switch
 * sets the dashboard's one range -- the picker above moves with it.
 */
function CollectionsSection({
  figures,
  range,
  onRange,
}: {
  figures: CollectionFiguresView | null;
  range: { from: string; to: string };
  onRange?: (range: { from: string; to: string }) => void;
}) {
  const current = monthOf(new Date());
  const active = keyFor(range, current);
  const items: { label: string; value: string | null; hint: string; to?: string }[] = figures
    ? [
        { label: 'Premiums due', value: tzs(figures.due, figures.currency),
          hint: `falling due ${formatDate(figures.from)} to ${formatDate(figures.to)}` },
        { label: 'Premiums paid', value: tzs(figures.paid, figures.currency), hint: 'of the premiums due' },
        { label: 'Collection rate', value: figures.ratePercent ? `${figures.ratePercent}%` : null,
          hint: figures.ratePercent ? 'of the premiums due, how much is paid' : 'nothing fell due' },
        { label: 'Overdue now', value: figures.overdueCount.toLocaleString(),
          hint: `${tzs(figures.overdueOwed, figures.currency)} owed`, to: '/staff/follow-up' },
        { label: 'Due today', value: figures.dueTodayCount.toLocaleString(),
          hint: `${tzs(figures.dueTodayOwed, figures.currency)} to collect`, to: '/staff/follow-up?view=DUE_TODAY' },
      ]
    : [];

  return (
    <Panel
      title="Collections"
      subtitle={`Premiums due ${describeRange(range.from, range.to)}, and who to follow up today`}
      actions={
        onRange && (
          <div role="group" aria-label="Collections period" className="inline-flex gap-0.5 rounded-lg bg-control p-0.5">
            {SPANS.map((s) => (
              <button
                key={s.key}
                type="button"
                aria-pressed={active === s.key}
                onClick={() => onRange(rangeFor(s.key, current))}
                className={cn(
                  'rounded-md px-3 py-1.5 text-xs font-semibold text-muted-foreground transition-colors hover:text-foreground pointer-coarse:py-3',
                  active === s.key && 'bg-surface text-foreground shadow-raise',
                )}
              >
                {s.label}
              </button>
            ))}
          </div>
        )
      }
    >
      <div className="px-4 pb-4">
        {figures === null ? (
          <p className="text-xs text-muted-foreground">The collection figures could not be read.</p>
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(12rem,1fr))] gap-3">
            {items.map((item) => {
              const body = (
                <>
                  <span className="block text-xs font-medium text-muted-foreground">{item.label}</span>
                  <span className={cn('mt-1 block whitespace-nowrap text-figure', item.value === null && 'text-subtle-foreground')}>
                    {item.value ?? '—'}
                  </span>
                  <span className="mt-0.5 block text-xs text-subtle-foreground">{item.hint}</span>
                </>
              );
              const frame = '@container block min-w-0 rounded-lg border border-border px-4 py-3';
              return item.to ? (
                <Link key={item.label} to={item.to} className={cn(frame, 'transition-colors hover:bg-hover active:bg-selected active:duration-0')}>
                  {body}
                </Link>
              ) : (
                <div key={item.label} className={frame}>{body}</div>
              );
            })}
          </div>
        )}
      </div>
    </Panel>
  );
}

/**
 * The finance dashboard (2026-10-14; ranged and charted 2026-10-23). Every money figure is the server's: the range's
 * collections and their change, each product line's share, what is owed by age. The browser draws.
 */
export function FinanceDashboardPanel({ range, onRange }: PanelProps) {
  const resource = useDashboardStore(selectFinance);
  const load = useDashboardStore((s) => s.load);
  const signatures = useApprovalsStore((s) => s.approvals);
  const loadApprovals = useApprovalsStore((s) => s.loadApprovals);

  const { from, to } = range;
  useEffect(() => {
    void load('finance', { from, to });
  }, [load, from, to]);
  useEffect(() => {
    void loadApprovals();
  }, [loadApprovals]);

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
      hint: 'every unpaid premium due by today',
      // The list that holds exactly these (2026-11-11): Arrears holds only those with a case, so it listed fewer.
      to: '/staff/follow-up',
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
  // Only what this viewer can act on (2026-10-27): the signatures waiting for them -- the approvals inbox's own count,
  // their own requests left out -- in place of every journal waiting for anyone, which showed a finance officer
  // approvals they cannot give; and each list only to whoever can work it.
  const waiting: { n: number | null | undefined; text: string; to?: string; tone: 'warn' | 'calm'; show: boolean }[] = [
    { n: signatures.data?.actionable, text: 'Waiting for your signature', to: '/staff/approvals', tone: 'warn',
      show: true },
    { n: v.receiptsOverdue, text: 'Agent cash past its matching deadline', to: '/staff/field-receipts?status=RECONCILIATION_OVERDUE', tone: 'warn',
      show: can('collections.manage') },
    { n: v.transfersAwaiting, text: 'Bank transfers and cheques not yet made', to: '/staff/bank-transfers', tone: 'calm',
      show: can('payouts.manage') },
    { n: v.arrearsAtLapse, text: 'Policies in arrears at the lapse level', to: '/staff/arrears?minDunningLevel=5', tone: 'warn',
      show: can('collections.manage') },
  ];

  return (
    <div className="space-y-5">
      <CollectionsSection figures={v.collections} range={range} {...(onRange ? { onRange } : {})} />
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
            subtitle="Unpaid premiums due by today, by how overdue"
            actions={<Link to="/staff/follow-up" className="text-xs text-muted-foreground hover:text-foreground hover:underline">Open the list</Link>}
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
                      // One measure in order, so not a series per band: owed-not-late one colour, every late band another.
                      color: i === 0 ? 'chart-1' : 'chart-3',
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
          <Panel emphasis title="Needs a look">
            <ul className="divide-y divide-border">
              {waiting.filter((row) => row.show).map((row) => {
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
              { label: 'Match agent cash', description: 'Agent-collected cash to payments', to: '/staff/field-receipts' },
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
