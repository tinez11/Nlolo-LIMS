import { ChevronDown, ChevronRight } from 'lucide-react';
import { Fragment, useState } from 'react';
import { getReportParts, type ReportColumn, type ReportParams, type ReportParts, type ReportSection } from '@/api/reporting';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatCell } from './reportParams';

type Row = Record<string, string>;

interface Opened {
  loading: boolean;
  error: string | null;
  parts: ReportParts | null;
  /** Every page fetched so far, in order -- a postings list grows with "Show all". */
  rows: Row[];
}

/** The reserved row keys: for the table, never columns. */
const isHidden = (key: string) => key.startsWith('_');

/**
 * A statement whose every line opens to what it is made of (2026-11-09, the user: the computation "until it reaches
 * the figure summed up"; the tree inside the table, option B). A line opens to its accounts, an account to its
 * sub-accounts or postings, and every opened level ends with the server's own sum -- "✓ 1,102.00 + 96.30 = 1,198.30" --
 * or, if the parts do not add up, says so plainly. Nothing here adds anything up.
 */
export function ReportTree({ reportKey, params, section }: { reportKey: string; params: ReportParams; section: ReportSection }) {
  const [opened, setOpened] = useState<Record<string, Opened>>({});
  const columns = section.columns.filter((c) => !isHidden(c.key));
  const paramsKey = new URLSearchParams(params).toString();
  const cacheKey = (lineId: string) => `${paramsKey}|${lineId}`;

  async function load(lineId: string, page = 0) {
    const key = cacheKey(lineId);
    setOpened((o) => ({ ...o, [key]: { loading: true, error: null, parts: o[key]?.parts ?? null, rows: o[key]?.rows ?? [] } }));
    try {
      const parts = await getReportParts(reportKey, lineId, params, page);
      setOpened((o) => ({
        ...o,
        [key]: { loading: false, error: null, parts, rows: page === 0 ? parts.rows : [...(o[key]?.rows ?? []), ...parts.rows] },
      }));
    } catch {
      setOpened((o) => ({ ...o, [key]: { loading: false, error: 'Could not open this line. Try again.', parts: null, rows: [] } }));
    }
  }

  function toggle(lineId: string) {
    const key = cacheKey(lineId);
    if (opened[key] && !opened[key]!.error) {
      setOpened((o) => {
        const next = { ...o };
        delete next[key];
        return next;
      });
    } else {
      void load(lineId);
    }
  }

  function expandAll() {
    for (const row of section.rows) {
      if (row['_hasParts'] === 'true' && row['_lineId'] && !opened[cacheKey(row['_lineId'])]) void load(row['_lineId']);
    }
  }

  function renderRow(row: Row, level: number, label: string): React.ReactNode {
    const lineId = row['_lineId'];
    const hasParts = row['_hasParts'] === 'true' && !!lineId;
    const state = lineId ? opened[cacheKey(lineId)] : undefined;
    const kind = row['_kind'];
    const good = row['_goodWhen'];
    return (
      <Fragment key={`${lineId ?? label}-${level}`}>
        <tr
          className={cn(
            'border-b border-border',
            kind === 'SUBTOTAL' && 'font-semibold',
            kind === 'TOTAL' && 'bg-band font-semibold',
            level > 0 && 'bg-surface-muted/40',
          )}
        >
          {columns.map((c, i) => (
            <td key={c.key} className={cn('px-3 py-2.5', i === 0 ? 'text-left' : 'text-right whitespace-nowrap')}>
              {i === 0 ? (
                <span className="flex items-center gap-1" style={{ paddingLeft: `${level * 20}px` }}>
                  {hasParts ? (
                    <button
                      type="button"
                      aria-expanded={!!state && !state.error}
                      aria-label={`${state && !state.error ? 'Hide' : 'Show'} what makes up ${row['line'] ?? ''}`}
                      onClick={() => toggle(lineId)}
                      className="grid size-6 shrink-0 place-items-center rounded-md text-accent hover:bg-hover"
                    >
                      {state && !state.error ? <ChevronDown className="size-4" aria-hidden /> : <ChevronRight className="size-4" aria-hidden />}
                    </button>
                  ) : (
                    <span className="size-6 shrink-0" aria-hidden />
                  )}
                  <span>{row[c.key] ?? ''}</span>
                </span>
              ) : (
                <Cell column={c} value={row[c.key]} good={good} />
              )}
            </td>
          ))}
        </tr>
        {state && renderOpened(state, lineId!, level + 1)}
      </Fragment>
    );
  }

  function renderOpened(state: Opened, lineId: string, level: number): React.ReactNode {
    if (state.error) {
      return (
        <tr><td colSpan={columns.length} className="px-3 py-2 text-xs text-status-danger-fg" style={{ paddingLeft: `${level * 20 + 40}px` }}>{state.error}</td></tr>
      );
    }
    if (!state.parts) {
      return (
        <tr><td colSpan={columns.length} className="px-3 py-2 text-xs text-muted-foreground" style={{ paddingLeft: `${level * 20 + 40}px` }}>Opening…</td></tr>
      );
    }
    const parts = state.parts;
    const more = parts.total - state.rows.length;
    return (
      <>
        {state.rows.map((r, i) => renderRow(r, level, `${lineId}-${i}`))}
        {more > 0 && (
          <tr>
            <td colSpan={columns.length} className="px-3 py-1.5" style={{ paddingLeft: `${level * 20 + 40}px` }}>
              <Button size="sm" variant="ghost" pending={state.loading}
                onClick={() => void load(lineId, Math.floor(state.rows.length / Math.max(1, parts.size)))}>
                Show {Math.min(more, parts.size).toLocaleString('en-US')} more of {parts.total.toLocaleString('en-US')}
              </Button>
            </td>
          </tr>
        )}
        <tr>
          <td
            colSpan={columns.length}
            className={cn('px-3 py-1.5 text-xs', parts.balanced ? 'text-status-success-fg' : 'font-semibold text-status-danger-fg')}
            style={{ paddingLeft: `${level * 20 + 40}px` }}
          >
            {parts.balanced ? `✓ ${parts.check}` : `Does not add up: ${parts.check}`}
          </td>
        </tr>
      </>
    );
  }

  return (
    <Panel
      title={section.title}
      subtitle="Open a line to see what it is made of, down to the postings."
      actions={
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={expandAll}>Expand all</Button>
          <Button size="sm" variant="ghost" onClick={() => setOpened({})}>Collapse all</Button>
        </div>
      }
    >
      <div className="overflow-x-auto rounded-b-xl">
        <table className="w-full border-collapse text-sm">
          <caption className="sr-only">{section.title}</caption>
          <thead className="bg-band">
            <tr>
              {columns.map((c, i) => (
                <th key={c.key} scope="col" className={cn('px-3 py-2 text-xs font-medium text-muted-foreground', i === 0 ? 'text-left' : 'text-right')}>
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {section.rows.map((row, i) => renderRow(row, 0, `top-${i}`))}
            {Object.keys(section.totals).length > 0 && (
              <tr className="bg-band font-semibold">
                {columns.map((c, i) => (
                  <td key={c.key} className={cn('px-3 py-2.5', i === 0 ? 'text-left' : 'text-right whitespace-nowrap')}>
                    {i === 0 ? <span style={{ paddingLeft: '28px' }}>{section.totals[c.key] ?? ''}</span> : formatCell(c.type, section.totals[c.key])}
                  </td>
                ))}
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}

/** A figure; the change % as a pill, green or red by whether the change is good news, with its arrow word. */
function Cell({ column, value, good }: { column: ReportColumn; value: string | undefined; good: string | undefined }) {
  if (column.key !== 'changePercent' || !value) return <>{formatCell(column.type, value)}</>;
  const down = value.startsWith('-');
  const isGood = Number(value) === 0 || !good ? null : (down ? good === 'DOWN' : good === 'UP');
  return (
    <span
      data-delta={isGood === null ? 'none' : isGood ? 'good' : 'bad'}
      className={cn(
        'inline-flex rounded-full px-2 py-0.5 text-xs font-medium',
        isGood === null ? 'bg-control text-muted-foreground'
          : isGood ? 'bg-status-success-bg text-status-success-fg' : 'bg-status-danger-bg text-status-danger-fg',
      )}
    >
      {down ? 'down' : 'up'} {value.replace(/^-/, '')}%
    </span>
  );
}
