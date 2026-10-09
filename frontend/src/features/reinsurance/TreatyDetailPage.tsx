import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { BordereauView, CessionView, ReinsuranceStatementView } from '@/api/types';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { useReinsuranceStatementsStore } from '@/store/reinsuranceStatementsStore';
import { STATEMENT_STATUS_LABEL, endedQuarters, settlementSide } from './statementForm';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock, TableSkeleton } from '@/components/states';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { DetailLayout } from '@/components/DetailLayout';
import { Panel } from '@/components/Panel';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { formatPeriod } from './formatPeriod';
import { idle, isInitialLoad } from '@/store/createResourceSlice';
import {
  selectBordereauPreview,
  selectBordereaux,
  selectTreatyCessions,
  selectTreatyDetail,
  selectTreatyUtilisation,
  useReinsuranceStore,
} from '@/store/reinsuranceStore';

/** Where this record lives. Treaties are staff-only, under the finance group. */
const BREADCRUMB = [{ label: 'Treaties', to: '/staff/treaties' }];

/**
 * The "acts" half of drawer-previews-page-acts, though there is nothing to
 * act on here: no endpoint updates or retires a treaty once created (`status`
 * moves ACTIVE -> EXPIRED, if it ever does, with no visible mechanism in this
 * codebase -- most plausibly a scheduled sweep past `effectiveTo`, but nothing
 * confirms one exists). This page is therefore read-only by platform
 * constraint, not by an unfinished feature.
 */
export function TreatyDetailPage() {
  const { treatyId = '' } = useParams();

  const detail = useReinsuranceStore(selectTreatyDetail(treatyId));
  const loadDetail = useReinsuranceStore((s) => s.loadDetail);

  useEffect(() => {
    if (treatyId) void loadDetail(treatyId);
  }, [treatyId, loadDetail]);

  const treaty = detail.data;

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading treaty" />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <>
        {/* The bar renders on the error path too, so a treaty that fails to load keeps its
            heading and its way out rather than leaving a bare error panel. */}
        <PageHeader breadcrumb={BREADCRUMB} title="Treaty" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadDetail(treatyId)} />
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        // Replaces a ghost button with a back arrow in its own strip above the bar.
        breadcrumb={BREADCRUMB}
        title={treaty?.reinsurerName ?? 'Treaty'}
        description={treaty?.treatyType?.replace(/_/g, ' ')}
        // `status`, not `actions`: a status is what the record IS.
        status={treaty?.status && <StatusBadge kind="treaty" value={treaty.status} />}
      />

      {treaty && (
        /*
          The last record on the platform to adopt the two-column shape. It used to stack the
          terms in a 448px box and then put the cession table underneath, so the retention
          limit -- the single number every row of that table is measured against -- scrolled
          away exactly when somebody started reading the rows.

          The split follows `DetailLayout`'s own rule rather than a preference: `record` is
          facts you need WHILE working in the other column, `children` is the work. A treaty's
          terms are the definition of what its cessions mean, which is as close to that rule as
          a page gets. Nothing mutating goes in the rail, and nothing here mutates anyway --
          no endpoint updates or retires a treaty once created.
        */
        <DetailLayout
          record={
            <Panel title="Terms">
              <dl className="px-4 pb-2">
                <Field label="Retention limit" value={formatMoney(treaty.retentionLimit)} emphasis />
                {treaty.cessionPercent && (
                  <Field
                    label="Cession"
                    value={`${treaty.cessionPercent}%`}
                    note="Only QUOTA_SHARE treaties carry a cession percent"
                  />
                )}
                <Field label="Effective from" value={formatDate(treaty.effectiveFrom)} />
                <Field
                  label="Effective to"
                  value={treaty.effectiveTo ? formatDate(treaty.effectiveTo) : 'Open-ended'}
                />
                {treaty.treatyType === 'XOL' && (
                  <Field
                    label="Cession at issuance"
                    value="None"
                    note="Excess-of-loss cedes nothing on new business — it participates only in claim recovery"
                  />
                )}
                <Field
                  label="Reinsurance commission"
                  value={`${treaty.commissionPercent}%`}
                  note="Not contingent on claims — taken off each month's ceded premium"
                />
                {treaty.xolAnnualPremium && (
                  <Field
                    label="Annual XOL premium"
                    value={formatMoney(treaty.xolAnnualPremium)}
                    note="Charged one twelfth on each monthly bordereau"
                  />
                )}
              </dl>
            </Panel>
          }
        >
          {treatyId && <TreatyBordereaux treatyId={treatyId} />}
          {treatyId && (
            <TreatyStatements treatyId={treatyId} effectiveFrom={treaty.effectiveFrom} effectiveTo={treaty.effectiveTo} />
          )}
          {treatyId && <TreatyCessions treatyId={treatyId} />}
        </DetailLayout>
      )}
    </>
  );
}

