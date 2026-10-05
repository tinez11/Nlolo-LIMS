import type { AccountStatus, AccountType, ChartOfAccountView, PostingMode } from '@/api/types';

/**
 * Tree assembly, filtering and sorting for the chart of accounts, as pure functions.
 *
 * `GET /chart-of-accounts` answers with a flat, unpaged array on purpose -- a chart is
 * bounded reference data (36 rows seeded, a few hundred for a real Finance-authored
 * chart), so the client owning the hierarchy is what makes the Tree/Table toggle
 * instant and keeps search and sort off the network entirely.
 *
 * Everything here is pure: a flat array in, a new structure out. Nothing mutates its
 * argument, so a memoised source list stays stable across renders.
 */

export type AccountNode = ChartOfAccountView & { children: AccountNode[] };

export interface AccountFilters {
  search: string;
  types: AccountType[];
  statuses: AccountStatus[];
  /** The posting guide's modes; empty means every mode. Optional so older callers need not name it. */
  modes?: PostingMode[];
  postingOnly: boolean;
}

export type SortKey =
  | 'accountCode'
  | 'name'
  | 'accountType'
  | 'normalBalance'
  | 'mode'
  | 'parentCode'
  | 'level'
  | 'status';

/** The posting guide's modes in a finance officer's words: who may post to the account. */
const MODE_LABEL: Record<PostingMode, string> = {
  AUTO: 'Automatic',
  MAN: 'Manual',
  BOTH: 'Both',
};

export function postingModeLabel(mode: string): string {
  return MODE_LABEL[mode as PostingMode] ?? mode;
}

/**
 * The five blocks in the words a finance officer uses for them.
 *
 * `ASSET` is what the wire says and `Asset` is what a person reads; the console
 * rendered the wire spelling in a Type column, in five filter chips and on every
 * tree row. Two spellings because the two jobs differ: a column labels one row, a
 * filter chip names the whole block it narrows to.
 */
const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  ASSET: 'Asset',
  LIABILITY: 'Liability',
  EQUITY: 'Equity',
  INCOME: 'Income',
  EXPENSE: 'Expense',
  CLEARING: 'Clearing',
};

const ACCOUNT_TYPE_PLURAL: Record<AccountType, string> = {
  ASSET: 'Assets',
  LIABILITY: 'Liabilities',
  EQUITY: 'Equity',
  INCOME: 'Income',
  EXPENSE: 'Expenses',
  CLEARING: 'Clearing',
};

/**
 * Both fall back to the raw literal rather than to a blank or a guess. A type this
 * build has never heard of is a newly-added backend enum, and the same ethic
 * `StatusBadge` applies to an unrecognised status applies here: show what the server
 * said instead of dressing it up as something else.
 */
export function accountTypeLabel(type: string): string {
  return ACCOUNT_TYPE_LABEL[type as AccountType] ?? type;
}

export function accountTypePlural(type: string): string {
  return ACCOUNT_TYPE_PLURAL[type as AccountType] ?? type;
}

/**
 * A flat array in, roots out.
 *
 * Two defensive cases, both deliberate. An account whose `parentCode` names a row the
 * response does not contain is surfaced at the ROOT rather than dropped -- silently
 * losing an account from a chart of accounts is worse than showing it in the wrong
 * place. And a cyclic parent chain -- which the backend's prefix rule makes
 * structurally impossible, but which this function must not hang on regardless -- is
 * broken by refusing to attach a node beneath its own descendant.
 */
export function buildAccountTree(accounts: ChartOfAccountView[]): AccountNode[] {
  const nodes = new Map<string, AccountNode>();
  for (const account of accounts) {
    nodes.set(account.accountCode, { ...account, children: [] });
  }

  const roots: AccountNode[] = [];

  for (const node of nodes.values()) {
    const parent = node.parentCode ? nodes.get(node.parentCode) : undefined;
    if (!parent || parent.accountCode === node.accountCode || isDescendantOf(parent, node, nodes)) {
      roots.push(node);
      continue;
    }
    parent.children.push(node);
  }

  sortDeep(roots);
  return roots;
}

/** Walks up from `candidate` looking for `node`: true when attaching would close a loop. */
function isDescendantOf(
  candidate: AccountNode,
  node: AccountNode,
  nodes: Map<string, AccountNode>,
): boolean {
  const seen = new Set<string>();
  let cursor: AccountNode | undefined = candidate;
  while (cursor && !seen.has(cursor.accountCode)) {
    if (cursor.accountCode === node.accountCode) return true;
    seen.add(cursor.accountCode);
    cursor = cursor.parentCode ? nodes.get(cursor.parentCode) : undefined;
  }
  return false;
}

function sortDeep(list: AccountNode[]): void {
  list.sort((a, b) => a.accountCode.localeCompare(b.accountCode));
  for (const node of list) {
    sortDeep(node.children);
  }
}

export function filterAccounts(
  accounts: ChartOfAccountView[],
  filters: AccountFilters,
): ChartOfAccountView[] {
  const needle = filters.search.trim().toLowerCase();
  return accounts.filter((account) => {
    if (
      needle &&
      !account.accountCode.toLowerCase().includes(needle) &&
      !(account.name ?? '').toLowerCase().includes(needle)
    ) {
      return false;
    }
    if (filters.types.length > 0 && !filters.types.includes(account.accountType)) return false;
    if (filters.statuses.length > 0 && !filters.statuses.includes(account.status)) return false;
    if (filters.modes && filters.modes.length > 0 && !filters.modes.includes(account.mode)) return false;
    if (filters.postingOnly && !account.postingAllowed) return false;
    return true;
  });
}

/** Returns a NEW array -- the caller's list is never mutated. */
export function sortAccounts(
  accounts: ChartOfAccountView[],
  key: SortKey,
  direction: 'asc' | 'desc',
): ChartOfAccountView[] {
  const sign = direction === 'asc' ? 1 : -1;
  return [...accounts].sort((a, b) => {
    const left = a[key];
    const right = b[key];
    // An absent value always sorts last, in BOTH directions: a blank cell floating to
    // the top of a descending sort reads as data rather than as absence.
    if (left === undefined || left === null) return right === undefined || right === null ? 0 : 1;
    if (right === undefined || right === null) return -1;
    if (typeof left === 'number' && typeof right === 'number') return (left - right) * sign;
    return String(left).localeCompare(String(right)) * sign;
  });
}

/** The codes the tree currently renders, in render order -- for keyboard navigation. */
export function visibleDescendantCodes(nodes: AccountNode[], expanded: Set<string>): string[] {
  const out: string[] = [];
  const walk = (list: AccountNode[]) => {
    for (const node of list) {
      out.push(node.accountCode);
      if (expanded.has(node.accountCode)) walk(node.children);
    }
  };
  walk(nodes);
  return out;
}
