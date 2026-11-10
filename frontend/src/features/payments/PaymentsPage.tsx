import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { canAny } from '@/auth/access';
import { DEFAULT_PAGE_SIZE } from '@/api/billing';
import type { PaymentDirection, PaymentRecord, RegisterState } from '@/api/payments';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectRegister, usePaymentStore } from '@/store/paymentStore';
import { PayoutViews } from '@/features/payouts/PayoutViews';
import { methodWords, paymentLink, purposeWords } from './paymentWords';

const STATES: { value: RegisterState; label: string }[] = [
  { value: 'attention', label: 'Needs attention' },
  { value: 'waiting', label: 'Waiting' },
  { value: 'done', label: 'Completed' },
  { value: 'all', label: 'All' },
];

/** One register, two names (money movement 6d, design §5.1): money in is Deposits, money out All payments out (renamed 2026-11-10). */
const WORDS: Record<PaymentDirection, { title: string; one: string; many: string; description: string; empty: string }> = {
  IN: {
    title: 'Deposits',
    one: 'deposit',
    many: 'deposits',
    description:
      'Every deposit, newest first. Opens on the ones that need attention: stuck with the provider or failed. Select one to open it.',
    empty: 'Nothing is stuck with the provider and nothing has failed. A deposit lands here the moment the rail leaves it in doubt or refuses it.',
  },
  OUT: {
    title: 'All payments out',
    one: 'payout',
    many: 'payouts',
    description:
      'Every payout by any method, newest first: claims, withdrawals, loans, commission and refunds as well as instalments. Opens on the ones that need attention. Select one to open what it pays.',
    empty: 'Nothing is stuck with the provider and nothing has failed. A payout lands here the moment the rail leaves it in doubt or refuses it.',
  },
};

/**
 * The payments register, one direction at a time, newest first (2026-10-27; split by direction in money movement 6d).
 *
 * Finance could read a payment only by its id. A premium the rail left in doubt, or a payout it failed, sat in its
 * table with no screen listing it -- and those are exactly the rows somebody has to chase. So this opens on them:
 * "Needs attention" is the stuck and the failed. A row opens what it pays: the deposit, the claim, the payout
 * instalment, otherwise its policy.
 */
