import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { can } from '@/auth/access';
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
import { methodWords, paymentLink, purposeWords } from './paymentWords';

const STATES: { value: RegisterState; label: string }[] = [
  { value: 'attention', label: 'Needs attention' },
  { value: 'waiting', label: 'Waiting' },
  { value: 'done', label: 'Completed' },
  { value: 'all', label: 'All' },
];

const DIRECTIONS: { value: PaymentDirection | undefined; label: string }[] = [
  { value: undefined, label: 'In and out' },
  { value: 'IN', label: 'Money in' },
  { value: 'OUT', label: 'Money out' },
];

/**
 * Every payment in and out, newest first (2026-10-27).
 *
 * Finance could read a payment only by its id. A premium the rail left in doubt, or a payout it failed, sat in its
 * table with no screen listing it -- and those are exactly the rows somebody has to chase. So this opens on them:
 * "Needs attention" is the stuck and the failed, from both ledgers. A row opens what it pays: the claim, the payout
 * instalment, otherwise its policy.
 */
export function PaymentsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const stateParam = params.get('state');
  const state: RegisterState = STATES.some((s) => s.value === stateParam) ? (stateParam as RegisterState) : 'attention';
  const directionParam = params.get('direction');
  const direction: PaymentDirection | undefined =
    directionParam === 'IN' || directionParam === 'OUT' ? directionParam : undefined;
  const policyNumber = params.get('policy') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);
  const [policyInput, setPolicyInput] = useState(policyNumber);

  const list = usePaymentStore(selectRegister);
  const loadRegister = usePaymentStore((s) => s.loadRegister);

  useEffect(() => {
    void loadRegister({
      state,
      ...(direction ? { direction } : {}),
      ...(policyNumber ? { policyNumber } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }, [loadRegister, state, direction, policyNumber, page]);

  function update(next: { state?: RegisterState; direction?: PaymentDirection | null; policy?: string; page?: number }) {
    const merged = new URLSearchParams(params);
    if (next.state !== undefined) merged.set('state', next.state);
    if (next.direction !== undefined) {
      if (next.direction) merged.set('direction', next.direction);
      else merged.delete('direction');
    }
    if (next.policy !== undefined) {
      if (next.policy) merged.set('policy', next.policy);
      else merged.delete('policy');
    }
    if (next.page !== undefined && next.page > 0) merged.set('page', String(next.page));
    else merged.delete('page');
    setParams(merged);
  }

  function applyPolicy(e: FormEvent) {
    e.preventDefault();
    update({ policy: policyInput.trim().toUpperCase() });
  }

  const total = list.data?.page.totalElements ?? null;
  const count: Stat = {
    label:
      state === 'attention'
        ? total === 1 ? 'payment needs attention' : 'payments need attention'
        : total === 1 ? 'payment' : 'payments',
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
          <span className="font-medium">
            {p.direction === 'IN' ? 'In · ' : 'Out · '}
            {purposeWords(p.purpose)}
          </span>
          {p.policyNumber && <span className="text-xs text-muted-foreground">{p.policyNumber}</span>}
        </span>
      ),
    },
    {
      key: 'counterparty',
      header: 'From or to',
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
          title={state === 'attention' ? 'No payment needs attention' : 'No payments match'}
          description={
            state === 'attention'
              ? 'Nothing is stuck with the provider and nothing has failed. A payment lands here the moment the rail leaves it in doubt or refuses it.'
              : 'Try another filter, or clear the policy number.'
          }
          {...(state !== 'all'
            ? { action: <Button size="sm" onClick={() => update({ state: 'all' })}>Show every payment</Button> }
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
          caption="Payments"
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
        title="Payments"
        description="Every payment in and out, newest first. Opens on the ones that need attention: stuck with the provider or failed. Select one to open what it pays."
        count={<CountLine {...count} />}
        actions={
          can('collections.manage') ? (
            <Button asChild variant="primary">
              <Link to="/staff/receipts/new">Record a receipt</Link>
            </Button>
          ) : undefined
        }
      />
      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-end gap-x-4 gap-y-2 border-b border-border px-3 py-2.5">
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Status">
              {STATES.map((s) => (
                <FilterChip key={s.value} label={s.label} active={state === s.value} onClick={() => update({ state: s.value })} />
              ))}
            </div>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Direction">
              {DIRECTIONS.map((d) => (
                <FilterChip
                  key={d.label}
                  label={d.label}
                  active={direction === d.value}
                  onClick={() => update({ direction: d.value ?? null })}
                />
              ))}
            </div>
            <form className="ml-auto flex items-end gap-2" onSubmit={applyPolicy}>
              <FormField label="Policy number">
                <Input
                  inputSize="sm"
                  className="w-40 font-mono"
                  placeholder="POL-XXXXXXXX"
                  value={policyInput}
                  onChange={(e) => setPolicyInput(e.target.value)}
                />
              </FormField>
              <Button type="submit" size="sm">
                Apply
              </Button>
              {policyNumber && (
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setPolicyInput('');
                    update({ policy: '' });
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
