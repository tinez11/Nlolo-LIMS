import { Download } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  downloadClientPremiums,
  getClientPremiums,
  type ClientPremiumRow,
  type ClientPremiums,
  type PremiumWindow,
} from '@/api/clientPremiums';
import { downloadReceipt, type DocumentFormat } from '@/api/documents';
import { canAny } from '@/auth/access';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate, todayIso } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import type { ApiError } from '@/lib/http';
import { formatMoney } from '@/lib/money';

const WINDOWS: { value: PremiumWindow; label: string; words: string }[] = [
  { value: '30', label: 'Next 30 days', words: 'in the next 30 days' },
  { value: '90', label: 'Next 90 days', words: 'in the next 90 days' },
  { value: 'ALL', label: 'All raised', words: 'on every invoice raised' },
];

/** The server's figure, in full: finance checks it against the bank to the shilling. */
const money = (amount: number | null | undefined, currency: string) =>
  amount == null ? '—' : formatMoney({ amount: amount.toFixed(2), currencyCode: currency });

/**
 * The client's Premiums tab (2026-11-12, the user: "to see the upcoming invoices for their policies so it becomes
 * quick for payments"): what they owe across every policy, overdue first, and their last receipt one click away. The
 * same lit band and table as the dashboards; every figure is the server's, and each is said once.
 */
