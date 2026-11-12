import { Download } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  exportMySales,
  getMySales,
  getMySalesReport,
  getPoliciesSold,
  type CompareWith,
  type PolicySold,
  type SalesBy,
  type SalesQuery,
  type SalesTotals,
} from '@/api/agentSales';
import type { ExportFormat, ReportResult } from '@/api/reporting';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { formatDate, todayIso } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import type { ApiError } from '@/lib/http';
import { formatMoney } from '@/lib/money';
import { selectOwnAgent, selectOwnTeam, useDistributionStore } from '@/store/distributionStore';
import { ReportFigures } from '@/features/reporting/ReportFigures';
import { ReportSectionTable } from '@/features/reporting/ReportSectionTable';
import { ReportTrend } from '@/features/reporting/ReportTrend';
import { bucketDays, bucketWords } from './salesBuckets';

const BY: { value: SalesBy; label: string }[] = [
  { value: 'MONTH', label: 'Month' },
  { value: 'QUARTER', label: 'Quarter' },
  { value: 'YEAR', label: 'Year' },
];

const money = (amount: number | null | undefined, currency: string) =>
  amount == null ? '—' : formatMoney({ amount: amount.toFixed(2), currencyCode: currency });

/**
 * My sales (2026-11-12, analytics plan 3; the user: "on the report be more ranged in timeline"): any dates, by month,
 * quarter or year, compared with an earlier period, each period opening the policies behind it, and a file to keep.
 * Sold means cover started (D1); single premiums and savings deposits apart (D2, D3). Every figure is the server's.
 */