/**
 * What the reinsurer is charged, month by month (IFRS 17 I3c).
 *
 * A month-end job writes one bordereau per treaty and closed month: the treaty's share of each ceded policy's own
 * premium for every month it was on risk (K-01), less the commission not contingent on claims (K-02). Each is final
 * once written and posts one journal dated the month's last day. The current month is shown as it would be written
 * now -- computed, not stored -- so finance can see it build up before the month closes.
 */
function TreatyBordereaux({ treatyId }: { treatyId: string }) {
  const navigate = useNavigate();
  const written = useReinsuranceStore(selectBordereaux(treatyId));
  const preview = useReinsuranceStore(selectBordereauPreview(treatyId));
  const loadBordereaux = useReinsuranceStore((s) => s.loadBordereaux);
  const loadBordereauPreview = useReinsuranceStore((s) => s.loadBordereauPreview);

  useEffect(() => {
    void loadBordereaux(treatyId);
    void loadBordereauPreview(treatyId);
  }, [treatyId, loadBordereaux, loadBordereauPreview]);

  const money = (amount: string, currency: string) => formatMoney({ amount, currencyCode: currency });
  const current = preview.data;
  const rows = written.data ?? [];

  const columns: Column<BordereauView>[] = [
    { key: 'period', header: 'Month', render: (b) => <span className="font-medium">{formatPeriod(b.period)}</span> },
    { key: 'policyCount', header: 'Policies', align: 'right', render: (b) => b.policyCount },
    { key: 'premium', header: 'Ceded premium', align: 'right', render: (b) => money(b.premium, b.currency) },
    { key: 'commission', header: 'Commission', align: 'right', render: (b) => money(b.commission, b.currency) },
    {
      key: 'recoveries',
      header: 'Recoveries matched',
      align: 'right',
      secondary: true,
      render: (b) => money(b.recoveries, b.currency),
    },
  ];

  return (
    <Panel title="Bordereaux">
      {current && (
        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1 border-b border-border px-4 py-2.5 text-xs tabular-nums">
          <span className="font-medium">{formatPeriod(current.period)} so far</span>
          <span>
            <span className="text-muted-foreground">Policies </span>
            {current.policyCount}
          </span>
          <span>
            <span className="text-muted-foreground">Ceded premium </span>
            {money(current.premium, current.currency)}
          </span>
          <span>
            <span className="text-muted-foreground">Commission </span>
            {money(current.commission, current.currency)}
          </span>
          <span>
            <span className="text-muted-foreground">Recoveries </span>
            {money(current.recoveries, current.currency)}
          </span>
        </div>
      )}

      {isInitialLoad(written) ? (
        <TableSkeleton columns={columns.length} />
      ) : written.status === 'error' && written.error && written.data === null ? (
        <ErrorPanel error={written.error} onRetry={() => void loadBordereaux(treatyId)} />
      ) : rows.length === 0 ? (
        <EmptyState
          title="No month closed yet"
          description="A bordereau is written for each month after it ends, from the treaty's effective date."
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(b) => b.bordereauId ?? b.period}
          caption="Monthly bordereaux of this treaty"
          onRowActivate={(b) => {
            if (b.bordereauId) navigate(`bordereaux/${b.bordereauId}`);
          }}
        />
      )}
    </Panel>
  );
}

/**
 * The treaty's quarterly statements (IFRS 17 I3d): each settles a calendar quarter's bordereaux and recoveries into the
 * reinsurer current account. An ended quarter with no live statement offers "Prepare statement for <quarter>"; the
 * server decides whether it can really be settled (every month's bordereau written) and says why not.
 */
function TreatyStatements({ treatyId, effectiveFrom, effectiveTo }: {
  treatyId: string;
  effectiveFrom: string;
  effectiveTo?: string | null | undefined;
}) {
  const navigate = useNavigate();
  const statements = useReinsuranceStatementsStore((s) => s.byTreaty[treatyId] ?? idle<ReinsuranceStatementView[]>());
  const loadForTreaty = useReinsuranceStatementsStore((s) => s.loadForTreaty);
  const prepare = useReinsuranceStatementsStore((s) => s.prepare);
  const acting = useReinsuranceStatementsStore((s) => s.acting[`prepare.${treatyId}`]);

  useEffect(() => {
    void loadForTreaty(treatyId);
  }, [treatyId, loadForTreaty]);

  const rows = statements.data ?? [];
  const settled = new Set(rows.filter((s) => s.status !== 'REJECTED').map((s) => s.quarter));
  const open = endedQuarters(effectiveFrom, effectiveTo).filter((q) => !settled.has(q));

  const columns: Column<ReinsuranceStatementView>[] = [
    { key: 'quarter', header: 'Quarter', render: (s) => <span className="font-medium">{s.quarter}</span> },
    { key: 'status', header: 'Status', render: (s) => STATEMENT_STATUS_LABEL[s.status] ?? s.status },
    { key: 'balance', header: 'Balance', render: (s) => settlementSide(s) },
  ];

  return (
    <Panel title="Statements">
      {open.length > 0 && (
        <div className="flex flex-wrap gap-2 border-b border-border px-4 py-2.5">
          {open.map((quarter) => (
            <Button key={quarter} type="button" size="sm" variant="outline" disabled={acting?.status === 'loading'}
              onClick={async () => {
                const draft = await prepare(treatyId, quarter);
                if (draft) navigate(`statements/${draft.statementId}`);
              }}>
              {`Prepare statement for ${quarter}`}
            </Button>
          ))}
        </div>
      )}
      {acting?.status === 'error' && acting.error && (
        <div className="px-4 py-2">
          <InlineError error={acting.error} />
        </div>
      )}
      {isInitialLoad(statements) ? (
        <TableSkeleton columns={columns.length} />
      ) : statements.status === 'error' && statements.error && statements.data === null ? (
        <ErrorPanel error={statements.error} onRetry={() => void loadForTreaty(treatyId)} />
      ) : rows.length === 0 ? (
        <EmptyState title="No statement yet" description="A quarter is settled once all its months' bordereaux are written." />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(s) => s.statementId}
          caption="Quarterly statements of this treaty"
          onRowActivate={(s) => navigate(`statements/${s.statementId}`)}
        />
      )}
    </Panel>
  );
}

