import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { BalanceStatementLine } from '@/api/types';
import { can } from '@/auth/access';
import { DataTable, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectBalance, selectBalanceStatement, usePolicyStore } from '@/store/policyStore';
import { BalanceWithdrawals } from './BalanceWithdrawals';

/** What each statement line is, in the words the console uses for money (design §5.1). */
const BALANCE_ENTRY_LABEL: Record<BalanceStatementLine['type'], string> = {
  DEPOSIT: 'Deposit',
  PREMIUM_PAID: 'Premium paid',
  WITHDRAWAL: 'Withdrawal',
  REVERSAL: 'Deposit reversed',
  PREMIUM_REOPENED: 'Premium reopened',
};

/**
 * The policy's balance (money movement step 5, design §7.1): what it holds, and how far ahead that pays premiums --
 * both the server's figures. Recording a deposit is a full page, so this only links there.
 */
export function BalancePanel({ policyNumber }: { policyNumber: string }) {
  const balance = usePolicyStore(selectBalance(policyNumber));
  const loadBalance = usePolicyStore((s) => s.loadBalance);

  useEffect(() => {
    void loadBalance(policyNumber);
  }, [policyNumber, loadBalance]);

  if (isInitialLoad(balance)) return <LoadingBlock />;
  if (balance.status === 'error' && balance.error && balance.data === null) {
    return <ErrorPanel error={balance.error} onRetry={() => void loadBalance(policyNumber)} />;
  }
  if (!balance.data) return null;
  const covers = balance.data.coversPremiumsTo;
  const depositHref = `/staff/receipts/new?policy=${encodeURIComponent(policyNumber)}&from=policy`;

  return (
    <div className="space-y-3 px-4 py-3">
      <dl className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        <Field label="Balance" value={formatMoney(balance.data.balance)} />
        <Field
          label="Covers premiums to"
          value={covers ? formatDate(covers) : 'No premium ahead'}
          {...(covers ? { note: 'The last premium due that this balance pays in full' } : {})}
        />
      </dl>
      {(can('collections.manage') || can('payouts.manage')) && (
        <div className="flex flex-wrap gap-1.5">
          {can('collections.manage') && (
            <Button asChild size="sm" variant="primary">
              <Link to={depositHref}>Record deposit</Link>
            </Button>
          )}
          {can('payouts.manage') && (
            <Button asChild size="sm" variant="outline">
              <Link to={`/staff/policies/${encodeURIComponent(policyNumber)}/withdraw`}>Withdraw</Link>
            </Button>
          )}
        </div>
      )}
      <BalanceWithdrawals policyNumber={policyNumber} />
    </div>
  );
}

/**
 * The balance's statement, newest first: money in and money out in their own columns, the balance after each line
 * (the server's running sum), as the savings account's ledger reads. Older lines come a page at a time.
 */
export function BalanceStatementPanel({ policyNumber }: { policyNumber: string }) {
  const statement = usePolicyStore(selectBalanceStatement(policyNumber));
  const loadStatement = usePolicyStore((s) => s.loadBalanceStatement);

  useEffect(() => {
    void loadStatement(policyNumber);
  }, [policyNumber, loadStatement]);

  if (isInitialLoad(statement)) return <LoadingBlock />;
  if (statement.status === 'error' && statement.error && statement.data === null) {
    return <ErrorPanel error={statement.error} onRetry={() => void loadStatement(policyNumber)} />;
  }
  if (!statement.data) return null;
  const { lines, total } = statement.data;
  if (lines.length === 0) {
    return (
      <EmptyState
        title="Nothing on the balance yet"
        description="A deposit lands here first and pays the premiums due, oldest first."
      />
    );
  }

  const seqById = new Map(lines.map((l) => [l.entryId, l.seq]));
  const moneyIn = (l: BalanceStatementLine) => !l.amount.amount.startsWith('-');
  const columns: Column<BalanceStatementLine>[] = [
    { key: 'date', header: 'Date', nowrap: true, render: (l) => formatDate(l.at) },
    {
      key: 'entry',
      header: 'Entry',
      render: (l) => (
        <span className="block min-w-0">
          {l.type === 'DEPOSIT' && l.paymentId && l.method !== 'MOBILE_MONEY' ? (
            <Link to={`/staff/receipts/${encodeURIComponent(l.paymentId)}`} className="font-medium underline">
              {BALANCE_ENTRY_LABEL[l.type]}
            </Link>
          ) : (
            <span className="font-medium">{BALANCE_ENTRY_LABEL[l.type]}</span>
          )}{' '}
          <span className="text-xs text-subtle-foreground">#{l.seq}</span>
          {(l.reference || l.reversesEntryId) && (
            <span className="block text-xs text-muted-foreground">
              {l.reference}
              {l.reference && l.reversesEntryId ? ' · ' : ''}
              {l.reversesEntryId ? `reverses #${seqById.get(l.reversesEntryId) ?? '?'}` : ''}
            </span>
          )}
        </span>
      ),
    },
    { key: 'in', header: 'Money in', align: 'right', render: (l) => (moneyIn(l) ? formatMoney(l.amount) : '') },
    {
      key: 'out',
      header: 'Money out',
      align: 'right',
      render: (l) => (moneyIn(l) ? '' : formatMoney({ ...l.amount, amount: l.amount.amount.slice(1) })),
    },
    {
      key: 'balance',
      header: 'Balance',
      align: 'right',
      render: (l) => <span className="font-medium">{formatMoney(l.balanceAfter)}</span>,
    },
  ];
  const nextPage = Math.ceil(lines.length / statement.data.size);

  return (
    <section className="space-y-2 px-4 py-3">
      <p className="text-xs text-muted-foreground">
        {total} {total === 1 ? 'entry' : 'entries'}, newest first
      </p>
      <div className="overflow-hidden rounded-lg border border-border">
        <DataTable caption="Statement" columns={columns} rows={lines} rowKey={(l) => l.entryId} />
      </div>
      {lines.length < total && (
        <Button
          size="sm"
          variant="ghost"
          pending={statement.status === 'loading'}
          onClick={() => void loadStatement(policyNumber, nextPage)}
        >
          Show older entries
        </Button>
      )}
    </section>
  );
}
