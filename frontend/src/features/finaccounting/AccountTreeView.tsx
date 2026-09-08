import { ChevronRight, Coins, Folder, FolderOpen } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { AccountStatusCell } from './AccountStatusCell';
import { accountTypeLabel, type AccountNode } from './accountTree';

/**
 * The hierarchy view.
 *
 * A header account renders as a folder and cannot be posted to; a postable leaf renders
 * as coins. Expansion is controlled by the page, so the URL and the Tree/Table toggle
 * stay the single source of truth for what is on screen.
 *
 * `role="tree"`/`treeitem` with `aria-expanded` and `aria-level`: a screen reader must
 * be able to announce depth, and an indent alone conveys none of it.
 *
 * ## What makes it read as a ledger rather than a file browser
 *
 * Three things, and they are the reason the row is built out of fixed-width cells
 * instead of a `gap`-spaced flex:
 *
 * 1. **Indent guides.** Depth used to be a single `paddingLeft`, so four levels of
 *    hierarchy were conveyed by whitespace alone and tracing a leaf back to its block
 *    meant counting pixels. The indent is now one 20px cell per ancestor, each carrying
 *    a hairline on its leading edge, so every branch has a line running back up to the
 *    account it rolls into.
 * 2. **The five block roots are section headings, not rows.** They take Quiet Paper,
 *    a rule above, weight and full-strength ink. A chart of accounts has exactly five
 *    of them and they are the only structure a finance officer navigates by.
 * 3. **Type, normal balance and status are true columns.** They were `gap-3` text in an
 *    `ml-auto` run, so every row put them somewhere slightly different and the retired
 *    rows -- the only ones carrying a badge -- shifted them again. Fixed widths, a
 *    status slot that is reserved whether or not a badge fills it, and a header strip
 *    naming them. This is the screen that literally is a ledger; its values line up.
 */
export function AccountTreeView({
  nodes,
  expanded,
  onToggle,
  renderActions,
  renderForm,
}: {
  nodes: AccountNode[];
  expanded: Set<string>;
  onToggle: (accountCode: string) => void;
  renderActions: (node: AccountNode) => ReactNode;
  /** The rename/delete form for the one row that has it open, or null. */
  renderForm: (node: AccountNode) => ReactNode;
}) {
  return (
    <>
      {/* Presentational, and deliberately not aria-hidden: a screen reader reading the
          tree hears each treeitem's own values as text, and hearing the column names
          once on the way in costs a line and orients the same way the sighted reading
          does. */}
      <div className="flex items-center border-b border-border px-4 py-2.5 text-xs font-medium text-muted-foreground">
        <span>Account</span>
        <span className="ml-auto flex shrink-0 items-center pl-4">
          <span className="w-20">Type</span>
          <span className="w-16">Normal</span>
          <span className="w-20">Status</span>
          <span className="w-56" />
        </span>
      </div>

      <ul role="tree" aria-label="Chart of accounts" className="pb-1">
        {nodes.map((node) => (
          <AccountTreeRow
            key={node.accountCode}
            node={node}
            expanded={expanded}
            onToggle={onToggle}
            renderActions={renderActions}
            renderForm={renderForm}
          />
        ))}
      </ul>
    </>
  );
}

