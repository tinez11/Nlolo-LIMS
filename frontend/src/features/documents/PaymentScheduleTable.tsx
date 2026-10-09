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
import { StatusBadge } from '@/components/StatusBadge';
import { TableSkeleton } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { formatMoney } from '@/lib/money';
import { DownloadButtons } from './DownloadButtons';
import { scheduleStatusLiteral } from './documentLabels';
import { remember, remembered } from '@/lib/remembered';

/**
 * A policy's premium payment schedule (2026-10-07) as a table: every premium due, what was paid against it,
 * when, under which receipt and by whom, its status and balance, then the totals. The same schedule downloads as
 * a PDF or Excel file through `PaymentScheduleDownloads`, which the page sets on the panel's title row. Staff
 * and the customer who holds the policy see the same.
 */
export function PaymentScheduleTable({ policyNumber }: { policyNumber: string }) {
  // Seeded from the last answer, so a second visit to Billing shows the schedule while it refetches.
  const [schedule, setSchedule] = useState<PaymentScheduleView | null>(() =>
    remembered<PaymentScheduleView>(`payment-schedule:${policyNumber}`),
  );
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    getPaymentSchedule(policyNumber).then(
      (s) => { if (live) { setSchedule(remember(`payment-schedule:${policyNumber}`, s)); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [policyNumber]);

  if (error) return <div className="p-4"><InlineError error={error} /></div>;
  if (!schedule) return <TableSkeleton rows={4} />;
  const money = (amount: number | null | undefined) =>
    amount == null ? '' : formatMoney({ amount: amount.toFixed(2), currencyCode: schedule.currency ?? 'TZS' });

  // Five columns, not ten. The work column of a policy record is ~670px at 1280 wide, and ten columns there
  // broke "Oct 9, 2026 – Jan 8, 2027" one word per line and pushed "Paid by" off the edge. What a premium
  // covers now sits under its due date, and when, how and under which receipt it was paid under the amount
  // paid: each answers a question about the figure above it, the way a ledger annotates an entry. The line
  // number is left to the PDF -- on screen the due date already says which premium a row is, and the
  // number's column was what pushed Balance out of view.
  const columns: Column<PaymentScheduleLine>[] = [
    {
      key: 'due',
      header: 'Due',
      nowrap: true,
      // The cover each premium pays for, as billing recorded it (2026-10-08): from the due date on a policy
      // billed in advance, ending the day before on one billed in arrears before then.
      render: (l) => (
        <div>
          <div>{formatDate(l.dueDate)}</div>
          {/* Billed in advance, the cover starts on the due date printed just above, so only its end is
              news; billed in arrears it does not, and the whole range is said. */}
          {l.coversFrom && l.coversTo && (
            <div className="text-xs text-muted-foreground">
              {l.coversFrom === l.dueDate
                ? `covers to ${formatDate(l.coversTo)}`
                : `covers ${formatDate(l.coversFrom)} – ${formatDate(l.coversTo)}`}
            </div>
          )}
        </div>
      ),
    },
    { key: 'amount', header: 'Amount due', align: 'right', render: (l) => money(l.amountDue) },
    {
      key: 'paid',
      header: 'Paid',
      align: 'right',
      render: (l) => (
        <div>
          <div>{money(l.amountPaid)}</div>
          {(l.paidOn || l.paidBy) && (
            <div className="text-xs text-muted-foreground">
              {[l.paidOn ? formatDate(l.paidOn) : null, l.paidBy].filter(Boolean).join(' · ')}
            </div>
          )}
          {l.receipts && <div className="font-mono text-xs text-muted-foreground">{l.receipts}</div>}
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (l) => <StatusBadge kind="invoice" value={scheduleStatusLiteral(l.status)} label={l.status} />,
    },
    { key: 'balance', header: 'Balance', align: 'right', render: (l) => money(l.balance) },
  ];
  const t = schedule.totals;

  return (
    <div>
      {schedule.lines.length === 0 ? (
        <p className="px-4 py-6 text-sm text-muted-foreground">No premium has been billed on this policy yet.</p>
      ) : (
        <DataTable columns={columns} rows={schedule.lines} rowKey={(l) => l.invoiceId} caption="Premium payment schedule" />
      )}
      {/* As many columns as fit a whole figure, not a breakpoint: `sm:grid-cols-5` gave each total ~110px of a
          670px record column, and "TZS 3,000,000.00" broke across two lines. */}
      <dl className="grid grid-cols-[repeat(auto-fill,minmax(10rem,1fr))] gap-x-6 gap-y-3 border-t border-border px-4 py-3 text-sm" aria-label="Schedule totals">
        <Total label="Total charged" value={money(t.charged)} />
        <Total label="Total paid" value={money(t.paid)} />
        <Total label="Outstanding now" value={money(t.outstanding)} strong={t.outstanding > 0} />
        <Total label="Still to come" value={money(t.upcoming)} />
        <Total label="Next payment" wrap value={t.nextDueDate ? `${money(t.nextDueAmount)} on ${formatDate(t.nextDueDate)}` : 'None due'} />
      </dl>
    </div>
  );
}

/** A figure never wraps; `wrap` is for the one total that is a sentence ("TZS 700,000.00 on Oct 8, 2027"). */
function Total({ label, value, strong, wrap }: { label: string; value: string; strong?: boolean; wrap?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn('font-semibold', !wrap && 'whitespace-nowrap', strong && 'text-status-danger-fg')}>{value}</dd>
    </div>
  );
}

/**
 * The schedule's PDF and Excel downloads, for the panel's title row (`Panel actions`). They used to sit on a
 * toolbar row of their own inside the table, ruled off beneath a line restating the policyholder, product and
 * premium -- three facts the page header and the record rail already show.
 */
export function PaymentScheduleDownloads({ policyNumber }: { policyNumber: string }) {
  async function download(format: DocumentFormat) {
    saveBlob(await downloadPaymentSchedule(policyNumber, format), documentFileName('payment-schedule', policyNumber, format));
  }
  return <DownloadButtons what="payment schedule" onDownload={download} />;
}