/**
 * What has actually been ceded to this treaty.
 *
 * The terms above say what the treaty PROMISES; this says what it has taken. Until
 * `GET /treaties/{id}/cessions` and `.../utilisation` existed neither could be asked:
 * cessions were reachable only through the policy they were made on, so a treaty could
 * state a retention limit and a cession percent beside hundreds of cessions naming it
 * and show none of them.
 *
 * The totals come from the server, never from summing the page below. Summing a page
 * would report one page's worth of cession as the treaty's utilisation -- a figure that
 * is always plausible and always too small.
 */
function TreatyCessions({ treatyId }: { treatyId: string }) {
  const utilisation = useReinsuranceStore(selectTreatyUtilisation(treatyId));
  const cessions = useReinsuranceStore(selectTreatyCessions(treatyId));
  const loadTreatyUtilisation = useReinsuranceStore((s) => s.loadTreatyUtilisation);
  const loadTreatyCessions = useReinsuranceStore((s) => s.loadTreatyCessions);

  useEffect(() => {
    void loadTreatyUtilisation(treatyId);
    void loadTreatyCessions(treatyId);
  }, [treatyId, loadTreatyUtilisation, loadTreatyCessions]);

  const totals = utilisation.data;
  const rows = cessions.data?.items ?? [];

  const columns: Column<CessionView>[] = [
    {
      key: 'policyNumber',
      header: 'Policy',
      render: (c) => <span className="font-mono font-medium">{c.policyNumber ?? '—'}</span>,
    },
    {
      key: 'cededAmount',
      header: 'Ceded',
      align: 'right',
      render: (c) => (c.cededAmount ? formatMoney(c.cededAmount) : '—'),
    },
    {
      key: 'cededPremium',
      header: 'Ceded premium',
      align: 'right',
      secondary: true,
      // An em dash, not a zero: a treaty type that cedes risk without a modelled premium
      // share genuinely has none, and a 0.00 would read as "nothing was due".
      render: (c) => (c.cededPremium ? formatMoney(c.cededPremium) : '—'),
    },
  ];

  return (
    <Panel title="Ceded to this treaty">
      {/* The totals sit inside the panel as a ruled strip, above the register they describe --
          the same shape the chart of accounts gives its trial balance, and the same reason:
          a figure that summarises a table belongs against that table, not floating above the
          panel as a second card.

          They come from the server's utilisation endpoint, never from summing the page below.
          Summing a page would report one page's worth of cession as the treaty's utilisation:
          a figure that is always plausible and always too small. */}
      {totals && (
        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-1 border-b border-border px-4 py-2.5 text-xs tabular-nums">
          <span>
            <span className="text-muted-foreground">Cessions </span>
            {totals.cessionCount}
          </span>
          <span>
            <span className="text-muted-foreground">Risk ceded </span>
            {totals.cededAmount ? formatMoney(totals.cededAmount) : '—'}
          </span>
          <span>
            <span className="text-muted-foreground">Premium ceded </span>
            {totals.cededPremium ? formatMoney(totals.cededPremium) : '—'}
          </span>
        </div>
      )}

      {isInitialLoad(cessions) ? (
          <TableSkeleton columns={columns.length} />
        ) : cessions.status === 'error' && cessions.error && cessions.data === null ? (
          <ErrorPanel error={cessions.error} onRetry={() => void loadTreatyCessions(treatyId)} />
        ) : rows.length === 0 ? (
          <EmptyState
            title="Nothing ceded yet"
            description="Cessions are recorded automatically as covered policies go on risk. An active treaty with none is an ordinary state, not a gap."
          />
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={(c) => c.cessionId ?? JSON.stringify(c)}
              caption="Cessions made to this treaty"
            />
            {cessions.data && (
              <Pager
                page={cessions.data.page}
                busy={cessions.status === 'loading'}
                onPageChange={(next) => void loadTreatyCessions(treatyId, next)}
              />
            )}
        </>
      )}
    </Panel>
  );
}