function AccountTreeRow({
  node,
  expanded,
  onToggle,
  renderActions,
  renderForm,
}: {
  node: AccountNode;
  expanded: Set<string>;
  onToggle: (accountCode: string) => void;
  renderActions: (node: AccountNode) => ReactNode;
  renderForm: (node: AccountNode) => ReactNode;
}) {
  const hasChildren = node.children.length > 0;
  const isOpen = expanded.has(node.accountCode);
  const isBlockRoot = node.level === 1;
  const form = renderForm(node);

  // `postingAllowed` rather than "has no children": it is the server's own answer to
  // the only question this icon is asked, and it stays right for a header account whose
  // children have not been created yet.
  const Icon = node.postingAllowed ? Coins : isOpen && hasChildren ? FolderOpen : Folder;

  return (
    <li
      role="treeitem"
      aria-expanded={hasChildren ? isOpen : undefined}
      aria-level={node.level}
      // A rule above every block root except the first, so the chart reads as five
      // blocks. `first:` works here because the roots are the direct children of the
      // `role="tree"` list; a nested group's items never take this class at all.
      className={cn(isBlockRoot && 'border-t border-border first:border-t-0')}
    >
      <div
        className={cn(
          'group flex items-stretch',
          isBlockRoot ? 'bg-surface-muted hover:bg-selected' : 'hover:bg-hover',
        )}
      >
        {/* The indent, as cells rather than as padding: one 20px cell per ancestor,
            each ruled on its leading edge so a branch traces back to its parent. The
            leading 16px cell is the table's own cell inset, so the tree's first column
            starts where the table view's does. */}
        <span className="w-4 shrink-0" aria-hidden="true" />
        {Array.from({ length: node.level - 1 }, (_, depth) => (
          <span key={depth} className="w-5 shrink-0 border-l border-border" aria-hidden="true" />
        ))}

        <div className="flex min-w-0 flex-1 items-center gap-2 py-1.5 pr-4">
          {hasChildren ? (
            <button
              type="button"
              onClick={() => onToggle(node.accountCode)}
              aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${node.name}`}
              // 24px, not the 18px it was: this is the control the whole view is
              // driven by, and it was the smallest target on the screen.
              className="flex size-6 shrink-0 items-center justify-center rounded hover:bg-selected"
            >
              <ChevronRight
                className={cn('h-3.5 w-3.5 transition-transform', isOpen && 'rotate-90')}
              />
            </button>
          ) : (
            // Keeps leaf rows aligned with their expandable siblings.
            <span className="size-6 shrink-0" aria-hidden="true" />
          )}

          <Icon
            className={cn(
              'h-3.5 w-3.5 shrink-0',
              isBlockRoot ? 'text-foreground' : 'text-muted-foreground',
            )}
            aria-hidden="true"
          />
          <span
            className={cn(
              'font-mono text-xs',
              isBlockRoot ? 'text-foreground' : 'text-muted-foreground',
            )}
          >
            {node.accountCode}
          </span>
          <span className={cn('truncate text-sm', isBlockRoot ? 'font-semibold' : 'font-medium')}>
            {node.name}
          </span>

          <span className="ml-auto flex shrink-0 items-center pl-4 text-xs text-muted-foreground">
            <span className="w-20">{accountTypeLabel(node.accountType)}</span>
            {/* DR/CR unexpanded: it is the abbreviation this audience writes itself,
                and the header strip above says which column it is. */}
            <span className="w-16">{node.normalBalance}</span>
            {/* A reserved slot on every row, not just the retired ones: a badge that
                appears without a slot behind it shunts every column left of it, which
                is how these values stopped lining up. What goes in it is
                `AccountStatusCell`'s decision -- text for the norm, a badge for
                anything worth stopping on. */}
            <span className="w-20">
              <AccountStatusCell status={node.status} />
            </span>
            {renderActions(node)}
          </span>
        </div>
      </div>

      {/* Indented to the row's own content, so a rename reads as belonging to the
          account above it rather than floating at the right edge of the row. */}
      {form && (
        <div
          className="pr-4 pb-2"
          style={{ paddingLeft: `${(node.level - 1) * 20 + 40}px` }}
        >
          {form}
        </div>
      )}

      {hasChildren && isOpen && (
        <ul role="group">
          {node.children.map((child) => (
            <AccountTreeRow
              key={child.accountCode}
              node={child}
              expanded={expanded}
              onToggle={onToggle}
              renderActions={renderActions}
              renderForm={renderForm}
            />
          ))}
        </ul>
      )}
    </li>
  );
}
