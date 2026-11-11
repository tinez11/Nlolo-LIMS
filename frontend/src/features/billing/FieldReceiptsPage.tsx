import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { can } from '@/auth/access';
import { DEFAULT_PAGE_SIZE, type AgentCashTotal } from '@/api/billing';
import { FIELD_RECEIPT_STATUSES, type FieldReceiptStatus, type FieldReceiptView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { AgentName } from '@/components/AgentName';
import { PartyName } from '@/components/PartyName';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FilterChip } from '@/components/FilterChip';
import { formatInstantDay } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad, type Resource } from '@/store/createResourceSlice';
import { selectFieldReceipts, useBillingStore } from '@/store/billingStore';

/**
 * Field receipts: cash an agent says they collected, and whether the platform has matched it
 * to a payment yet.
 *
 * This screen is the answer to an alert that could not be acted on. `alert-rules.yml` carries a
 * live medium-severity `FieldReceiptReconciliationOverdue` whose description is "one or more
 * agent-captured receipts have exceeded the SLA without a matching PaymentConfirmed" — and the
 * entity had exactly one endpoint, capture. The alert named a count, no endpoint could name a
 * receipt, and the only follow-up available was a hand-written database query.
 *
 * The default view is the overdue ones, because that is what the alert is about and what
 * somebody arriving here has been paged for. Every other queue on this console defaults to
 * everything; this one defaults to the breach.
 */
