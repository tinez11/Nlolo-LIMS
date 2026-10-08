import {
  documentFileName,
  downloadSavingsStatement,
  type DocumentFormat,
  type SavingsStatementLine,
  type SavingsStatementView,
} from '@/api/documents';
import { DataTable, type Column } from '@/components/DataTable';
import { formatDate } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { formatMoney } from '@/lib/money';
import { DownloadButtons } from './DownloadButtons';

type Row = SavingsStatementLine & { key: string; emphasis?: boolean };

/**
 * A savings account statement (2026-10-07) as a table: the opening balance, every entry with money in, money
 * out and the balance after it, the closing balance, then each kind of movement totalled -- and the same period
 * downloadable as a PDF or an Excel file.
 */
export function SavingsStatementTable({ statement }: { statement: SavingsStatementView }) {
  const money = (amount: number | null | undefined) =>
    amount == null ? '' : formatMoney({ amount: amount.toFixed(2), currencyCode: statement.currency });
  const period = { from: statement.periodFrom, to: statement.periodTo };

  async function download(format: DocumentFormat) {
    saveBlob(await downloadSavingsStatement(statement.policyNumber, format, period.from, period.to),
      documentFileName('savings-statement', statement.policyNumber, format, period));
  }

  const rows: Row[] = [
    { key: 'opening', date: statement.periodFrom, description: 'Opening balance', moneyIn: null, moneyOut: null,
      balance: statement.openingBalance, emphasis: true },
    ...statement.lines.map((l, i) => ({ ...l, key: String(i) })),
    { key: 'closing', date: statement.periodTo, description: 'Closing balance', moneyIn: null, moneyOut: null,
      balance: statement.closingBalance, emphasis: true },
  ];
  const bold = (r: Row, text: string) => (r.emphasis ? <strong>{text}</strong> : text);
  const columns: Column<Row>[] = [
    { key: 'date', header: 'Date', render: (r) => formatDate(r.date) },
    { key: 'description', header: 'Description', render: (r) => bold(r, r.description) },
    { key: 'in', header: 'Money in', align: 'right', render: (r) => money(r.moneyIn) },
    { key: 'out', header: 'Money out', align: 'right', render: (r) => money(r.moneyOut) },
    { key: 'balance', header: 'Balance', align: 'right', render: (r) => bold(r, money(r.balance)) },
  ];

  return (
    <div className="rounded-md border border-border">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-2.5 text-xs text-muted-foreground">
        <span>
          {statement.policyholderName ?? '—'} · {formatDate(statement.periodFrom)} to {formatDate(statement.periodTo)}
        </span>
        <DownloadButtons what="savings statement" onDownload={download} />
      </div>
      <DataTable columns={columns} rows={rows} rowKey={(r) => r.key} caption="Savings account statement" />
      {statement.totals.length > 0 && (
        <dl className="grid grid-cols-2 gap-x-6 gap-y-1 border-t border-border px-4 py-3 text-sm sm:grid-cols-4" aria-label="Statement totals">
          {statement.totals.map((t) => (
            <div key={t.label}>
              <dt className="text-xs text-muted-foreground">{t.label}</dt>
              <dd className="font-semibold">{money(t.amount)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}
