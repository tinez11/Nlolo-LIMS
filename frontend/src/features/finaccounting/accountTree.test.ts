import { describe, expect, it } from 'vitest';
import type { ChartOfAccountView } from '@/api/types';
import {
  buildAccountTree,
  filterAccounts,
  sortAccounts,
  visibleDescendantCodes,
  type AccountFilters,
} from './accountTree';

function account(
  overrides: Partial<ChartOfAccountView> & { accountCode: string },
): ChartOfAccountView {
  return {
    name: `Account ${overrides.accountCode}`,
    accountType: 'ASSET',
    normalBalance: 'DR',
    level: 1,
    postingAllowed: true,
    status: 'ACTIVE',
    currency: 'TZS',
    ...overrides,
  } as ChartOfAccountView;
}

const CHART = [
  account({ accountCode: '1000', name: 'Assets', postingAllowed: false }),
  account({
    accountCode: '1200',
    name: 'Receivables',
    parentCode: '1000',
    level: 2,
    postingAllowed: false,
  }),
  account({ accountCode: '1210', name: 'Premium Receivables', parentCode: '1200', level: 3 }),
  account({
    accountCode: '1220',
    name: 'Agent Receivables',
    parentCode: '1200',
    level: 3,
    status: 'INACTIVE',
  }),
  account({
    accountCode: '5000',
    name: 'Expenses',
    accountType: 'EXPENSE',
    postingAllowed: false,
  }),
  account({
    accountCode: '5100',
    name: 'Claims Expense',
    accountType: 'EXPENSE',
    parentCode: '5000',
    level: 2,
  }),
];

const NO_FILTERS: AccountFilters = { search: '', types: [], statuses: [], postingOnly: false };

describe('buildAccountTree', () => {
  it('nests children under their parent and returns only roots at the top', () => {
    const tree = buildAccountTree(CHART);
    expect(tree.map((n) => n.accountCode)).toEqual(['1000', '5000']);
    expect(tree[0].children.map((n) => n.accountCode)).toEqual(['1200']);
    expect(tree[0].children[0].children.map((n) => n.accountCode)).toEqual(['1210', '1220']);
  });

  it('orders siblings by account code regardless of input order', () => {
    const tree = buildAccountTree([...CHART].reverse());
    expect(tree.map((n) => n.accountCode)).toEqual(['1000', '5000']);
    expect(tree[0].children[0].children.map((n) => n.accountCode)).toEqual(['1210', '1220']);
  });

  /* A parentCode naming an account the response does not contain must not make the row
     vanish -- silently losing an account from a chart of accounts is worse than showing
     it in the wrong place. */
  it('surfaces an orphan at the root rather than dropping it', () => {
    const tree = buildAccountTree([...CHART, account({ accountCode: '9990', parentCode: '8888' })]);
    expect(tree.map((n) => n.accountCode)).toContain('9990');
  });

  it('does not hang or lose rows on a cyclic parent reference', () => {
    const cyclic = [
      account({ accountCode: '1000', parentCode: '1200' }),
      account({ accountCode: '1200', parentCode: '1000' }),
    ];
    const tree = buildAccountTree(cyclic);
    const seen: string[] = [];
    const walk = (nodes: ReturnType<typeof buildAccountTree>) => {
      for (const n of nodes) {
        seen.push(n.accountCode);
        walk(n.children);
      }
    };
    walk(tree);
    expect(seen).toHaveLength(2);
    expect(seen).toEqual(expect.arrayContaining(['1000', '1200']));
  });

  it('returns an empty array for an empty chart', () => {
    expect(buildAccountTree([])).toEqual([]);
  });
});

describe('filterAccounts', () => {
  it('matches search against both code and name, case-insensitively', () => {
    expect(filterAccounts(CHART, { ...NO_FILTERS, search: 'premium' }).map((a) => a.accountCode))
      .toEqual(['1210']);
    expect(filterAccounts(CHART, { ...NO_FILTERS, search: 'PREMIUM' }).map((a) => a.accountCode))
      .toEqual(['1210']);
    expect(filterAccounts(CHART, { ...NO_FILTERS, search: '510' }).map((a) => a.accountCode))
      .toEqual(['5100']);
    expect(filterAccounts(CHART, { ...NO_FILTERS, search: 'nothing here' })).toEqual([]);
  });

  it('ignores surrounding whitespace in the search term', () => {
    expect(filterAccounts(CHART, { ...NO_FILTERS, search: '  premium  ' }).map((a) => a.accountCode))
      .toEqual(['1210']);
  });

  it('filters by type, status and posting-allowed independently', () => {
    expect(filterAccounts(CHART, { ...NO_FILTERS, types: ['EXPENSE'] }).map((a) => a.accountCode))
      .toEqual(['5000', '5100']);
    expect(filterAccounts(CHART, { ...NO_FILTERS, statuses: ['INACTIVE'] }).map((a) => a.accountCode))
      .toEqual(['1220']);
    expect(filterAccounts(CHART, { ...NO_FILTERS, postingOnly: true }).map((a) => a.accountCode))
      .toEqual(['1210', '1220', '5100']);
  });

  it('combines filters conjunctively', () => {
    expect(
      filterAccounts(CHART, { ...NO_FILTERS, types: ['EXPENSE'], postingOnly: true }).map(
        (a) => a.accountCode,
      ),
    ).toEqual(['5100']);
  });

  it('returns everything when nothing is filtered', () => {
    expect(filterAccounts(CHART, NO_FILTERS)).toHaveLength(CHART.length);
  });
});

describe('sortAccounts', () => {
  it('sorts by name descending without mutating the input', () => {
    const input = [...CHART];
    const sorted = sortAccounts(input, 'name', 'desc');
    expect(sorted[0].name).toBe('Receivables');
    expect(input).toEqual(CHART);
  });

  it('sorts by code ascending', () => {
    expect(sortAccounts(CHART, 'accountCode', 'asc')[0].accountCode).toBe('1000');
    expect(sortAccounts(CHART, 'accountCode', 'desc')[0].accountCode).toBe('5100');
  });

  it('sorts level numerically, not as text', () => {
    const deep = [
      account({ accountCode: '1000', level: 10 }),
      account({ accountCode: '1100', level: 2 }),
    ];
    expect(sortAccounts(deep, 'level', 'asc').map((a) => a.level)).toEqual([2, 10]);
  });

  /* A blank cell floating to the top of a descending sort reads as data rather than as
     absence, so an absent value sorts last in BOTH directions. */
  it('puts a missing parentCode last regardless of direction', () => {
    expect(sortAccounts(CHART, 'parentCode', 'asc').at(-1)?.parentCode).toBeUndefined();
    expect(sortAccounts(CHART, 'parentCode', 'desc').at(-1)?.parentCode).toBeUndefined();
  });
});

describe('visibleDescendantCodes', () => {
  it('lists only what an expanded tree actually renders, in render order', () => {
    const tree = buildAccountTree(CHART);
    expect(visibleDescendantCodes(tree, new Set())).toEqual(['1000', '5000']);
    expect(visibleDescendantCodes(tree, new Set(['1000']))).toEqual(['1000', '1200', '5000']);
    expect(visibleDescendantCodes(tree, new Set(['1000', '1200']))).toEqual([
      '1000',
      '1200',
      '1210',
      '1220',
      '5000',
    ]);
  });
});
