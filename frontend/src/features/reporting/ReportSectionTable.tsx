import type { ReportSection } from '@/api/reporting';
import { DataTable, type Column } from '@/components/DataTable';
import { Panel } from '@/components/Panel';
import { cn } from '@/lib/cn';
import { capRows, formatCell } from './reportParams';

interface Line {
  index: number;
  total: boolean;
  cells: Record<string, string>;
}

/**
 * The column a section's rows are compared along with a bar: the first count (policies, claims), else the last money
 * column (a closing balance, a premium). A bar for a section of one or two rows says nothing the figures do not.
 */
function barColumn(section: ReportSection): string | null {
  if (section.rows.length < 3) return null;
  const count = section.columns.slice(1).find((c) => c.type === 'COUNT');
  if (count) return count.key;
  const money = section.columns.slice(1).filter((c) => c.type === 'MONEY');
  const key = money.length > 0 ? money[money.length - 1]!.key : null;
  // A signed column -- a ledger balance, a variance -- would draw a debit and a credit as the same bar.
  return key && section.rows.every((r) => !(r[key] ?? '').startsWith('-')) ? key : null;
}

/**
 * One report section, in a panel titled with what it holds. Figures right-aligned so they compare down a column; the
 * total row, when the section has one, last and in bold. The compared column carries a bar under each value so the
 * large rows show at a glance (2026-11-09, the user: "still too basic inside"); the bar is the server's value drawn
 * to scale, never a figure of its own.
 */
export function ReportSectionTable({ section }: { section: ReportSection }) {
  const { shown, hidden } = capRows(section.rows);
  const lines: Line[] = shown.map((cells, index) => ({ index, total: false, cells }));
  if (Object.keys(section.totals).length > 0) {
    lines.push({ index: lines.length, total: true, cells: section.totals });
  }
  // A short identifying first column (a policy number) stays on one line -- it broke at its hyphen; a long one (an
  // account and its name) wraps at a readable width instead of pushing the figures off the card (2026-11-09).
  const firstKey = section.columns[0]?.key ?? '';
  const firstIsShort = section.rows.every((r) => (r[firstKey] ?? '').length <= 24);
  const bar = barColumn(section);
  const largest = bar ? Math.max(0, ...shown.map((r) => Math.abs(Number(r[bar] ?? 0)) || 0)) : 0;
  const columns: Column<Line>[] = section.columns.map((c, i) => ({
    key: c.key,
    header: c.label,
    align: c.type === 'TEXT' || c.type === 'DATE' ? 'left' : 'right',
    nowrap: c.type === 'DATE' || (i === 0 && firstIsShort),
    ...(i === 0 && !firstIsShort ? { className: 'min-w-64' } : {}),
    render: (line) => {
      const text = <span className={cn(line.total && 'font-semibold')}>{formatCell(c.type, line.cells[c.key])}</span>;
      if (c.key !== bar || line.total || largest === 0) return text;
      const share = Math.abs(Number(line.cells[c.key] ?? 0)) / largest;
      return (
        <span className="inline-flex min-w-24 flex-col items-end gap-1">
          {text}
          <span className="block h-1 w-full rounded-full bg-control" aria-hidden>
            <span className="ml-auto block h-1 rounded-full bg-chart-1" style={{ width: `${Math.max(2, share * 100)}%` }} />
          </span>
        </span>
      );
    },
  }));
  const count = section.rows.length;
  return (
      <Panel title={section.title} {...(count > 0 ? { subtitle: `${count.toLocaleString('en-US')} ${count === 1 ? 'row' : 'rows'}` } : {})}>
        {lines.length === 0 ? (
          <p className="px-4 pb-6 text-sm text-muted-foreground">No rows for these filters.</p>
        ) : (
          <DataTable columns={columns} rows={lines} rowKey={(l) => String(l.index)} caption={section.title} />
        )}
        {hidden > 0 && (
          <p className="border-t border-border px-4 py-3 text-xs text-muted-foreground">
            Showing the first {shown.length.toLocaleString('en-US')} of {count.toLocaleString('en-US')} rows.
            Download the report for all of them.
          </p>
        )}
      </Panel>
  );
}