export function FieldReceiptsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  // `ALL` is an explicit sentinel so "show me everything" can be told apart from a fresh
  // visit — the same idiom the client register uses for its own non-neutral default.
  const statusParam = params.get('status');
  const status: FieldReceiptStatus | undefined =
    statusParam === 'ALL'
      ? undefined
      : statusParam && (FIELD_RECEIPT_STATUSES as readonly string[]).includes(statusParam)
        ? (statusParam as FieldReceiptStatus)
        : 'RECONCILIATION_OVERDUE';

  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useBillingStore(selectFieldReceipts);
  const loadFieldReceipts = useBillingStore((s) => s.loadFieldReceipts);
  const totals = useBillingStore((s) => s.agentTotals);
  const loadAgentTotals = useBillingStore((s) => s.loadAgentTotals);
  const waitingList = useBillingStore((s) => s.agentCashWaiting);
  const loadAgentCashWaiting = useBillingStore((s) => s.loadAgentCashWaiting);
  // Agent cash whose receipt already waits for a second person (2026-11-11): offered nothing to record again.
  const waiting = new Map((waitingList.data ?? []).map((w) => [w.fieldReceiptId, w.receiptId]));

  useEffect(() => {
    void loadAgentTotals();
    void loadAgentCashWaiting();
  }, [loadAgentTotals, loadAgentCashWaiting]);

  useEffect(() => {
    void loadFieldReceipts({
      ...(status ? { status } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }, [loadFieldReceipts, status, page]);

  function update(next: { status?: FieldReceiptStatus | 'ALL'; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;
  const busy = list.status === 'loading';

  const count: Stat = {
    // Plain words (2026-11-11, findings 12): no "reconciliation SLA", no "in this tenant".
    label:
      status === 'RECONCILIATION_OVERDUE'
        ? 'collections still with the agent past their deadline'
        : status === 'PENDING_RECONCILIATION'
          ? 'collections still with the agent'
          : status === 'RECONCILED'
            ? 'collections received'
            : status === 'EXPIRED'
              ? 'collections never received'
              : 'agent collections',
    value: total,
    pending: isInitialLoad(list),
    hint: list.status === 'error' && total === null ? 'could not load' : 'oldest first',
  };

  const columns: Column<FieldReceiptView>[] = [
    {
      key: 'policyholderPartyId',
      header: 'Client',
      // Who paid the agent, beside the agent who took it (findings 10): both, on both pages.
      render: (r) => (
        <span className="flex flex-col">
          {r.policyholderPartyId ? <PartyName partyId={r.policyholderPartyId} className="font-medium" /> : '—'}
          <span className="font-mono text-xs whitespace-nowrap text-muted-foreground">{r.policyNumber ?? '—'}</span>
        </span>
      ),
    },
    {
      key: 'agentId',
      header: 'Agent',
      render: (r) => (r.agentId ? <AgentName agentId={r.agentId} withLicense={false} /> : '—'),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (r) => (r.amount ? formatMoney(r.amount) : '—'),
    },
    {
      key: 'status',
      header: 'Status',
      render: (r) => (r.status ? <StatusBadge kind="fieldReceipt" value={r.status} /> : '—'),
    },
    {
      key: 'capturedAtServer',
      header: 'Collected',
      // When it reached us; the agent's deadline and the 14-day expiry both run from here.
      render: (r) => (
        <span className="whitespace-nowrap">{r.capturedAtServer ? formatInstantDay(r.capturedAtServer) : '—'}</span>
      ),
    },
    {
      key: 'bankedReference',
      header: 'Agent says banked',
      secondary: true,
      // The agent's slip reference: what finance looks for on the bank statement before recording the money.
      render: (r) =>
        r.bankedReference ? (
          <span className="flex flex-col">
            <span className="font-mono text-xs">{r.bankedReference}</span>
            <span className="text-xs whitespace-nowrap text-muted-foreground">{formatInstantDay(r.bankedAt)}</span>
          </span>
        ) : (
          <span className="text-subtle-foreground">—</span>
        ),
    },
    {
      key: 'reconciledAt',
      header: 'Received',
      secondary: true,
      render: (r) =>
        r.reconciledAt ? (
          <span className="whitespace-nowrap">{formatInstantDay(r.reconciledAt)}</span>
        ) : (
          <span className="text-subtle-foreground">—</span>
        ),
    },
    {
      key: 'reconcile',
      header: '',
      align: 'right',
      /*
       * THE ACTION THIS QUEUE NEVER HAD.
       *
       * `FieldReceipt.reconcile()` existed server-side with no caller anywhere on the platform,
       * so a receipt could only ratchet PENDING_RECONCILIATION -> RECONCILIATION_OVERDUE and
       * stay there. The queue could fill and never drain, and the Prometheus alert, once
       * firing, would never clear.
       *
       * On the row rather than on a page, which is a deliberate exception to "acting is not
       * dismissable". There is no field-receipt record page to act from -- the row drills to
       * the POLICY, which is not where a receipt lives -- and this queue IS the work surface:
       * a reconciliation officer clears a list against a bank statement. The confirmation
       * ABOVE THE TABLE is what makes it deliberate rather than a click-through.
       *
       * The confirmation is not rendered in this cell, and that was learnt the hard way: a
       * ConfirmAct is a heading, a consequence, a reversal and two buttons, and putting that
       * inside a right-aligned table cell overflowed it across the neighbouring rows. One
       * confirmation at a time, with room, is also the honest interaction -- arming five
       * assertions about money at once is not something anybody should be offered.
       *
       * Nothing is offered on an already-RECONCILED row: there is no second thing to do.
       *
       * Since 2026-10-27 the act is RECORDING THE MONEY, not asserting a match. "Mark matched" flipped a status and
       * nothing else: no invoice was paid and nothing reached the ledger. Now the agent's cash is recorded as a
       * receipt against the policy's invoice; a second finance person confirms it, and that confirmation pays the
       * invoice, posts the money and closes this field receipt -- all by the receipt road.
       */
      render: (r) =>
        r.receiptId && waiting.has(r.receiptId) ? (
          <Link
            className="text-sm whitespace-nowrap text-muted-foreground underline-offset-2 hover:underline"
            to={`/staff/receipts/${waiting.get(r.receiptId)}?from=field-receipts`}
          >
            Waiting for a second person
          </Link>
        ) : r.receiptId && r.status !== 'RECONCILED' && can('collections.manage') ? (
          <Button asChild size="sm" variant="ghost">
            <Link to={receiptLink(r)}>Record as received</Link>
          </Button>
        ) : null,
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() => void loadFieldReceipts({ ...(status ? { status } : {}), page })}
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={
            status === 'RECONCILIATION_OVERDUE'
              ? 'No agent is holding cash past the deadline'
              : status === 'PENDING_RECONCILIATION'
                ? 'No agent is holding cash'
                : status === 'RECONCILED'
                  ? 'No agent cash received yet'
                  : status === 'EXPIRED'
                    ? 'No agent cash has expired'
                    : 'No agent cash'
          }
          description="Cash appears here when an agent records a premium a customer paid them."
          {...(status !== undefined
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ status: 'ALL' })}>
                    Show every receipt
                  </Button>
                ),
              }
            : {})}
        />
      );
    }

    return (
      <>
        {list.status === 'error' && list.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.receiptId ?? JSON.stringify(r)}
          // To the policy the cash was collected against, which is where the invoice it should
          // have matched lives. This screen finds the mismatch; the policy record explains it.
          onRowActivate={(r) => {
            if (r.policyNumber) navigate(`../policies/${encodeURIComponent(r.policyNumber)}`);
          }}
          caption="Agent cash"
        />
        {list.data && (
          <Pager page={list.data.page} busy={busy} onPageChange={(next) => update({ page: next })} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Agent cash"
        description="Cash customers paid to agents. On insurance policies it already paid the premium; savings, deposits and annuities are paid when you record it as received. Not received within 14 days, the payment is undone and dunning resumes."
        count={<CountLine {...count} />}
      />

      <div className="space-y-4 px-6 pb-6">
        <AgentTotals totals={totals} />
        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-center gap-1.5 gap-y-2 border-b border-border px-3 py-2.5">
            {FIELD_RECEIPT_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="fieldReceipt" value={value} />}
                bare
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
            <FilterChip label="All" active={status === undefined} onClick={() => update({ status: 'ALL' })} />
          </div>

          {renderBody()}
        </div>
      </div>
    </>
  );
}

/**
 * Who is holding how much, longest-held first (2026-11-11, findings 14: "Juma Senior holds TZS 545,000, oldest 10
 * days"). Hidden when no agent holds anything.
 */
function AgentTotals({ totals }: { totals: Resource<AgentCashTotal[]> }) {
  const rows = totals.data ?? [];
  if (rows.length === 0) return null;
  // Measured against when the totals were loaded, not the render: the same answer on every render.
  const now = totals.loadedAt ?? 0;
  return (
    <section aria-label="Cash still with agents" className="rounded-xl border border-border bg-surface p-4 shadow-card">
      <h2 className="mb-2 text-sm font-medium">Still with agents</h2>
      <ul className="divide-y divide-border">
        {rows.map((t) => {
          const days = Math.floor((now - new Date(t.oldestCapturedAt).getTime()) / 86_400_000);
          return (
            <li key={`${t.agentId}:${t.currency}`} className="flex flex-wrap items-baseline justify-between gap-x-4 py-1.5 text-sm">
              <AgentName agentId={t.agentId} withLicense={false} className="font-medium" />
              <span className="tabular-nums">
                {formatMoney({ amount: String(t.total), currencyCode: t.currency })}
                <span className="ml-2 text-xs text-muted-foreground">
                  {t.collections} {t.collections === 1 ? 'collection' : 'collections'} · oldest{' '}
                  {days <= 0 ? 'today' : `${days} ${days === 1 ? 'day' : 'days'} ago`}
                </span>
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/**
 * The receipt form, prefilled from an agent's field receipt (2026-10-27): its policy, its amount, cash, and the field
 * receipt it closes once a second person confirms the money.
 */
function receiptLink(r: FieldReceiptView): string {
  const params = new URLSearchParams({ method: 'CASH', fieldReceipt: r.receiptId ?? '', from: 'field-receipts' });
  if (r.policyNumber) params.set('policy', r.policyNumber);
  if (r.amount?.amount) params.set('amount', r.amount.amount);
  return '/staff/receipts/new?' + params.toString();
}
