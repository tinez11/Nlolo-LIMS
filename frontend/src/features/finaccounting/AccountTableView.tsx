import { ArrowDown, ArrowUp } from 'lucide-react';
import type { ReactNode } from 'react';
import type { ChartOfAccountView } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import type { SortKey } from './accountTree';

const COLUMNS: { key: SortKey; label: string; numeric?: boolean }[] = [
  { key: 'accountCode', label: 'Code' },
  { key: 'name', label: 'Account' },
  { key: 'accountType', label: 'Type' },
  { key: 'parentCode', label: 'Parent' },
  { key: 'level', label: 'Level', numeric: true },
  { key: 'status', label: 'Status' },
];

/**
 * The accountant's view: every account on one flat surface that can be searched,
 * filtered and sorted -- the questions a tree answers badly.
 *
 * Sort state lives in the page, so switching views does not silently reset it.
 */
export function AccountTableView({
  accounts,
  sortKey,
  sortDirection,
  onSort,
  renderActions,
}: {
  accounts: ChartOfAccountView[];
  sortKey: SortKey;
  sortDirection: 'asc' | 'desc';
  onSort: (key: SortKey) => void;
  renderActions: (account: ChartOfAccountView) => ReactNode;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs text-muted-foreground">
            {COLUMNS.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={column.numeric ? 'px-3 py-2 text-right font-medium' : 'px-3 py-2 font-medium'}
                aria-sort={
                  sortKey === column.key
                    ? sortDirection === 'asc'
                      ? 'ascending'
                      : 'descending'
                    : 'none'
                }
              >
                <button
                  type="button"
                  onClick={() => onSort(column.key)}
                  className={
                    column.numeric
                      ? 'ml-auto flex items-center gap-1 hover:text-foreground'
                      : 'flex items-center gap-1 hover:text-foreground'
                  }
                >
                  {column.label}
                  {sortKey === column.key &&
                    (sortDirection === 'asc' ? (
                      <ArrowUp className="h-3 w-3" aria-hidden="true" />
                    ) : (
                      <ArrowDown className="h-3 w-3" aria-hidden="true" />
                    ))}
                </button>
              </th>
            ))}
            <th scope="col" className="px-3 py-2">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {accounts.map((account) => (
            <tr key={account.accountCode} className="hover:bg-hover">
              <td className="px-3 py-2 font-mono text-xs text-muted-foreground">
                {account.accountCode}
              </td>
              <td className="px-3 py-2 font-medium">{account.name}</td>
              <td className="px-3 py-2 text-xs text-muted-foreground">{account.accountType}</td>
              <td className="px-3 py-2 font-mono text-xs text-muted-foreground">
                {account.parentCode ?? '—'}
              </td>
              {/* Right-aligned and tabular: it is a number, and this console right-aligns
                  numeric columns. */}
              <td className="px-3 py-2 text-right tabular-nums">{account.level}</td>
              <td className="px-3 py-2">
                <StatusBadge kind="account" value={account.status} />
              </td>
              <td className="px-3 py-2 text-right">{renderActions(account)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
