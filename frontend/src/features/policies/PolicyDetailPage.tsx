import { ArrowLeft, Ban } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { InvoiceView, LoanView } from '@/api/types';
import { PageHeader } from '@/components/AppShell';
import { DataTable, type Column } from '@/components/DataTable';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectCoverage,
  selectDetail,
  selectInvoices,
  selectLoans,
  usePolicyStore,
} from '@/store/policyStore';
import { BeneficiariesPanel } from './BeneficiariesPanel';
import { Field } from '@/components/Field';

/**
 * The "acts" half of drawer-previews-page-acts: the full record, and where any
 * mutating action would live.
 *
 * The invoice and loan tables use the NO-PAGER variant, because
 * `GET /policies/{n}/invoices` and `.../loans` return bare unpaged arrays -- the
 * whole set arrives in one response and a pager over it would be a lie.
 */
export function PolicyDetailPage() {
  const { policyNumber = '' } = useParams();

  const detail = usePolicyStore(selectDetail(policyNumber));
  const coverage = usePolicyStore(selectCoverage(policyNumber));
  const invoices = usePolicyStore(selectInvoices(policyNumber));
  const loans = usePolicyStore(selectLoans(policyNumber));

  const loadDetail = usePolicyStore((s) => s.loadDetail);
  const loadCoverage = usePolicyStore((s) => s.loadCoverage);
  const loadInvoices = usePolicyStore((s) => s.loadInvoices);
  const loadLoans = usePolicyStore((s) => s.loadLoans);

  useEffect(() => {
    if (!policyNumber) return;
    void loadDetail(policyNumber);
    void loadCoverage(policyNumber);
    void loadInvoices(policyNumber);
    void loadLoans(policyNumber);
  }, [policyNumber, loadDetail, loadCoverage, loadInvoices, loadLoans]);

  const policy = detail.data;

  // isInitialLoad, not a 'loading'-only check: the load fires from an effect that
  // runs AFTER first render, so status is briefly 'idle' -- a 'loading'-only check
  // let that frame fall through toward the error branch below.
  if (isInitialLoad(detail)) {
    return <LoadingBlock label={`Loading ${policyNumber}`} />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={detail.error} onRetry={() => void loadDetail(policyNumber)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={policyNumber}
        description={
          policy?.productId ? (
            <span className="font-mono text-xs">product {policy.productId}</span>
          ) : undefined
        }
        actions={
          <>
            {policy?.status && <StatusBadge kind="policy" value={policy.status} />}
            {/* POST /policies/{n}/surrender genuinely returns 501 -- the surrender
                choreography was deferred with the workflow engine. Rendered disabled
                rather than as a live button that produces an error. */}
            <Button size="sm" disabled title="Not implemented on the platform yet (HTTP 501)">
              <Ban />
              Surrender
            </Button>
          </>
        }
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <Panel title="Invoices" subtitle="All invoices for this policy">
            {renderInvoices()}
          </Panel>

          <Panel title="Loans" subtitle="Policy loans taken against cash value">
            {renderLoans()}
          </Panel>
        </div>

        <div className="space-y-5">
          <Panel title="Policy">
            {policy && (
              <dl className="px-4 pb-2">
                <Field label="Sum assured" value={formatMoney(policy.sumAssured)} emphasis />
                <Field
                  label="Premium"
                  value={
                    <>
                      {formatMoney(policy.premium)}
                      {policy.premiumFrequency && (
                        <span className="ml-1 text-xs text-subtle-foreground">
                          {policy.premiumFrequency.toLowerCase()}
                        </span>
                      )}
                    </>
                  }
                />
                <Field
                  label="Cash value"
                  value={formatMoney(policy.cashValue)}
                  note="Always 0.00 until the platform credits cash value"
                />
                <Field label="Issued" value={formatDate(policy.issueDate)} />
                <Field
                  label="Policyholder"
                  value={<span className="font-mono text-xs">{policy.policyholderPartyId ?? '—'}</span>}
                  note="No party lookup endpoint exists yet"
                />
                <Field
                  label="Agent of record"
                  value={
                    policy.agentOfRecordId ? (
                      <Link to={`/staff/agents/${policy.agentOfRecordId}`} className="font-mono text-xs underline">
                        {policy.agentOfRecordId}
                      </Link>
                    ) : (
                      'Direct — no agent'
                    )
                  }
                />
              </dl>
            )}
          </Panel>

          <Panel title="Coverage" subtitle="Active benefits as of today">
            {renderCoverage()}
          </Panel>

          <Panel title="Beneficiaries">
            {policy && (
              <BeneficiariesPanel policyNumber={policyNumber} beneficiaries={policy.beneficiaries ?? []} />
            )}
          </Panel>
        </div>
      </div>
    </>
  );

  function renderInvoices() {
    if (isInitialLoad(invoices)) return <LoadingBlock />;
    if (invoices.status === 'error' && invoices.error && invoices.data === null) {
      return (
        <ErrorPanel error={invoices.error} onRetry={() => void loadInvoices(policyNumber)} />
      );
    }
    const rows = invoices.data ?? [];
    if (rows.length === 0) {
      return <EmptyState title="No invoices" description="Nothing has been billed on this policy." />;
    }

    const columns: Column<InvoiceView>[] = [
      { key: 'due', header: 'Due', render: (i) => formatDate(i.dueDate) },
      {
        key: 'status',
        header: 'Status',
        render: (i) => (
          <span className="flex items-center gap-1.5">
            <StatusBadge kind="invoice" value={i.status} />
            {typeof i.dunningLevel === 'number' && (
              <span
                className="text-[11px] text-status-danger-fg"
                title="Dunning escalation level (1-5)"
              >
                L{i.dunningLevel}
              </span>
            )}
          </span>
        ),
      },
      {
        key: 'grace',
        header: 'Grace ends',
        secondary: true,
        render: (i) => (
          <span className="text-muted-foreground">{formatDate(i.gracePeriodEndsAt)}</span>
        ),
      },
      { key: 'amount', header: 'Amount', align: 'right', render: (i) => formatMoney(i.amount) },
    ];

    return (
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(i) => i.invoiceId ?? JSON.stringify(i)}
        caption={`Invoices for ${policyNumber}`}
      />
    );
  }

  function renderLoans() {
    if (isInitialLoad(loans)) return <LoadingBlock />;
    if (loans.status === 'error' && loans.error && loans.data === null) {
      return <ErrorPanel error={loans.error} onRetry={() => void loadLoans(policyNumber)} />;
    }
    const rows = loans.data ?? [];
    if (rows.length === 0) {
      return (
        <EmptyState
          title="No loans"
          description="No policy loan has been taken against this policy."
        />
      );
    }

    const columns: Column<LoanView>[] = [
      {
        key: 'loanId',
        header: 'Loan',
        render: (l) => <span className="font-mono text-xs">{l.loanId?.slice(0, 8) ?? '—'}</span>,
      },
      { key: 'status', header: 'Status', render: (l) => <StatusBadge kind="loan" value={l.status} /> },
      {
        key: 'rate',
        header: 'Rate',
        align: 'right',
        secondary: true,
        render: (l) =>
          typeof l.currentInterestRate === 'number' ? `${l.currentInterestRate}%` : '—',
      },
      {
        key: 'principal',
        header: 'Principal',
        align: 'right',
        secondary: true,
        render: (l) => formatMoney(l.principalAmount),
      },
      {
        key: 'outstanding',
        header: 'Outstanding',
        align: 'right',
        render: (l) => formatMoney(l.outstandingBalance),
      },
    ];

    return (
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(l) => l.loanId ?? JSON.stringify(l)}
        caption={`Loans for ${policyNumber}`}
      />
    );
  }

  function renderCoverage() {
    if (isInitialLoad(coverage)) return <LoadingBlock />;
    if (coverage.status === 'error' && coverage.error && coverage.data === null) {
      return <ErrorPanel error={coverage.error} onRetry={() => void loadCoverage(policyNumber)} />;
    }
    const active = coverage.data?.activeCoverages ?? [];
    if (active.length === 0) {
      return (
        <p className="px-4 pb-4 text-xs text-muted-foreground">
          No active coverage as of today.
        </p>
      );
    }
    return (
      <dl className="px-4 pb-2">
        {active.map((c, index) => (
          <Field
            key={`${c.benefitType ?? 'benefit'}-${index}`}
            label={c.benefitType ? c.benefitType.replace(/_/g, ' ').toLowerCase() : 'Benefit'}
            value={formatMoney(c.sumAssured)}
          />
        ))}
      </dl>
    );
  }

}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to=".." relative="path">
        <ArrowLeft />
        All policies
      </Link>
    </Button>
  );
}

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
