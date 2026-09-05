import { ChevronRight, Coins, Folder, FolderOpen } from 'lucide-react';
import type { ReactNode } from 'react';
import { StatusBadge } from '@/components/StatusBadge';
import { cn } from '@/lib/cn';
import type { AccountNode } from './accountTree';

/**
 * The hierarchy view.
 *
 * A header account renders as a folder and cannot be posted to; a postable leaf renders
 * as coins. Expansion is controlled by the page, so the URL and the Tree/Table toggle
 * stay the single source of truth for what is on screen.
 *
 * `role="tree"`/`treeitem` with `aria-expanded` and `aria-level`: a screen reader must
 * be able to announce depth, and an indent alone conveys none of it.
 */
export function AccountTreeView({
  nodes,
  expanded,
  onToggle,
  renderActions,
}: {
  nodes: AccountNode[];
  expanded: Set<string>;
  onToggle: (accountCode: string) => void;
  renderActions: (node: AccountNode) => ReactNode;
}) {
  return (
    <ul role="tree" aria-label="Chart of accounts" className="py-1">
      {nodes.map((node) => (
        <AccountTreeRow
          key={node.accountCode}
          node={node}
          expanded={expanded}
          onToggle={onToggle}
          renderActions={renderActions}
        />
      ))}
    </ul>
  );
}

function AccountTreeRow({
  node,
  expanded,
  onToggle,
  renderActions,
}: {
  node: AccountNode;
  expanded: Set<string>;
  onToggle: (accountCode: string) => void;
  renderActions: (node: AccountNode) => ReactNode;
}) {
  const hasChildren = node.children.length > 0;
  const isOpen = expanded.has(node.accountCode);
  const Icon = hasChildren ? (isOpen ? FolderOpen : Folder) : Coins;

  return (
    <li role="treeitem" aria-expanded={hasChildren ? isOpen : undefined} aria-level={node.level}>
      <div
        className="group flex items-center gap-2 px-3 py-1.5 hover:bg-hover"
        // Indent by depth. Inline because the depth is data, not one of a fixed set of
        // classes Tailwind could enumerate ahead of time.
        style={{ paddingLeft: `${(node.level - 1) * 20 + 12}px` }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={() => onToggle(node.accountCode)}
            aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${node.name}`}
            className="rounded p-0.5 hover:bg-selected"
          >
            <ChevronRight
              className={cn('h-3.5 w-3.5 transition-transform', isOpen && 'rotate-90')}
            />
          </button>
        ) : (
          // Keeps leaf rows aligned with their expandable siblings.
          <span className="w-[18px] shrink-0" aria-hidden="true" />
        )}

        <Icon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="font-mono text-xs text-muted-foreground">{node.accountCode}</span>
        <span className="truncate text-sm font-medium">{node.name}</span>

        <span className="ml-auto flex shrink-0 items-center gap-3 text-xs text-muted-foreground">
          <span>{node.accountType}</span>
          <span>{node.normalBalance}</span>
          {/* Only when retired: an "Active" pill on 30-odd rows is noise, and absence
              of a badge already reads as the normal case. */}
          {node.status === 'INACTIVE' && <StatusBadge kind="account" value={node.status} />}
          {renderActions(node)}
        </span>
      </div>

      {hasChildren && isOpen && (
        <ul role="group">
          {node.children.map((child) => (
            <AccountTreeRow
              key={child.accountCode}
              node={child}
              expanded={expanded}
              onToggle={onToggle}
              renderActions={renderActions}
            />
          ))}
        </ul>
      )}
    </li>
  );
}