export function PaymentsPage({ direction }: { direction: PaymentDirection }) {
  const words = WORDS[direction];
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const stateParam = params.get('state');
  const state: RegisterState = STATES.some((s) => s.value === stateParam) ? (stateParam as RegisterState) : 'attention';
  const policyNumber = params.get('policy') ?? '';
  // One box finds either: a receipt number (RC-…) as printed and texted to the customer, or a policy (receipts step 1).
  const receiptNumber = params.get('receipt') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);
  const [policyInput, setPolicyInput] = useState(policyNumber || receiptNumber);

  const list = usePaymentStore(selectRegister);
  const loadRegister = usePaymentStore((s) => s.loadRegister);

  useEffect(() => {
    void loadRegister({
      state,
      direction,
      ...(policyNumber ? { policyNumber } : {}),
      ...(receiptNumber ? { receiptNumber } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }, [loadRegister, state, direction, policyNumber, receiptNumber, page]);

  function update(next: { state?: RegisterState; policy?: string; receipt?: string; page?: number }) {
    const merged = new URLSearchParams(params);
    if (next.state !== undefined) merged.set('state', next.state);
    for (const key of ['policy', 'receipt'] as const) {
      const value = next[key];
      if (value === undefined) continue;
      if (value) merged.set(key, value);
      else merged.delete(key);
    }
    if (next.page !== undefined && next.page > 0) merged.set('page', String(next.page));
    else merged.delete('page');
    setParams(merged);
  }

  function applyPolicy(e: FormEvent) {
    e.preventDefault();
    const value = policyInput.trim().toUpperCase();
    // A receipt is found by any state it is in, so a person quoting one is not told it is missing because it is done.
    if (value.startsWith('RC-')) update({ receipt: value, policy: '', state: 'all' });
    else update({ policy: value, receipt: '' });
  }

  const total = list.data?.page.totalElements ?? null;
  const count: Stat = {
    label:
      state === 'attention'
        ? total === 1 ? `${words.one} needs attention` : `${words.many} need attention`
        : total === 1 ? words.one : words.many,
    value: total,
    pending: isInitialLoad(list),
    hint:
      list.status === 'error' && total === null
        ? 'could not load'
        : state === 'attention'
          ? 'stuck with the provider or failed'
          : 'in this tenant',
  };

  const columns: Column<PaymentRecord>[] = [
    {
      key: 'createdAt',
      header: 'When',
      className: 'w-44',
      render: (p) => <span className="whitespace-nowrap text-muted-foreground">{formatInstant(p.createdAt)}</span>,
    },
    {
      key: 'purpose',
      header: 'What',
      render: (p) => (
        <span className="flex flex-col gap-0.5">
          <span className="font-medium">{purposeWords(p.purpose)}</span>
          {p.policyNumber && <span className="text-xs text-muted-foreground">{p.policyNumber}</span>}
          {p.receiptNumber && <span className="font-mono text-xs text-muted-foreground">{p.receiptNumber}</span>}
        </span>
      ),
    },
    {
      key: 'counterparty',
      header: direction === 'IN' ? 'From' : 'To',
      secondary: true,
      render: (p) => (
        <span className="flex flex-col gap-0.5">
          <span>{p.counterparty}</span>
          <span className="text-xs text-muted-foreground">{methodWords(p.method)}</span>
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      className: 'w-40',
      render: (p) => <StatusBadge kind="payment" value={p.status} />,
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      className: 'w-40',
      render: (p) => (
        <span className={`whitespace-nowrap font-medium ${p.direction === 'OUT' ? 'text-foreground' : ''}`}>
          {p.direction === 'OUT' ? '−' : ''}
          {formatMoney(p.amount)}
        </span>
      ),
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;
    if (list.status === 'error' && list.error && !list.data) {
      return <ErrorPanel error={list.error} onRetry={() => update({})} />;
    }
    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={state === 'attention' ? `No ${words.one} needs attention` : `No ${words.many} match`}
          description={state === 'attention' ? words.empty : 'Try another filter, or clear the policy number.'}
          {...(state !== 'all'
            ? { action: <Button size="sm" onClick={() => update({ state: 'all' })}>Show every {words.one}</Button> }
            : {})}
        />
      );
    }
    return (
      <>
        {list.status === 'error' && list.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(p) => p.id}
          onRowActivate={(p) => {
            const to = paymentLink(p);
            if (to) navigate(to);
          }}
          caption={words.title}
        />
        {list.data && (
          <Pager page={list.data.page} busy={list.status === 'loading'} onPageChange={(next) => update({ page: next })} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={words.title}
        description={words.description}
        count={<CountLine {...count} />}
        actions={
          direction === 'IN' && canAny('collections.manage', 'policies.service', 'accounts.transact') ? (
            <Button asChild variant="primary">
              <Link to="/staff/receipts/new?from=payments">Deposit</Link>
            </Button>
          ) : undefined
        }
      />
      <div className="px-6 pb-6">
        {direction === 'OUT' && <PayoutViews current="all" />}
        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-end gap-x-4 gap-y-2 border-b border-border px-3 py-2.5">
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Status">
              {STATES.map((s) => (
                <FilterChip key={s.value} label={s.label} active={state === s.value} onClick={() => update({ state: s.value })} />
              ))}
            </div>
            <form className="ml-auto flex items-end gap-2" onSubmit={applyPolicy}>
              <FormField label={direction === 'IN' ? 'Policy or receipt number' : 'Policy number'}>
                <Input
                  inputSize="sm"
                  className="w-44 font-mono"
                  placeholder={direction === 'IN' ? 'POL-… or RC-…' : 'POL-XXXXXXXX'}
                  value={policyInput}
                  onChange={(e) => setPolicyInput(e.target.value)}
                />
              </FormField>
              <Button type="submit" size="sm">
                Apply
              </Button>
              {(policyNumber || receiptNumber) && (
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setPolicyInput('');
                    update({ policy: '', receipt: '' });
                  }}
                >
                  Clear
                </Button>
              )}
            </form>
          </div>
          {renderBody()}
        </div>
      </div>
    </>
  );
}
