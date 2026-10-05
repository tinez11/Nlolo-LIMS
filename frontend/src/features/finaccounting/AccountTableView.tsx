import { ArrowDown, ArrowUp, Coins, Folder } from 'lucide-react';
import type { ReactNode } from 'react';
import type { ChartOfAccountView } from '@/api/types';
import { cn } from '@/lib/cn';
import { AccountStatusCell } from './AccountStatusCell';
import { accountTypeLabel, postingModeLabel, type SortKey } from './accountTree';

const COLUMNS: { key: SortKey; label: string; numeric?: boolean }[] = [
  { key: 'accountCode', label: 'Code' },
  { key: 'name', label: 'Account' },
  { key: 'accountType', label: 'Type' },
  { key: 'normalBalance', label: 'Normal' },
  { key: 'mode', label: 'Mode' },
  { key: 'parentCode', label: 'Parent' },
  { key: 'level', label: 'Level', numeric: true },
  { key: 'status', label: 'Status' },
];

/** Every column, plus the unlabelled actions column, for the open form's `colSpan`. */
const CELL_COUNT = COLUMNS.length + 1;

/**
 * The accountant's view: every account on one flat surface that can be searched,
 * filtered and sorted -- the questions a tree answers badly.
 *
 * Sort state lives in the page, so switching views does not silently reset it.
 *
 * Conformed to DESIGN.md's Tables section, which it had drifted from: 16px cell
 * inset and a 44px row (it was 12px and ~33px), the type rendered as a word rather
 * than as the backend's `EXPENSE`, and an absent parent as an em dash in Subtle Ink
 * rather than as muted body text that reads like a value.
 */
export function AccountTableView({
  accounts,
  sortKey,
  sortDirection,
  onSort,
  renderActions,
  renderForm,
}: {
  accounts: ChartOfAccountView[];
  sortKey: SortKey;
  sortDirection: 'asc' | 'desc';
  onSort: (key: SortKey) => void;
  renderActions: (account: ChartOfAccountView) => ReactNode;
  /** The rename/delete form for the one row that has it open, or null. */
  renderForm: (account: ChartOfAccountView) => ReactNode;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs text-muted-foreground">
            {COLUMNS.map((column) => {
              const active = sortKey === column.key;
              return (
                <th
                  key={column.key}
                  scope="col"
                  className={cn('px-4 py-2.5 font-medium', column.numeric && 'text-right')}
                  aria-sort={
                    active ? (sortDirection === 'asc' ? 'ascending' : 'descending') : 'none'
                  }
                >
                  <button
                    type="button"
                    onClick={() => onSort(column.key)}
                    className={cn(
                      'group/sort flex items-center gap-1 hover:text-foreground',
                      column.numeric && 'ml-auto',
                    )}
                  >
                    {column.label}
                    {/* The active column shows its direction; the others show a
                        ghosted arrow on hover, because a header that is silent until
                        you have already clicked it does not look sortable. */}
                    {active ? (
                      sortDirection === 'asc' ? (
                        <ArrowUp className="h-3 w-3" aria-hidden="true" />
                      ) : (
                        <ArrowDown className="h-3 w-3" aria-hidden="true" />
                      )
                    ) : (
                      <ArrowDown
                        className="h-3 w-3 opacity-0 transition-opacity group-hover/sort:opacity-40"
                        aria-hidden="true"
                      />
                    )}
                  </button>
                </th>
              );
            })}
            <th scope="col" className="px-4 py-2.5">
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {accounts.map((account) => (
            <AccountTableRow
              key={account.accountCode}
              account={account}
              renderActions={renderActions}
              renderForm={renderForm}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AccountTableRow({
  account,
  renderActions,
  renderForm,
}: {
  account: ChartOfAccountView;
  renderActions: (account: ChartOfAccountView) => ReactNode;
  renderForm: (account: ChartOfAccountView) => ReactNode;
}) {
  const form = renderForm(account);
  const Icon = account.postingAllowed ? Coins : Folder;

  // The code cell is identical in both states, and that is deliberate: it is the
  // row's `rowheader`, so it is what identifies the row while a form is open in it.
  // Top-aligned only while a form is open, where the cell is 200px tall and a
  // vertically-centred code would float away from the row it names.
  const codeCell = (
    <th
      scope="row"
      className={cn(
        'px-4 py-1.5 text-left font-mono text-xs font-normal text-muted-foreground',
        form ? 'align-top' : 'align-middle',
      )}
    >
      {account.accountCode}
    </th>
  );

  // An open form spans the row's remaining columns rather than crowding into the
  // actions cell at the right edge, where it had ~190px of a 1200px table to lay two
  // labelled fields and two buttons out in.
  if (form) {
    return (
      <tr className="bg-surface-muted">
        {codeCell}
        <td colSpan={CELL_COUNT - 1} className="px-4 py-1.5">
          {form}
        </td>
      </tr>
    );
  }

  return (
    <tr className="group hover:bg-hover">
      {codeCell}
      <td className="px-4 py-1.5">
        {/* Centred on a one-line row and top-aligned on a two-line one, so the icon
            sits beside the NAME rather than between the name and its description. */}
        <div className={cn('flex gap-2', account.description ? 'items-start' : 'items-center')}>
          {/* The tree's own vocabulary -- coins postable, folder a header that never
              receives a leg -- carried into the flat view, which otherwise says
              nothing at all about the one property that decides whether an account
              can be used. */}
          <Icon
            className={cn(
              'h-3.5 w-3.5 shrink-0 text-muted-foreground',
              account.description && 'mt-0.5',
            )}
            aria-hidden="true"
          />
          <div className="min-w-0">
            <p className="font-medium">{account.name}</p>
            {/* Collected by both forms on this screen and, until now, rendered
                nowhere: a description you can write and never read. */}
            {account.description && (
              <p className="mt-0.5 text-xs text-subtle-foreground">{account.description}</p>
            )}
          </div>
        </div>
      </td>
      <td className="px-4 py-1.5 text-xs text-muted-foreground">
        {accountTypeLabel(account.accountType)}
      </td>
      <td className="px-4 py-1.5 font-mono text-xs text-muted-foreground">{account.normalBalance}</td>
      <td className="px-4 py-1.5 text-xs text-muted-foreground">{postingModeLabel(account.mode)}</td>
      <td className="px-4 py-1.5 font-mono text-xs text-muted-foreground">
        {account.parentCode ?? <span className="text-subtle-foreground">—</span>}
      </td>
      {/* Right-aligned and tabular: it is a number, and this console right-aligns
          numeric columns. */}
      <td className="px-4 py-1.5 text-right tabular-nums">{account.level}</td>
      <td className="px-4 py-1.5">
        <AccountStatusCell status={account.status} />
      </td>
      <td className="px-4 py-1.5 text-right">{renderActions(account)}</td>
    </tr>
  );
}
