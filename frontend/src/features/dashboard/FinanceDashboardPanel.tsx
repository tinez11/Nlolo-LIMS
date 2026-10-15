import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { countValue } from '@/lib/counts';
import { formatMoney, type Money } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectFinance, useDashboardStore } from '@/store/dashboardStore';
import { ActivityFeed } from './ActivityFeed';
import { DashboardRail } from './DashboardRail';
import { PremiumsChart } from './PremiumsChart';
import { lastDayOf, reportLink } from './reportLinks';

const MONTH_NAME = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const monthName = (yyyymm: string) => MONTH_NAME[Number(yyyymm.slice(5, 7)) - 1] ?? yyyymm;

const previousMonthName = (yyyymm: string) => {
  const m = Number(yyyymm.slice(5, 7));
  return MONTH_NAME[(m + 10) % 12] ?? '';
};

/** The finance dashboard (2026-10-14). Every money figure is a report's own number; every count is one status. */
export function FinanceDashboardPanel() {
  const resource = useDashboardStore(selectFinance);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('finance');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Reading the ledger" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('finance')} />;
  }
  const v = resource.data;
  if (!v) return null;

  const money = (m: Money | null | undefined) => (m ? formatMoney(m) : null);
  const change = v.premiumsChangePercent;
  const kpis: Kpi[] = [
    {
      label: `Premiums collected, ${monthName(v.premiumsMonth)}`,
      value: money(v.premiumsCollected),
      hint: 'last complete month — this month is still running',
      to: reportLink('premium-collections', { from: `${v.premiumsMonth}-01`, to: lastDayOf(v.premiumsMonth) }),
      ...(change !== null && change !== undefined
        ? {
            delta: {
              direction: change.startsWith('-') ? ('down' as const) : ('up' as const),
              text: `${change.replace(/^-/, '')}% vs ${previousMonthName(v.premiumsMonth)}`,
            },
          }
        : {}),
    },
    {
      label: 'Total assets',
      value: money(v.totalAssets),
      hint: `balance sheet through ${monthName(v.balanceSheetMonth)}`,
      to: reportLink('balance-sheet', { monthFrom: v.balanceSheetMonth }),
    },
    {
      label: 'Total liabilities',
      value: money(v.totalLiabilities),
      hint: `balance sheet through ${monthName(v.balanceSheetMonth)}`,
      to: reportLink('balance-sheet', { monthFrom: v.balanceSheetMonth }),
    },
    {
      label: 'Net result, year to date',
      value: money(v.netResult),
      hint: `income statement, ${monthName(v.resultFrom)}–${monthName(v.resultTo)}`,
      to: reportLink('income-statement', { monthFrom: v.resultFrom, monthTo: v.resultTo }),
    },
  ];

  const waiting: { n: number | null | undefined; text: string; to?: string }[] = [
    // No `to`: ManualJournalsPage does not read a status from its URL, so it would list every journal.
    { n: v.journalsAwaitingApproval, text: 'journals submitted, awaiting approval' },
    {
      n: v.receiptsOverdue,
      text: 'field receipts past their reconciliation deadline',
      to: '/staff/field-receipts?status=RECONCILIATION_OVERDUE',
    },
    { n: v.transfersAwaiting, text: 'bank transfers nobody has made yet', to: '/staff/bank-transfers' },
    { n: v.arrearsAtLapse, text: 'policies in arrears at the lapse level', to: '/staff/arrears?minDunningLevel=5' },
  ];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-5">
          <Panel emphasis title="Waiting on finance" subtitle="Each opens its list, already filtered.">
            <ul className="divide-y divide-border">
              {waiting.map((row) => (
                <li key={row.text} className="flex items-center gap-4 px-4 py-2.5">
                  <span className="w-12 shrink-0 text-right text-title">{countValue(row.n) ?? '—'}</span>
                  <span className="min-w-0 flex-1 text-sm">{row.text}</span>
                  {row.to && row.n !== null && row.n !== undefined && (
                    <Link
                      to={row.to}
                      aria-label={`Open the list of ${row.text}`}
                      className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                    >
                      Open
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          </Panel>
          <Panel
            title="Premiums collected"
            subtitle="By month, this year against last. Every bar is a report value."
            actions={
              <Link
                to={reportLink('premium-collections', {
                  from: `${Number(v.currentMonth.slice(0, 4)) - 1}-01-01`,
                  to: lastDayOf(v.currentMonth),
                })}
                className="text-xs text-muted-foreground hover:text-foreground hover:underline"
              >
                Open the report
              </Link>
            }
          >
            <PremiumsChart thisYear={v.premiumsThisYear} lastYear={v.premiumsLastYear} currentMonth={v.currentMonth} />
          </Panel>
          <ActivityFeed items={v.recentActivity} />
        </div>
        <DashboardRail
          alerts={[
            ...(v.receiptsOverdue
              ? [
                  {
                    tone: 'danger' as const,
                    text: (
                      <>
                        <b>{countValue(v.receiptsOverdue)} field receipts</b> are past their reconciliation deadline.
                      </>
                    ),
                  },
                ]
              : []),
            ...(v.journalsAwaitingApproval
              ? [
                  {
                    tone: 'warning' as const,
                    text: (
                      <>
                        <b>{countValue(v.journalsAwaitingApproval)} journals</b> wait for approval. You may not approve
                        your own.
                      </>
                    ),
                  },
                ]
              : []),
          ]}
          actions={[
            { label: 'Match field receipts', description: 'Agent-collected cash to payments', to: '/staff/field-receipts' },
            { label: 'Make bank transfers', description: 'Approved payouts awaiting EFT', to: '/staff/bank-transfers' },
            { label: 'Prepare a journal', description: 'Approval follows segregation of duties', to: '/staff/manual-journals/new' },
            { label: 'Run a statement', description: 'Trial balance, P&L, balance sheet', to: '/staff/reports' },
          ]}
          absent={['A cash-flow statement', 'Receivables total and aging — phase 2']}
        />
      </div>
    </div>
  );
}
