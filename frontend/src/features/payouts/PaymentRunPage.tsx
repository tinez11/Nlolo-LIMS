import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { PaymentRunView, PayoutInstalmentView, PayoutKind } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { Receipt } from '@/components/Receipt';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';

const BREADCRUMB = [{ label: 'Payment runs', to: '/staff/payment-runs' }];

const KIND_LABEL: Record<PayoutKind, string> = {
  SURVIVAL: 'Survival benefit',
  MATURITY: 'Maturity',
  INCOME: 'Income',
  RETURN_OF_PREMIUM: 'Premium return',
  ANNUITY: 'Annuity income',
};

/** One day's batch, and the single signature that releases it. */
export function PaymentRunPage() {
  const { paymentRunId = '' } = useParams();
  const runs = useBenefitPayoutStore((s) => s.runs);
  const loadRuns = useBenefitPayoutStore((s) => s.loadRuns);
  const instalments = useBenefitPayoutStore((s) => s.runInstalments[paymentRunId]);
  const loadRunInstalments = useBenefitPayoutStore((s) => s.loadRunInstalments);

  useEffect(() => {
    if (!paymentRunId) return;
    // The list is the only source of a run on this console -- there is no detail fetch in the
    // store, because the register is a bare array and already carries every field this page shows.
    if (runs.status === 'idle') void loadRuns();
    void loadRunInstalments(paymentRunId);
  }, [paymentRunId, runs.status, loadRuns, loadRunInstalments]);

  const run = runs.data?.find((r) => r.paymentRunId === paymentRunId);

  if (isInitialLoad(runs)) return <LoadingBlock label="Loading payment run" />;

  if (runs.status === 'error' && runs.error && runs.data === null) {
    return (
      <>
        <PageHeader breadcrumb={BREADCRUMB} title="Payment run" />
        <div className="px-6 pt-6">
          <ErrorPanel error={runs.error} onRetry={() => void loadRuns()} />
        </div>
      </>
    );
  }

  if (!run) {
    return (
      <>
        <PageHeader breadcrumb={BREADCRUMB} title="Payment run" />
        <div className="px-6 pt-6">
          <EmptyState
            title="No such payment run"
            description="It may belong to another tenant, or the list is stale — go back to Payment runs."
          />
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        breadcrumb={BREADCRUMB}
        title={`Run of ${formatDate(run.runDate)}`}
        description={`${run.instalmentCount} income instalment${run.instalmentCount === 1 ? '' : 's'}`}
      />

      <DetailLayout
        record={
          <Panel title="This run">
            <dl className="divide-y divide-border">
              <Field label="Status" value={<StatusBadge kind="paymentRun" value={run.status} />} />
              <Field label="Run date" value={formatDate(run.runDate)} />
              <Field label="Instalments" value={run.instalmentCount} />
              <Field
                label="Total"
                value={formatMoney(run.total)}
                note="Computed by the server, not summed from the rows below."
              />
              <Field label="Released by" value={run.approvedBy ?? '—'} />
            </dl>
          </Panel>
        }
      >
        {/*
          The receipt is rendered BESIDE the action, not inside it. Approving flips the run to
          APPROVED, which unmounts the acting panel -- so a receipt living in there would be
          destroyed by the very success it was meant to announce, and the person who just released
          real money would see the panel silently disappear.
        */}
        <ApprovedRunReceipt run={run} />
        {run.status === 'PREPARED' && <ApproveRunAction run={run} />}

        <Panel title="In this run" subtitle="Each instalment is paid separately">
          {!instalments || isInitialLoad(instalments) ? (
            <LoadingBlock />
          ) : instalments.status === 'error' && instalments.error && instalments.data === null ? (
            <ErrorPanel
              error={instalments.error}
              onRetry={() => void loadRunInstalments(paymentRunId)}
            />
          ) : (instalments.data ?? []).length === 0 ? (
            <EmptyState title="Nothing in this run" description="No instalment was assigned to it." />
          ) : (
            <div className="divide-y divide-border">
              {(instalments.data ?? []).map((instalment) => (
                <RunInstalmentRow key={instalment.instalmentId} instalment={instalment} />
              ))}
            </div>
          )}
        </Panel>
      </DetailLayout>
    </>
  );
}

function ApproveRunAction({ run }: { run: PaymentRunView }) {
  const approveRun = useBenefitPayoutStore((s) => s.approveRun);
  const acting = useBenefitPayoutStore((s) => s.acting[run.paymentRunId]);
  const [armed, setArmed] = useState(false);

  return (
    <Panel title="Release this run" subtitle="This is the point at which money leaves.">
      <div className="space-y-3 px-4 py-3">
        {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
        {armed ? (
          <ConfirmAct
            heading="Release this payment run?"
            consequence={
              <>
                Pay {run.instalmentCount} income instalment{run.instalmentCount === 1 ? '' : 's'}{' '}
                totalling <strong>{formatMoney(run.total)}</strong>, each to the destination a
                reviewer already confirmed on its stream.
              </>
            }
            reversal="Each instalment is paid separately; one that fails can be retried from its own page without touching the rest. Nothing here recalls one that succeeds."
            confirmLabel={`Pay ${formatMoney(run.total)}`}
            tone="danger"
            busy={acting?.status === 'loading'}
            onConfirm={() => {
              void approveRun(run.paymentRunId, startMutation());
              setArmed(false);
            }}
            onCancel={() => setArmed(false)}
          />
        ) : (
          <Button size="sm" onClick={() => setArmed(true)}>
            Release run
          </Button>
        )}
      </div>
    </Panel>
  );
}

/** Shown only after an approval made in THIS session -- a run approved yesterday needs no receipt. */
function ApprovedRunReceipt({ run }: { run: PaymentRunView }) {
  const acting = useBenefitPayoutStore((s) => s.acting[run.paymentRunId]);
  if (acting?.status !== 'success') return null;
  return (
    <Receipt
      heading="Payments requested"
      lines={[
        { label: 'Instalments', value: String(run.instalmentCount) },
        { label: 'Total', value: formatMoney(run.total) },
        { label: 'Released by', value: run.approvedBy ?? '—' },
      ]}
      note="Requested, not yet paid. Each instalment shows PAID once the payment provider confirms it, and one that fails can be tried again from its own page without touching the rest."
    />
  );
}

function RunInstalmentRow({ instalment }: { instalment: PayoutInstalmentView }) {
  return (
    <div className="flex items-center justify-between gap-2 px-4 py-2.5 text-sm">
      <div className="min-w-0">
        <Link
          to={`/staff/payouts/${encodeURIComponent(instalment.instalmentId)}`}
          className="font-mono text-xs underline underline-offset-2"
        >
          {instalment.policyNumber}
        </Link>
        <p className="text-xs text-muted-foreground">
          {KIND_LABEL[instalment.kind]} · due {formatDate(instalment.dueDate)}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-3">
        <StatusBadge kind="payoutInstalment" value={instalment.status} />
        <span className="font-medium">
          {instalment.currentAmount ? formatMoney(instalment.currentAmount) : '—'}
        </span>
      </div>
    </div>
  );
}
