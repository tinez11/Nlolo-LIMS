import { useState, type ClipboardEvent } from 'react';
import { Plus, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input, Select, Textarea } from '@/components/ui/input';
import { cn } from '@/lib/cn';

/**
 * A table of bands over the comma-separated text a product field has always held (the user, 2026-10-22: typing
 * `1,1,10` lines under a paragraph of instructions was not friendly). One row per band, one box per column, a row
 * added carries on from the last, and a block pasted from Excel into any box fills from there. "Edit as text" shows
 * the same value as lines, for whoever prefers it.
 *
 * The text stays the value -- the schema, its parsers and the server see exactly what they saw before. A blank row is
 * a blank line, which every parser skips, so a row just added does not vanish before it is filled.
 */
export interface BandColumn {
  header: string;
  /** `sex`: Female / Male. Otherwise a number box. */
  kind?: 'number' | 'sex';
  /** Shown in an empty box, e.g. "and after" on an open-ended to-year. */
  placeholder?: string;
  /** Kept in its place in the text, empty, and not shown -- the sex of a unisex mortality band. */
  hidden?: boolean;
}

export function BandTable({
  label,
  columns,
  value,
  onChange,
  error,
  next,
}: {
  /** The field's name: the table's accessible name, and each box's prefix. */
  label: string;
  columns: BandColumn[];
  value: string;
  onChange: (text: string) => void;
  error?: string | undefined;
  /** The row "Add row" appends, given the last one -- e.g. starting the year after it ends. */
  next?: (last: string[] | undefined) => string[];
}) {
  const [asText, setAsText] = useState(false);
  // An empty value cannot hold a blank row -- one blank line is the empty text -- so a first row added blank is held
  // here until something is typed into it.
  const [heldBlank, setHeldBlank] = useState(false);
  const blank = () => columns.map(() => '');
  const rows = value === '' && heldBlank ? [blank()] : toRows(value, columns.length);
  function addRow() {
    const row = next?.(rows.at(-1)) ?? blank();
    if (value === '' && rows.length === 0 && row.every((c) => c === '')) setHeldBlank(true);
    else write([...rows, row]);
  }
  const write = (updated: string[][]) => onChange(toText(updated));

  function setCell(r: number, c: number, cell: string) {
    write(rows.map((row, i) => (i === r ? row.map((v, j) => (j === c ? cell : v)) : row)));
  }

  /** A block from a spreadsheet, written from the box it lands in -- rows added as needed. */
  function paste(r: number, c: number, e: ClipboardEvent<HTMLInputElement | HTMLSelectElement>) {
    const text = e.clipboardData.getData('text');
    if (!/[\t\n]/.test(text.trim())) return;
    e.preventDefault();
    // Excel copies with tabs, and its numbers may carry thousands commas; only text without a tab splits on commas.
    const separator = text.includes('\t') ? /\t/ : /,/;
    const block = text.trim().split(/\r?\n/).map((line) => line.split(separator).map((v) => v.trim()));
    // Onto the columns on screen, so a hidden one (a unisex table's sex) takes nothing from the block.
    const visible = columns.map((col, i) => (col.hidden ? -1 : i)).filter((i) => i >= 0);
    const start = visible.indexOf(c);
    const updated = rows.map((row) => [...row]);
    block.forEach((line, i) => {
      while (updated.length <= r + i) updated.push(columns.map(() => ''));
      line.forEach((v, j) => {
        const target = visible[start + j];
        if (target === undefined) return;
        updated[r + i]![target] = columns[target]!.kind === 'sex' ? sexOf(v) : v.replace(/,/g, '');
      });
    });
    write(updated);
  }

  const shown = columns.filter((col) => !col.hidden);
  const grid = { gridTemplateColumns: `${shown.map((col) => (col.kind === 'sex' ? '7rem' : 'minmax(0,1fr)')).join(' ')} 2rem` };

  return (
    <div className="space-y-2">
      {asText ? (
        <Textarea rows={Math.max(3, rows.length + 1)} className="font-mono text-xs" aria-label={label} value={value}
          onChange={(e) => onChange(e.target.value)} />
      ) : (
        <div role="table" aria-label={label} className="space-y-1.5">
          {rows.length > 0 && (
            <div role="row" className="grid gap-2 px-1 text-xs text-subtle-foreground" style={grid}>
              {shown.map((col) => (
                <span key={col.header} role="columnheader" className={col.kind === 'sex' ? undefined : 'text-right'}>{col.header}</span>
              ))}
              <span aria-hidden />
            </div>
          )}
          {rows.map((row, r) => (
            <div key={r} role="row" className="grid items-center gap-2" style={grid}>
              {columns.map((col, c) => {
                if (col.hidden) return null;
                const name = `${label} row ${r + 1} ${col.header.toLowerCase()}`;
                return col.kind === 'sex' ? (
                  <Select key={col.header} inputSize="sm" aria-label={name} value={row[c] ?? ''}
                    onChange={(e) => setCell(r, c, e.target.value)} onPaste={(e) => paste(r, c, e)}>
                    <option value="">Choose…</option>
                    <option value="FEMALE">Female</option>
                    <option value="MALE">Male</option>
                  </Select>
                ) : (
                  <Input key={col.header} inputSize="sm" inputMode="decimal" className="text-right" aria-label={name}
                    placeholder={col.placeholder} value={row[c] ?? ''}
                    onChange={(e) => setCell(r, c, e.target.value)} onPaste={(e) => paste(r, c, e)} />
                );
              })}
              <Button type="button" size="icon" variant="ghost" aria-label={`Remove ${label.toLowerCase()} row ${r + 1}`}
                onClick={() => { setHeldBlank(false); write(rows.filter((_, i) => i !== r)); }}>
                <X className="size-4" />
              </Button>
            </div>
          ))}
        </div>
      )}
      {error && <p role="alert" className="text-xs text-status-danger-fg">{error}</p>}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        {!asText && (
          <Button type="button" size="sm" variant="ghost" className="-ml-2"
            onClick={addRow}>
            <Plus className="size-4" /> Add row
          </Button>
        )}
        <button type="button" className={cn('text-xs text-muted-foreground underline-offset-2 hover:underline', asText && '-ml-0')}
          aria-label={asText ? `Edit ${label.toLowerCase()} as a table` : `Edit ${label.toLowerCase()} as text`}
          onClick={() => setAsText(!asText)}>
          {asText ? 'Edit as a table' : 'Edit as text'}
        </button>
        {!asText && <span className="text-xs text-subtle-foreground">Paste from Excel into any box.</span>}
      </div>
    </div>
  );
}

/** The lines of the text as rows of boxes, each padded to the columns. A blank line is a blank row. */
function toRows(text: string, width: number): string[][] {
  if (text === '') return [];
  return text.split(/\r?\n/).map((line) => {
    const cells = /[,\t]/.test(line) ? line.split(/[,\t]/) : line.trim() === '' ? [] : line.trim().split(/\s+/);
    const row = cells.map((c) => c.trim()).slice(0, width);
    while (row.length < width) row.push('');
    return row;
  });
}

function toText(rows: string[][]): string {
  return rows.map((row) => (row.every((c) => c === '') ? '' : row.join(','))).join('\n');
}

function sexOf(v: string): string {
  const s = v.trim().toUpperCase();
  return s.startsWith('F') ? 'FEMALE' : s.startsWith('M') ? 'MALE' : '';
}
