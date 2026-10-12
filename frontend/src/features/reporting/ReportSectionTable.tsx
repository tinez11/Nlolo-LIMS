import type { ReportSection } from '@/api/reporting';
import { DataTable, type Column } from '@/components/DataTable';
import { cn } from '@/lib/cn';
import { capRows, formatCell } from './reportParams';

interface Line {
  index: number;
  total: boolean;
  cells: Record<string, string>;
}

/** One report section. Figures right-aligned so they compare down a column; the total row, when the section
 * has one, last and in bold. */
export function ReportSectionTable({ section }: { section: ReportSection }) {
  const { shown, hidden } = capRows(section.rows);
  const lines: Line[] = shown.map((cells, index) => ({ index, total: false, cells }));
  if (Object.keys(section.totals).length > 0) {
    lines.push({ index: lines.length, total: true, cells: section.totals });
  }
  const columns: Column<Line>[] = section.columns.map((c) => ({
    key: c.key,
    header: c.label,
    align: c.type === 'TEXT' || c.type === 'DATE' ? 'left' : 'right',
    render: (line) => <span className={cn(line.total && 'font-semibold')}>{formatCell(c.type, line.cells[c.key])}</span>,
  }));
  return (
    <section className="space-y-2">
      <h2 className="text-sm font-semibold">{section.title}</h2>
      <div className="rounded-lg border border-border bg-surface">
        {lines.length === 0 ? (
          <p className="px-4 py-6 text-sm text-muted-foreground">No rows for these filters.</p>
        ) : (
          <DataTable columns={columns} rows={lines} rowKey={(l) => String(l.index)} caption={section.title} />
        )}
      </div>
      {hidden > 0 && (
        <p className="text-xs text-muted-foreground">
          Showing the first {shown.length.toLocaleString('en-US')} of {section.rows.length.toLocaleString('en-US')} rows.
          Download the report for all of them.
        </p>
      )}
    </section>
  );
}
