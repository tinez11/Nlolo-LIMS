import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { DEFAULT_PAGE_SIZE } from '@/api/billing';
import { FIELD_RECEIPT_STATUSES, type FieldReceiptStatus, type FieldReceiptView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { AgentName } from '@/components/AgentName';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FilterChip } from '@/components/FilterChip';
import { ConfirmAct } from '@/components/ConfirmAct';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectFieldReceipts, selectReconciling, useBillingStore } from '@/store/billingStore';

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

  // ONE confirmation at a time, held by the page rather than by each row. Arming several
  // assertions about money at once is not an interaction anybody should be offered, and the
  // confirmation needs more room than a table cell has.
  const [confirming, setConfirming] = useState<FieldReceiptView | null>(null);
  const [reconcilingId, setReconcilingId] = useState<string | null>(null);
  const reconcile = useBillingStore((s) => s.reconcileFieldReceipt);
  const reconcileState = useBillingStore(selectReconciling(reconcilingId ?? confirming?.receiptId ?? ''));

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
    label:
      status === 'RECONCILIATION_OVERDUE'
        ? 'overdue receipts'
        : status === 'PENDING_RECONCILIATION'
          ? 'receipts awaiting a match'
          : status === 'RECONCILED'
            ? 'matched receipts'
            : 'field receipts',
    value: total,
    pending: isInitialLoad(list),
    hint:
      list.status === 'error' && total === null
        ? 'could not load'
        : status === 'RECONCILIATION_OVERDUE'
          ? 'past the reconciliation SLA'
          : 'in this tenant',
  };

  const columns: Column<FieldReceiptView>[] = [
    {
      key: 'policyNumber',
      header: 'Policy',
      render: (r) => <span className="font-mono font-medium">{r.policyNumber ?? '—'}</span>,
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
      header: 'Reached us',
      // The SLA runs from this timestamp, not from the agent's own clock, so this is the
      // column the overdue state is measured against.
      render: (r) => (r.capturedAtServer ? formatInstant(r.capturedAtServer) : '—'),
    },
    {
      key: 'capturedAtClient',
      header: 'Collected',
      secondary: true,
      /*
       * The agent's own timestamp, which can be hours earlier than the one above if the
       * phone was offline. Kept beside it rather than instead of it: a large gap says "the
       * field was offline", a small one says "we were slow", and those lead somewhere
       * different.
       */
      render: (r) => (r.capturedAtClient ? formatInstant(r.capturedAtClient) : '—'),
    },
    {
      key: 'reconciledAt',
      header: 'Matched',
      secondary: true,
      // An em dash, not a blank: unmatched is the fact this screen exists to show.
      render: (r) =>
        r.reconciledAt ? (
          formatInstant(r.reconciledAt)
        ) : (
          <span className="text-subtle-foreground">—</span>
        ),
    },
    {
      key: 'agentId',
      header: 'Agent',
      secondary: true,
      /*
       * The name, now that there is a way to it. This column showed the first eight
       * characters of the uuid with the rest on the title, on the reasoning that `PartyName`
       * takes a PARTY id while this is a distribution AgentProfile id and nothing resolved
       * one -- true when it was written. `AgentName` makes the two hops (agent -> partyId ->
       * party) and caches each, so a register of twenty receipts from four agents costs
       * eight requests, not eighty.
       *
       * No licence number beside it: this column is scanned, not read, and the amount and
       * policy number next to it are what the row is for.
       */
      render: (r) =>
        r.agentId ? <AgentName agentId={r.agentId} withLicense={false} /> : '—',
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
       */
      render: (r) =>
        r.receiptId && r.status !== 'RECONCILED' ? (
          <Button
            size="sm"
            variant="ghost"
            disabled={reconcilingId === r.receiptId}
            onClick={() => setConfirming(r)}
          >
            {reconcilingId === r.receiptId ? 'Matching…' : 'Mark matched'}
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
              ? 'Nothing is overdue for reconciliation'
              : status === 'PENDING_RECONCILIATION'
                ? 'Nothing is waiting to be matched'
                : status === 'RECONCILED'
                  ? 'No matched receipts'
                  : 'No field receipts'
          }
          description={
            status === 'RECONCILIATION_OVERDUE'
              ? 'Every receipt an agent has captured either matched a payment or is still inside its SLA.'
              : 'A receipt appears here when an agent captures a premium payment in the field.'
          }
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
          caption="Field receipts"
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
        title="Field receipts"
        description="Premium an agent collected in the field, and whether the platform has matched it to a payment. Oldest unmatched first."
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            {FIELD_RECEIPT_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="fieldReceipt" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
            <FilterChip label="All" active={status === undefined} onClick={() => update({ status: 'ALL' })} />
          </div>

          {confirming && (
            <ReconcileConfirm
              receipt={confirming}
              busy={reconcileState.status === 'loading'}
              onConfirm={() => {
                const id = confirming.receiptId!;
                setConfirming(null);
                setReconcilingId(id);
                void reconcile(id).finally(() => setReconcilingId(null));
              }}
              onCancel={() => setConfirming(null)}
            />
          )}

          {reconcileState.status === 'error' && reconcileState.error && (
            <p
              role="alert"
              className="border-b border-border bg-status-danger-bg px-4 py-2 text-xs text-status-danger-fg"
            >
              Could not mark it matched — {reconcileState.error.detail ?? reconcileState.error.title}
              {reconcileState.error.traceId && (
                <span className="ml-2 font-mono text-[10px] opacity-80">
                  ({reconcileState.error.traceId})
                </span>
              )}
            </p>
          )}

          {renderBody()}
        </div>
      </div>
    </>
  );
}

/**
 * The confirmation for one receipt, rendered above the table where it has room.
 *
 * Separate from the row button on purpose — see the reconcile column's own note. The confirming
 * verb deliberately differs from the arming one ("Mark matched" → "Record the match"), which is
 * ConfirmAct's stated rule: two identical-looking buttons a click apart is how the second click
 * becomes as automatic as the first. The waive flow makes the same distinction.
 */
function ReconcileConfirm({
  receipt,
  busy,
  onConfirm,
  onCancel,
}: {
  receipt: FieldReceiptView;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="border-b border-border px-3 py-2.5">
      <ConfirmAct
        heading="Mark this receipt as matched?"
        // The real values, not a generic warning: this is a statement about specific money.
        consequence={
          <>
            Record that{' '}
            <strong>{receipt.amount ? formatMoney(receipt.amount) : 'this amount'}</strong> collected
            against <strong>{receipt.policyNumber}</strong> has been matched to money actually
            received.
          </>
        }
        /*
         * Honest about the route back rather than a blanket "this is permanent": there genuinely
         * is no un-reconcile on this platform -- FieldReceipt has no such transition -- but the
         * assertion is recorded with its actor, so it can be traced rather than merely regretted.
         */
        reversal="There is no un-match: the receipt stays matched. The event records who matched it, so a mistake is traceable in the event journal rather than reversible here."
        confirmLabel="Record the match"
        tone="primary"
        busy={busy}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    </div>
  );
}
