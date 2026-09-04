import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  DEFAULT_PAGE_SIZE,
  DUNNING_LEVELS,
  LAPSE_RECOMMENDATION_LEVEL,
} from '@/api/billing';
import type { ArrearsCaseView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FilterChip } from '@/components/FilterChip';
import { formatDate, formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectArrears, useBillingStore } from '@/store/billingStore';

/**
 * The collections queue: who is behind on premium, how far it has escalated, and how much.
 *
 * This screen exists because the platform could escalate a policy through five dunning levels
 * and recommend it for lapse, and nobody could see any of it. There was no tenant-wide billing
 * read at all — every billing endpoint was keyed by a policy number or an invoice id you had to
 * know already — so dunning surfaced only as an `L3` badge on one invoice row of one policy
 * record. A collections officer had no way to ask "who is overdue".
 *
 * Ordered worst-first by the server, and that order is the work order rather than a default:
 * dunning level descending, then longest-standing. The page a person opens is the page they
 * should work.
 */
export function ArrearsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  // A bare visit shows the OPEN queue, not everything. Unlike the client register — where
  // defaulting to a filter hid most of the register — the open cases are the entirety of the
  // work here, and resolved history is a deliberate lookback. The chips say which you are in.
  const resolvedParam = params.get('resolved');
  const resolved = resolvedParam === 'true' ? true : resolvedParam === 'ALL' ? undefined : false;

  const levelParam = Number(params.get('minDunningLevel') ?? '');
  const minDunningLevel = DUNNING_LEVELS.includes(levelParam as (typeof DUNNING_LEVELS)[number])
    ? levelParam
    : undefined;

  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useBillingStore(selectArrears);
  const loadArrears = useBillingStore((s) => s.loadArrears);

  useEffect(() => {
    void loadArrears({
      ...(minDunningLevel !== undefined ? { minDunningLevel } : {}),
      ...(resolved !== undefined ? { resolved } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }, [loadArrears, minDunningLevel, resolved, page]);

  function update(next: { minDunningLevel?: number | undefined; resolved?: boolean | 'ALL'; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('minDunningLevel' in next) {
      if (next.minDunningLevel !== undefined) merged.set('minDunningLevel', String(next.minDunningLevel));
      else merged.delete('minDunningLevel');
      merged.delete('page');
    }
    if ('resolved' in next) {
      // `false` is the default and carries no parameter; `ALL` is an explicit sentinel so
      // "both" can be told apart from a fresh visit, the same idiom the client register uses.
      if (next.resolved === true) merged.set('resolved', 'true');
      else if (next.resolved === 'ALL') merged.set('resolved', 'ALL');
      else merged.delete('resolved');
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
      resolved === true
        ? 'resolved arrears cases'
        : resolved === undefined
          ? 'arrears cases'
          : 'open arrears cases',
    value: total,
    pending: isInitialLoad(list),
    hint:
      list.status === 'error' && total === null
        ? 'could not load'
        : minDunningLevel !== undefined
          ? `at dunning level ${minDunningLevel} or worse`
          : 'in this tenant',
  };

  const columns: Column<ArrearsCaseView>[] = [
    {
      key: 'policyNumber',
      header: 'Policy',
      render: (c) => <span className="font-mono font-medium">{c.policyNumber ?? '—'}</span>,
    },
    {
      key: 'dunningLevel',
      header: 'Dunning',
      /*
       * The level, and what it MEANS at 5. A bare "5" reads as one more step in a sequence,
       * when it is the point at which the platform recommends lapse -- `policy` consumes
       * `PolicyLapseRecommended` and lapses the contract. A collections officer scanning this
       * column needs the difference between "chase harder" and "this is going".
       */
      render: (c) =>
        c.dunningLevel === undefined ? (
          '—'
        ) : (
          <span className="flex items-baseline gap-2">
            <span className="font-medium tabular-nums">L{c.dunningLevel}</span>
            {c.dunningLevel >= LAPSE_RECOMMENDATION_LEVEL && (
              <span className="text-[11px] text-status-danger-fg">lapse recommended</span>
            )}
          </span>
        ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      // An em dash, never a zero. The amount comes from the invoice the case was opened
      // against, and a 0.00 on a collections screen would read as "nothing owed".
      render: (c) => (c.amount ? formatMoney(c.amount) : '—'),
    },
    {
      key: 'dueDate',
      header: 'Due',
      render: (c) => (c.dueDate ? formatDate(c.dueDate) : '—'),
    },
    {
      key: 'invoiceStatus',
      header: 'Invoice',
      secondary: true,
      render: (c) => (c.invoiceStatus ? <StatusBadge kind="invoice" value={c.invoiceStatus} /> : '—'),
    },
    {
      key: 'lastNotifiedDunningLevel',
      header: 'Chased to',
      /*
       * The gap that mattered. `lastNotifiedDunningLevel` below `dunningLevel` means this
       * level was reached and the customer has not been told -- which on this platform was
       * true of every case for a long time, because the sweep that publishes
       * `billing.PremiumOverdue` had no caller at all. It is now carried forward whenever this
       * screen loads, so a persistent gap here is a real signal rather than the normal state.
       */
      render: (c) => {
        if (c.lastNotifiedDunningLevel === undefined) return '—';
        const behind =
          c.dunningLevel !== undefined && c.lastNotifiedDunningLevel < c.dunningLevel;
        return (
          <span className={behind ? 'text-status-warning-fg' : 'text-muted-foreground'}>
            {c.lastNotifiedDunningLevel === 0 ? 'not yet' : `L${c.lastNotifiedDunningLevel}`}
          </span>
        );
      },
    },
    {
      key: 'openedAt',
      header: 'Opened',
      secondary: true,
      render: (c) => (c.openedAt ? formatInstant(c.openedAt) : '—'),
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() =>
            void loadArrears({
              ...(minDunningLevel !== undefined ? { minDunningLevel } : {}),
              ...(resolved !== undefined ? { resolved } : {}),
              page,
            })
          }
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      // An empty queue is good news here, and the copy should say so rather than reading like
      // a screen that failed to find its data.
      return (
        <EmptyState
          title={
            minDunningLevel !== undefined
              ? `Nothing at dunning level ${minDunningLevel} or worse`
              : resolved === true
                ? 'No resolved arrears cases'
                : 'Nobody is in arrears'
          }
          description={
            minDunningLevel !== undefined
              ? 'Lower the level to see cases earlier in the escalation.'
              : resolved === true
                ? 'A case resolves when its invoice is paid or waived.'
                : 'Every premium invoice in this tenant is either paid, waived, or not yet overdue.'
          }
          {...(minDunningLevel !== undefined || resolved !== false
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ minDunningLevel: undefined, resolved: false })}>
                    Show the open queue
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
          rowKey={(c) => c.arrearsCaseId ?? JSON.stringify(c)}
          // Through to the policy, which is where the invoice, the waiver and the payment
          // request live. This screen finds the work; the policy record is where it is done.
          onRowActivate={(c) => {
            if (c.policyNumber) navigate(`../policies/${encodeURIComponent(c.policyNumber)}`);
          }}
          caption="Arrears cases"
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
        title="Arrears"
        description="Policies behind on premium, worst escalation first. Open one to waive the invoice or request payment."
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip
              label="Open"
              active={resolved === false}
              onClick={() => update({ resolved: false })}
            />
            <FilterChip
              label="Resolved"
              active={resolved === true}
              onClick={() => update({ resolved: true })}
            />
            <FilterChip label="All" active={resolved === undefined} onClick={() => update({ resolved: 'ALL' })} />

            <span className="ml-3 text-[11px] text-subtle-foreground uppercase">Level</span>
            <FilterChip
              label="Any"
              active={minDunningLevel === undefined}
              onClick={() => update({ minDunningLevel: undefined })}
            />
            {DUNNING_LEVELS.map((level) => (
              <FilterChip
                key={level}
                // "3+" rather than "3": the filter is a floor, and a chip reading a bare
                // number would promise an exact match it does not do.
                label={`${level}+`}
                active={minDunningLevel === level}
                onClick={() => update({ minDunningLevel: level })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>
    </>
  );
}