export function MySalesPage() {
  const [params, setParams] = useSearchParams();
  const today = todayIso();
  const from = params.get('from') ?? `${Number(today.slice(0, 4)) - 1}-01-01`;
  const to = params.get('to') ?? today;
  const by = (BY.some((b) => b.value === params.get('by')) ? params.get('by') : 'QUARTER') as SalesBy;
  const compareWith = (['PRIOR_PERIOD', 'SAME_PERIOD_LAST_YEAR', 'NONE'].includes(params.get('compare') ?? '')
    ? params.get('compare') : 'PRIOR_PERIOD') as CompareWith;

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

  const query: SalesQuery = { from, to, by, compareWith, ...(agentId ? { agentId } : {}) };
  const key = JSON.stringify(query);

  // Each result carries the query it answers, so a slower old answer never shows under new filters.
  const [loaded, setLoaded] = useState<{ key: string; report: ReportResult; buckets: SalesTotals[] } | null>(null);
  const [failed, setFailed] = useState<{ key: string; error: ApiError } | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    const q = JSON.parse(key) as SalesQuery;
    Promise.all([getMySalesReport(q), getMySales(q)]).then(
      ([r, b]) => { if (live) setLoaded({ key, report: r, buckets: b }); },
      (e: ApiError) => { if (live) setFailed({ key, error: e }); },
    );
    return () => { live = false; };
  }, [key, attempt]);
  const report = loaded?.key === key ? loaded.report : null;
  const buckets = loaded?.key === key ? loaded.buckets : [];
  const error = failed?.key === key && report === null ? failed.error : null;

  const [opened, setOpened] = useState<string | null>(null);
  const openedKey = opened ? `${key}|${opened}` : null;
  const [sold, setSold] = useState<{ key: string; policies: PolicySold[] } | null>(null);
  useEffect(() => {
    if (!opened || !openedKey) return;
    let live = true;
    const days = bucketDays(opened, from, to);
    getPoliciesSold(days.from, days.to, agentId).then(
      (p) => { if (live) setSold({ key: openedKey, policies: p }); },
      () => { if (live) setSold({ key: openedKey, policies: [] }); },
    );
    return () => { live = false; };
  }, [opened, openedKey, from, to, agentId]);
  const policies = sold && sold.key === openedKey ? sold.policies : null;

  const [downloading, setDownloading] = useState<ExportFormat | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  async function download(format: ExportFormat) {
    setDownloading(format);
    setDownloadError(null);
    try {
      saveBlob(await exportMySales(query, format), `my-sales-${today}.${format}`);
    } catch {
      setDownloadError('The file could not be made. Try again in a moment.');
    } finally {
      setDownloading(null);
    }
  }

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [k, value] of Object.entries(next)) {
      if (value) merged.set(k, value);
      else merged.delete(k);
    }
    setParams(merged, { replace: true });
    setOpened(null);
  }

  const policyColumns: Column<PolicySold>[] = [
    {
      key: 'policy',
      header: 'Policy',
      render: (p) => (
        <Link to={`/agents/policies/${encodeURIComponent(p.policyNumber)}`} className="whitespace-nowrap font-mono font-medium hover:underline">
          {p.policyNumber}
        </Link>
      ),
    },
    { key: 'client', header: 'Client', render: (p) => p.clientName ?? '—' },
    { key: 'product', header: 'Product', secondary: true, render: (p) => p.productName ?? '—' },
    { key: 'on', header: 'On cover from', nowrap: true, render: (p) => formatDate(p.onCoverFrom ?? null) },
    {
      key: 'year',
      header: 'Premium a year',
      align: 'right',
      render: (p) =>
        p.premiumAYear != null ? money(p.premiumAYear, p.currency)
          : p.savingsDeposit != null ? <span className="text-muted-foreground">Deposit {money(p.savingsDeposit, p.currency)}</span>
          : <span className="text-muted-foreground">Single {money(p.premium, p.currency)}</span>,
    },
    { key: 'cover', header: 'Cover', align: 'right', secondary: true, render: (p) => money(p.cover, p.currency) },
    ...(hasTeam && !onlyMe ? [{ key: 'agent', header: 'Agent', secondary: true, render: (p: PolicySold) => p.agentName ?? '—' }] : []),
  ];

  return (
    <>
      <PageHeader
        title="My sales"
        description="What you sold, in money: premium a year, cover, single premiums and savings deposits. Sold means cover started."
        actions={
          <>
            <Button size="sm" variant="outline" pending={downloading === 'xlsx'} onClick={() => void download('xlsx')}>
              <Download />
              Download Excel
            </Button>
            <Button size="sm" variant="outline" pending={downloading === 'pdf'} onClick={() => void download('pdf')}>
              <Download />
              Download PDF to print
            </Button>
          </>
        }
      />
      <div className="space-y-5 px-6 pb-8">
        <div className="flex flex-wrap items-end gap-3 border-b border-border pb-4">
          <label className="flex flex-col gap-1 text-xs" htmlFor="sales-from">
            From
            <Input id="sales-from" type="date" inputSize="sm" value={from} max={to} onChange={(e) => update({ from: e.target.value })} />
          </label>
          <label className="flex flex-col gap-1 text-xs" htmlFor="sales-to">
            To
            <Input id="sales-to" type="date" inputSize="sm" value={to} min={from} max={today} onChange={(e) => update({ to: e.target.value })} />
          </label>
          <div role="group" aria-label="Show by" className="flex items-center gap-1.5">
            <span className="mr-1 text-xs text-muted-foreground" aria-hidden>Show by</span>
            {BY.map((b) => (
              <FilterChip key={b.value} label={b.label} active={by === b.value} onClick={() => update({ by: b.value === 'QUARTER' ? '' : b.value })} />
            ))}
          </div>
          <label className="flex flex-col gap-1 text-xs" htmlFor="sales-compare">
            Compare with
            <Select id="sales-compare" inputSize="sm" value={compareWith} onChange={(e) => update({ compare: e.target.value === 'PRIOR_PERIOD' ? '' : e.target.value })}>
              <option value="PRIOR_PERIOD">The period before</option>
              <option value="SAME_PERIOD_LAST_YEAR">The same period last year</option>
              <option value="NONE">Nothing</option>
            </Select>
          </label>
          {hasTeam && (
            <div role="group" aria-label="Whose sales" className="flex items-center gap-1.5">
              <FilterChip label="Me" active={onlyMe} onClick={() => update({ show: 'me' })} />
              <FilterChip label="My team" active={!onlyMe} onClick={() => update({ show: '' })} />
            </div>
          )}
        </div>

        {downloadError && (
          <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">{downloadError}</p>
        )}

        {error ? (
          <ErrorPanel error={error} onRetry={() => setAttempt((n) => n + 1)} />
        ) : report === null ? (
          <LoadingBlock label="Adding up your sales" />
        ) : (
          <>
            <ReportFigures figures={report.figures} comparisonLabel={report.comparisonLabel} />
            {report.chart && report.chart.series[0] && report.chart.series[0].points.length > 1 && (
              <Panel title={report.chart.description}>
                <div className="px-4 pb-4">
                  <ReportTrend chart={report.chart} />
                </div>
              </Panel>
            )}
            {report.sections.map((s) => <ReportSectionTable key={s.title} section={s} />)}

            {buckets.length === 0 ? (
              <EmptyState title="Nothing sold in these dates" description="Pick earlier dates, or a wider span." />
            ) : (
              <Panel title="The policies behind the figures" subtitle="Pick a period to see each policy sold in it">
                <div className="space-y-3 pb-2">
                  <div role="group" aria-label="Period" className="flex flex-wrap gap-1.5 px-4">
                    {buckets.map((b) => (
                      <FilterChip
                        key={b.bucket}
                        label={`${bucketWords(b.bucket)} · ${b.policies}`}
                        active={opened === b.bucket}
                        onClick={() => setOpened(opened === b.bucket ? null : b.bucket)}
                      />
                    ))}
                  </div>
                  {opened && (policies === null ? (
                    <LoadingBlock label="Reading the policies" />
                  ) : (
                    <DataTable columns={policyColumns} rows={policies} rowKey={(p) => p.policyNumber}
                      caption={`Policies sold, ${bucketWords(opened)}`} />
                  ))}
                </div>
              </Panel>
            )}
          </>
        )}
      </div>
    </>
  );
}
