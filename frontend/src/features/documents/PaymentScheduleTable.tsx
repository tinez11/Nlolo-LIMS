import { useEffect, useState } from 'react';
import {
  documentFileName,
  downloadPaymentSchedule,
  getPaymentSchedule,
  type DocumentFormat,
  type PaymentScheduleLine,
  type PaymentScheduleView,
} from '@/api/documents';
import { DataTable, type Column } from '@/components/DataTable';
import { InlineError } from '@/components/InlineError';
import { TableSkeleton } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { formatMoney } from '@/lib/money';
import { DownloadButtons } from './DownloadButtons';
import { scheduleTone } from './documentLabels';

/**
 * A policy's premium payment schedule (2026-10-07) as a table: every premium due, what was paid against it,
 * when, under which receipt and by whom, its status and balance, then the totals -- with the same schedule
 * downloadable as a PDF to hand over or an Excel file. Staff and the customer who holds the policy see the same.
 */
export function PaymentScheduleTable({ policyNumber }: { policyNumber: string }) {
  const [schedule, setSchedule] = useState<PaymentScheduleView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    getPaymentSchedule(policyNumber).then(
      (s) => { if (live) { setSchedule(s); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [policyNumber]);

  async function download(format: DocumentFormat) {
    saveBlob(await downloadPaymentSchedule(policyNumber, format), documentFileName('payment-schedule', policyNumber, format));
  }

  if (error) return <div className="p-4"><InlineError error={error} /></div>;
  if (!schedule) return <TableSkeleton rows={4} />;
  const money = (amount: number | null | undefined) =>
    amount == null ? '' : formatMoney({ amount: amount.toFixed(2), currencyCode: schedule.currency ?? 'TZS' });

  const columns: Column<PaymentScheduleLine>[] = [
    { key: 'number', header: 'No.', render: (l) => l.number },
    { key: 'due', header: 'Due date', render: (l) => formatDate(l.dueDate) },
    // The cover each premium pays for, as billing recorded it (2026-10-08): from the due date on a policy billed in
    // advance, ending the day before on one billed in arrears before then.
    {
      key: 'covers',
      header: 'Cover',
      secondary: true,
      render: (l) => (l.coversFrom && l.coversTo ? `${formatDate(l.coversFrom)} – ${formatDate(l.coversTo)}` : '—'),
    },
    { key: 'amount', header: 'Amount due', align: 'right', render: (l) => money(l.amountDue) },
    { key: 'paid', header: 'Paid', align: 'right', render: (l) => money(l.amountPaid) },
    { key: 'paidOn', header: 'Paid on', render: (l) => (l.paidOn ? formatDate(l.paidOn) : '') },
    { key: 'receipt', header: 'Receipt ref', secondary: true, render: (l) => <span className="font-mono text-xs">{l.receipts ?? ''}</span> },
    { key: 'payer', header: 'Paid by', secondary: true, render: (l) => l.paidBy ?? '' },
    { key: 'status', header: 'Status', render: (l) => <span className={scheduleTone(l.status)}>{l.status}</span> },
    { key: 'balance', header: 'Balance', align: 'right', render: (l) => money(l.balance) },
  ];
  const t = schedule.totals;

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2.5 text-xs text-muted-foreground">
        <span>
          {schedule.policyholderName ?? '—'} · {schedule.productName ?? '—'} · {money(schedule.premium)}{' '}
          {frequencyWord(schedule.premiumFrequency)}
        </span>
        <DownloadButtons what="payment schedule" onDownload={download} />
      </div>
      {schedule.lines.length === 0 ? (
        <p className="px-4 py-6 text-sm text-muted-foreground">No premium has been billed on this policy yet.</p>
      ) : (
        <DataTable columns={columns} rows={schedule.lines} rowKey={(l) => l.invoiceId} caption="Premium payment schedule" />
      )}
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1 border-t border-border px-4 py-3 text-sm sm:grid-cols-5" aria-label="Schedule totals">
        <Total label="Total charged" value={money(t.charged)} />
        <Total label="Total paid" value={money(t.paid)} />
        <Total label="Outstanding now" value={money(t.outstanding)} strong={t.outstanding > 0} />
        <Total label="Still to come" value={money(t.upcoming)} />
        <Total label="Next payment" value={t.nextDueDate ? `${money(t.nextDueAmount)} on ${formatDate(t.nextDueDate)}` : 'None due'} />
      </dl>
    </div>
  );
}

function Total({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={strong ? 'font-semibold text-status-danger-fg' : 'font-semibold'}>{value}</dd>
    </div>
  );
}

function frequencyWord(f: string | null | undefined): string {
  switch (f) {
    case 'MONTHLY': return 'a month';
    case 'QUARTERLY': return 'a quarter';
    case 'ANNUALLY': return 'a year';
    case 'SINGLE': return 'once';
    default: return '';
  }
}