export function ClientPremiumsTab({ partyId }: { partyId: string }) {
  const [params, setParams] = useSearchParams();
  const span = (WINDOWS.some((w) => w.value === params.get('premiums')) ? params.get('premiums') : '30') as PremiumWindow;
  const key = `${partyId}|${span}`;

  // Each result carries the question it answers, so a slower old answer never shows under a new choice.
  const [loaded, setLoaded] = useState<{ key: string; data: ClientPremiums } | null>(null);
  const [failed, setFailed] = useState<{ key: string; error: ApiError } | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    const [party, w] = key.split('|') as [string, PremiumWindow];
    getClientPremiums(party, w).then(
      (data) => { if (live) setLoaded({ key, data }); },
      (error: ApiError) => { if (live) setFailed({ key, error }); },
    );
    return () => { live = false; };
  }, [key, attempt]);
  const data = loaded?.key === key ? loaded.data : null;
  const error = failed?.key === key && data === null ? failed.error : null;

  const [busy, setBusy] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  async function save(what: string, make: () => Promise<Blob>, name: string) {
    setBusy(what);
    setFileError(null);
    try {
      saveBlob(await make(), name);
    } catch {
      setFileError('The file could not be made. Try again in a moment.');
    } finally {
      setBusy(null);
    }
  }
  const download = (format: DocumentFormat) =>
    save(format, () => downloadClientPremiums(partyId, span, format), `premiums-${todayIso()}.${format}`);

  function choose(next: PremiumWindow) {
    const merged = new URLSearchParams(params);
    if (next === '30') merged.delete('premiums');
    else merged.set('premiums', next);
    setParams(merged, { replace: true });
  }

  if (error) return <ErrorPanel error={error} onRetry={() => setAttempt((n) => n + 1)} />;
  if (data === null) return <LoadingBlock label="Reading what this client owes" />;

  const cur = data.currency;
  const words = WINDOWS.find((w) => w.value === span)?.words ?? '';
  const mayRecord = canAny('collections.manage', 'policies.service');
  const band: { label: string; value: string; note: string }[] = [
    {
      label: 'Overdue now',
      value: money(data.overdueOwed, cur),
      note: data.overdueCount === 1 ? '1 invoice' : `${data.overdueCount.toLocaleString()} invoices`,
    },
    {
      label: 'Next due',
      value: data.nextDueAmount == null ? 'Nothing due' : money(data.nextDueAmount, cur),
      note: data.nextDueOn ? `on ${formatDate(data.nextDueOn)}` : 'no invoice raised ahead',
    },
    {
      label: span === 'ALL' ? 'Due on every invoice raised' : `Due ${words}`,
      value: money(data.dueInWindowOwed, cur),
      note: data.dueInWindowCount === 1 ? '1 invoice' : `${data.dueInWindowCount.toLocaleString()} invoices`,
    },
  ];

  const columns: Column<ClientPremiumRow>[] = [
    { key: 'due', header: 'Due date', nowrap: true, render: (r) => formatDate(r.dueDate) },
    {
      key: 'policy',
      header: 'Policy',
      render: (r) => (
        <Link to={`/staff/policies/${encodeURIComponent(r.policyNumber)}?tab=billing`} className="whitespace-nowrap font-mono font-medium hover:underline">
          {r.policyNumber}
        </Link>
      ),
    },
    { key: 'product', header: 'Product', secondary: true, render: (r) => r.productName ?? '—' },
    { key: 'owed', header: 'Amount', align: 'right', render: (r) => <span className="whitespace-nowrap">{money(r.owed, r.currency)}</span> },
    { key: 'status', header: 'Status', render: (r) => <StatusBadge kind="invoice" value={r.status} /> },
    ...(mayRecord
      ? [{
          key: 'record',
          header: '',
          align: 'right' as const,
          render: (r: ClientPremiumRow) => (
            <Button asChild size="sm" variant="outline">
              <Link to={`/staff/receipts/new?policy=${encodeURIComponent(r.policyNumber)}&from=client`}>Record payment</Link>
            </Button>
          ),
        }]
      : []),
  ];

  const last = data.lastReceipt;
  return (
    <div className="space-y-5">
      <Panel
        title="Premiums"
        subtitle="Every policy this client holds"
        actions={
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" pending={busy === 'xlsx'} onClick={() => void download('xlsx')}>
              <Download />
              Download Excel
            </Button>
            <Button size="sm" variant="outline" pending={busy === 'pdf'} onClick={() => void download('pdf')}>
              <Download />
              Download PDF
            </Button>
          </div>
        }
      >
        <div className="space-y-3 px-4 pb-4">
          <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3">
            {band.map((b) => (
              <div key={b.label} className="flex min-w-0 flex-col gap-1">
                <dt className="text-sm text-muted-foreground">{b.label}</dt>
                <dd className="min-w-0 whitespace-nowrap text-display">{b.value}</dd>
                <dd className="text-xs text-subtle-foreground">{b.note}</dd>
              </div>
            ))}
          </dl>
          <p className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
            <span className="text-muted-foreground">Last receipt</span>
            {last ? (
              <>
                <span className="font-medium">{last.receiptNumber}</span>
                <span className="text-muted-foreground">
                  {formatDate(last.receivedOn)} · {money(last.amount, last.currency)} · {last.policyNumber}
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  pending={busy === 'receipt'}
                  onClick={() => void save('receipt', () => downloadReceipt(last.policyNumber, last.receiptId), `${last.receiptNumber}.pdf`)}
                >
                  <Download />
                  Receipt PDF
                </Button>
              </>
            ) : (
              <span className="text-muted-foreground">No payment yet</span>
            )}
          </p>
          {fileError && (
            <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">{fileError}</p>
          )}
        </div>
      </Panel>

      <Panel title="Invoices" subtitle="Overdue first, then by due date">
        <div className="space-y-3 pb-2">
          <div role="group" aria-label="Show invoices due" className="flex flex-wrap gap-1.5 px-4">
            {WINDOWS.map((w) => (
              <FilterChip key={w.value} label={w.label} active={span === w.value} onClick={() => choose(w.value)} />
            ))}
          </div>
          {data.rows.length === 0 ? (
            <EmptyState
              title={`Nothing owed ${words}`}
              description={data.nextDueOn ? `The next premium falls due on ${formatDate(data.nextDueOn)}.` : 'No premium is raised ahead.'}
            />
          ) : (
            <DataTable columns={columns} rows={data.rows} rowKey={(r) => r.invoiceId} caption={`Premiums owed ${words}`} />
          )}
          {data.notInvoicedAhead.length > 0 && (
            <ul className="space-y-1 px-4 text-sm text-muted-foreground">
              {data.notInvoicedAhead.map((n) => (
                <li key={n.policyNumber}>
                  <Link to={`/staff/policies/${encodeURIComponent(n.policyNumber)}`} className="font-mono text-foreground hover:underline">
                    {n.policyNumber}
                  </Link>{' '}
                  {n.how === 'ONCE' ? 'is paid once — nothing is raised ahead.' : 'is paid per enrolment file — nothing is raised ahead.'}
                </li>
              ))}
            </ul>
          )}
        </div>
      </Panel>
    </div>
  );
}
