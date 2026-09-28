import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChartOfAccountView, TrialBalanceView } from '@/api/types';
import { useFinaccountingStore } from '@/store/finaccountingStore';
import { renderScreen } from '@/test/renderScreen';
import { ChartOfAccountsPage } from './ChartOfAccountsPage';

vi.mock('@/api/finaccounting');

/**
 * The first render test on this console, and the reason it is on this screen.
 *
 * `ChartOfAccountsPage` is the largest screen on the platform, and the next task splits it.
 * A test that passes identically before and after that split is what makes the split a move
 * rather than a rewrite -- which is the whole argument for writing it first.
 *
 * Three lanes of redesign shipped before this file existed, with test suites that covered only
 * pure functions. See `@/test/renderScreen` for what that cost.
 */

/**
 * No `as ChartOfAccountView` on the way out. The cast was there first and it silently accepted
 * `normalBalance: 'DEBIT'` and `accountType: 'REVENUE'` -- neither of which this platform has;
 * they are `DR` and `INCOME`. A fixture that does not typecheck is a fixture that can drift
 * from the contract it claims to stand in for.
 */
function account(over: Partial<ChartOfAccountView> = {}): ChartOfAccountView {
  return {
    accountCode: '1000',
    name: 'Cash at bank',
    accountType: 'ASSET',
    normalBalance: 'DR',
    level: 1,
    postingAllowed: true,
    status: 'ACTIVE',
    currency: 'TZS',
    ...over,
  };
}

const RESOURCE = { status: 'success' as const, error: null, loadedAt: Date.now() };

beforeEach(() => {
  useFinaccountingStore.setState({
    chartOfAccounts: { ...RESOURCE, data: [account(), account({ accountCode: '4000', name: 'Premium income', accountType: 'INCOME', normalBalance: 'CR' })] },
    trialBalance: { ...RESOURCE, data: null },
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('ChartOfAccountsPage', () => {
  it('renders the register with the accounts the store holds', () => {
    renderScreen(<ChartOfAccountsPage />);
    expect(screen.getByRole('heading', { name: 'Chart of accounts' })).toBeInTheDocument();
    expect(screen.getByText('Cash at bank')).toBeInTheDocument();
    expect(screen.getByText('Premium income')).toBeInTheDocument();
  });

  /**
   * THE ONE ASSERTION THIS SCREEN MOST NEEDS, and the one a refactor is most likely to undo.
   *
   * `balanced` is the SERVER's verdict -- the OpenAPI schema says so in as many words:
   * "Stated by the server rather than left to a caller comparing two decimal strings." The
   * tempting client-side simplification is `totalDebit.amount === totalCredit.amount`, which
   * would be wrong for reasons no test with tidy fixtures would ever reveal.
   *
   * So the fixture is deliberately hostile: debits and credits are EQUAL while the server says
   * the ledger is out. Equal-and-balanced would pass under either implementation and prove
   * nothing; this passes only if the screen reports what it was told.
   */
  it('reports the server\'s own balanced verdict, never a comparison made here', () => {
    useFinaccountingStore.setState({
      trialBalance: {
        ...RESOURCE,
        data: {
          period: null,
          accounts: [],
          totalDebit: { amount: '1000.00', currencyCode: 'TZS' },
          totalCredit: { amount: '1000.00', currencyCode: 'TZS' },
          balanced: false,
        } as TrialBalanceView,
      },
    });

    renderScreen(<ChartOfAccountsPage />);
    expect(screen.getByText('OUT OF BALANCE')).toBeInTheDocument();
    expect(screen.queryByText('In balance')).not.toBeInTheDocument();
  });

  it('says the ledger is in balance when the server says so', () => {
    useFinaccountingStore.setState({
      trialBalance: {
        ...RESOURCE,
        data: {
          period: null,
          accounts: [],
          totalDebit: { amount: '1000.00', currencyCode: 'TZS' },
          totalCredit: { amount: '2500.00', currencyCode: 'TZS' },
          balanced: true,
        } as TrialBalanceView,
      },
    });

    renderScreen(<ChartOfAccountsPage />);
    expect(screen.getByText('In balance')).toBeInTheDocument();
    expect(screen.queryByText('OUT OF BALANCE')).not.toBeInTheDocument();
  });

  /**
   * The trial balance is inception-to-date and has to say so. A total with no period beside it
   * is a number nobody can reconcile against anything, which is the page's own comment.
   */
  it('dates the trial balance rather than presenting a bare number', () => {
    useFinaccountingStore.setState({
      trialBalance: {
        ...RESOURCE,
        data: {
          period: null,
          accounts: [],
          totalDebit: { amount: '1000.00', currencyCode: 'TZS' },
          totalCredit: { amount: '1000.00', currencyCode: 'TZS' },
          balanced: true,
        } as TrialBalanceView,
      },
    });

    renderScreen(<ChartOfAccountsPage />);
    expect(screen.getByText('inception to date')).toBeInTheDocument();
  });
});
