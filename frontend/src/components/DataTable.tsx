import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import type { PageMeta } from '@/api/types';
import { Button } from './ui/button';

/**
 * Two table variants, because the API genuinely offers two things.
 *
 * `DataTable` renders rows and nothing about paging. `Pager` is mounted only for the
 * four endpoints that actually page (`/policies`, `/claims`, `/gl-postings`, group
 * members). The other eleven list endpoints return a bare unpaged array, and they
 * get the table with no pager -- a control reading "Page 1 of 12" over a
 * fully-downloaded array lies about the network and breaks the day the array grows.
 */

export interface Column<T> {
  /** Stable key, also used for the header cell. */
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  /** Right-align numerics so money and counts line up. */
  align?: 'left' | 'right';
  className?: string;
  /** Hidden below `sm`, for columns that are useful but not identifying. */
  secondary?: boolean;
  /**
   * Never wrap the header or the value -- a date, a reference. Implied by `align: 'right'`: a
   * figure broken across lines ("TZS / 3,000,000.00") no longer compares down its column.
   */
  nowrap?: boolean;
}

function noWrap<T>(column: Column<T>): boolean {
  return column.nowrap === true || column.align === 'right';
}

export interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Makes rows activatable. Rows become real buttons, not click-handled divs. */
  onRowActivate?: (row: T) => void;
  /** Highlights the row currently previewed in the drawer. */
  isRowSelected?: (row: T) => boolean;
  caption?: string;
  className?: string;
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowActivate,
  isRowSelected,
  caption,
  className,
}: DataTableProps<T>) {
  const interactive = onRowActivate !== undefined;

  return (
    // Wide tables scroll inside their own container; the page body never scrolls
    // horizontally.
    <div className={cn('w-full overflow-x-auto', className)}>
      <table className="w-full border-collapse text-sm">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          {/* Espresso's grey header band. The muted header ink is ~5:1 on it. No rule under it
              (2026-10-09): the band's own tone ends where the rows begin. */}
          <tr className="bg-band">
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                // The 1px band-coloured shadow to the right covers a seam. Chrome paints a row's
                // background cell by cell, and at fractional cell widths (150% display scaling)
                // left a white hairline between every pair of header cells once the band was toned.
                className={cn(
                  'bg-band px-4 py-2.5 text-left text-xs font-medium text-muted-foreground shadow-[1px_0_0_var(--color-band)]',
                  column.align === 'right' && 'text-right',
                  noWrap(column) && 'whitespace-nowrap',
                  column.secondary && 'hidden sm:table-cell',
                  column.className,
                )}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const selected = isRowSelected?.(row) ?? false;
            return (
              <tr
                key={rowKey(row)}
                aria-current={selected ? 'true' : undefined}
                className={cn(
                  'border-b border-border last:border-0',
                  selected && 'bg-selected',
                  interactive && !selected && 'hover:bg-hover active:bg-selected',
                )}
              >
                {columns.map((column, index) => (
                  <td
                    key={column.key}
                    className={cn(
                      'px-4 py-0 align-middle',
                      column.align === 'right' && 'text-right',
                      noWrap(column) && 'whitespace-nowrap',
                      column.secondary && 'hidden sm:table-cell',
                    )}
                  >
                    {/* The first cell carries the activation control so the row is
                        reachable by keyboard and announced as one action, instead of
                        a click handler on a <tr> that no screen reader can find. */}
                    {/* `justify-end` is what actually right-aligns a numeric cell.
                        The `text-right` on the <td> above cannot: these wrappers are
                        flex containers, and text-align does not position a flex item
                        -- justify-content does. Without this the <th> right-aligned
                        and the <td> did not, leaving every numeric column's values
                        ~100px left of their own header. It stayed invisible on
                        /policies only because every value there happens to be the
                        same character length; on /gl-postings the gap was ~140px.
                        Global `tabular-nums` exists so figures compare vertically,
                        which is worth nothing if the column does not line up. */}
                    {interactive && index === 0 ? (
                      <button
                        type="button"
                        onClick={() => onRowActivate(row)}
                        className={cn(
                          '-mx-1 flex min-h-11 w-full items-center rounded py-1.5 px-1',
                          column.align === 'right' ? 'justify-end text-right' : 'text-left',
                        )}
                      >
                        {column.render(row)}
                      </button>
                    ) : (
                      // min-h-11, not h-11. A fixed height is the right rhythm for a cell whose
                      // value fits on one line, and every table on this console was one until a
                      // credit-life member roll arrived: a borrower's full name and a minted
                      // reference each wrap to three lines in their columns, the cell kept its
                      // 44px, and the overflow ran straight through the row below it -- names
                      // printed on top of names on the one screen that answers "is this person
                      // covered". The floor keeps the rhythm; the row grows when a value needs it.
                      <div
                        className={cn(
                          'flex min-h-11 items-center py-1.5',
                          column.align === 'right' && 'justify-end',
                        )}
                      >
                        {column.render(row)}
                      </div>
                    )}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export interface PagerProps {
  page: PageMeta;
  onPageChange: (nextPage: number) => void;
  busy?: boolean;
}

/**
 * Pager for the four genuinely paged endpoints.
 *
 * `PageMeta` carries no `totalPages`, `hasNext` or cursor, so "is there another
 * page" is derived from `totalElements`. Those three fields are now `required` in
 * the spec, so this does not need to defend against undefined.
 */
export function Pager({ page, onPageChange, busy = false }: PagerProps) {
  const { page: current, pageSize, totalElements } = page;
  const from = totalElements === 0 ? 0 : current * pageSize + 1;
  const to = Math.min((current + 1) * pageSize, totalElements);
  const hasPrevious = current > 0;
  const hasNext = to < totalElements;

  return (
    <div className="flex items-center justify-between gap-4 border-t border-border px-4 py-2.5">
      <p className="text-xs text-muted-foreground" aria-live="polite">
        {totalElements === 0 ? 'No results' : `${from}–${to} of ${totalElements}`}
      </p>
      <div className="flex items-center gap-1">
        <Button
          size="icon"
          variant="ghost"
          aria-label="Previous page"
          disabled={!hasPrevious || busy}
          onClick={() => onPageChange(current - 1)}
        >
          <ChevronLeft />
        </Button>
        <Button
          size="icon"
          variant="ghost"
          aria-label="Next page"
          disabled={!hasNext || busy}
          onClick={() => onPageChange(current + 1)}
        >
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}
