import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import type { PayoutInstalmentView, PayoutKind, ProofOfLifeMethod } from '@/api/types';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { approveGates, retryGates, reviewGates } from '@/gates/payoutGates';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';
import {
  blankPayoutReview,
  payoutReviewSchema,
  PROOF_OF_LIFE_METHODS,
  type PayoutReviewValues,
} from './payoutReviewForm';

const BREADCRUMB = [{ label: 'Payouts', to: '/staff/payouts' }];

const KIND_LABEL: Record<PayoutKind, string> = {
  SURVIVAL: 'Survival benefit',
  MATURITY: 'Maturity',
  INCOME: 'Income',
  RETURN_OF_PREMIUM: 'Premium return',
};

/**
 * One payout, and the two signatures that get it paid.
 *
 * Both halves are here rather than in a drawer because both move real money: a reviewer confirms
 * where it goes and that the person is alive, and a DIFFERENT person releases it. The page exists
 * so the second person has something to read before signing.
 */
export function PayoutPage() {
  const { instalmentId = '' } = useParams();
  const detail = useBenefitPayoutStore((s) => s.instalment[instalmentId]);
  const load = useBenefitPayoutStore((s) => s.loadInstalment);

  useEffect(() => {
    if (instalmentId) void load(instalmentId);
  }, [instalmentId, load]);

  if (!detail || isInitialLoad(detail)) return <LoadingBlock label="Loading payout" />;

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <>
        {/* The bar renders on the error path too, so a record that fails to load keeps its
            heading and its way out. */}
        <PageHeader breadcrumb={BREADCRUMB} title="Payout" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void load(instalmentId)} />
        </div>
      </>
    );
  }

  const payout = detail.data;
  if (!payout) return null;

  return (
    <>
      <PageHeader
        breadcrumb={BREADCRUMB}
        title={KIND_LABEL[payout.kind]}
        description={
          <Link to={`/staff/policies/${encodeURIComponent(payout.policyNumber)}`} className="font-mono text-xs underline underline-offset-2">
            {payout.policyNumber}
          </Link>
        }
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <PayoutActions payout={payout} />
        </div>

        <Panel title="This payout">
          <dl className="divide-y divide-border">
            <Field label="Status" value={<StatusBadge kind="payoutInstalment" value={payout.status} />} />
            <Field label="Due" value={formatDate(payout.dueDate)} />
            <Field
              label="Payable now"
              value={payout.currentAmount ? formatMoney(payout.currentAmount) : 'Valued when due'}
            />
            {payout.restatementReason && (
              <Field
                label="As contracted"
                value={formatMoney(payout.originalAmount)}
                note={payout.restatementReason}
              />
            )}
            {payout.statusReason && <Field label="Why" value={payout.statusReason} />}
            <Field label="Payee" value={payout.payeeRef ?? '—'} />
            <Field label="Reviewed by" value={payout.reviewedBy ?? '—'} />
            <Field label="Approved by" value={payout.approvedBy ?? '—'} />
            <Field label="Payment attempts" value={payout.attempts} />
          </dl>
        </Panel>
      </div>
    </>
  );
}

function PayoutActions({ payout }: { payout: PayoutInstalmentView }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const viewerSubject = identity?.subject ?? undefined;

  if (payout.status === 'DUE' || payout.status === 'ON_HOLD') {
    return <ReviewAction payout={payout} />;
  }
  if (payout.status === 'REVIEWED') {
    return <ApproveAction payout={payout} viewerSubject={viewerSubject} />;
  }
  if (payout.status === 'FAILED' || payout.status === 'IN_DOUBT') {
    return <RetryAction payout={payout} />;
  }
  return (
    <Panel title="Nothing to do">
      <p className="px-4 py-3 text-sm text-muted-foreground">
        {payout.status === 'APPROVED'
          ? 'The payment has been requested from the provider. This page shows PAID once the provider confirms it.'
          : `This payout is ${payout.status.toLowerCase().replace(/_/g, ' ')}. Nothing further is needed here.`}
      </p>
    </Panel>
  );
}

function ReviewAction({ payout }: { payout: PayoutInstalmentView }) {
  const review = useBenefitPayoutStore((s) => s.review);
  const acting = useBenefitPayoutStore((s) => s.acting[payout.instalmentId]);
  const gates = reviewGates(payout);
  const refused = gates.some((g) => !g.ok && g.hard);
  const needsProofOfLife = payout.kind === 'SURVIVAL' || payout.kind === 'INCOME';

  const form = useForm<PayoutReviewValues>({
    resolver: zodResolver(payoutReviewSchema(needsProofOfLife)),
    defaultValues: blankPayoutReview(),
  });

  const onSubmit = form.handleSubmit((values) => {
    void review(
      payout.instalmentId,
      {
        payeeRef: values.payeeRef.trim(),
        // '' is the "none chosen" sentinel the select uses; the wire wants null.
        proofOfLifeMethod: values.proofOfLifeMethod === '' ? null : (values.proofOfLifeMethod as ProofOfLifeMethod),
        proofOfLifeDocumentId: null,
      },
      startMutation(),
    );
  });

  return (
    <Panel title="Review this payout" subtitle="Confirm where the money goes. A second person releases it.">
      <div className="space-y-3 px-4 py-3">
        <GatePanel gates={gates} title="Before reviewing" />
        {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
        <form onSubmit={onSubmit} className="space-y-3">
          <FormField label="Payee reference" error={form.formState.errors.payeeRef?.message}>
            <Input
              {...form.register('payeeRef')}
              placeholder="Mobile money number or bank destination"
              disabled={refused}
            />
          </FormField>
          {needsProofOfLife && (
            <FormField
              label="How was the life assured confirmed alive?"
              error={form.formState.errors.proofOfLifeMethod?.message}
            >
              <Select {...form.register('proofOfLifeMethod')} disabled={refused}>
                <option value="">Choose a method</option>
                {PROOF_OF_LIFE_METHODS.map((method) => (
                  <option key={method.value} value={method.value}>
                    {method.label}
                  </option>
                ))}
              </Select>
            </FormField>
          )}
          <Button type="submit" size="sm" disabled={refused || acting?.status === 'loading'}>
            Review
          </Button>
        </form>
      </div>
    </Panel>
  );
}

function ApproveAction({
  payout,
  viewerSubject,
}: {
  payout: PayoutInstalmentView;
  viewerSubject: string | undefined;
}) {
  const approve = useBenefitPayoutStore((s) => s.approve);
  const acting = useBenefitPayoutStore((s) => s.acting[payout.instalmentId]);
  const [armed, setArmed] = useState(false);
  const gates = approveGates(payout, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <Panel title="Approve this payout" subtitle="This is the point at which money leaves.">
      <div className="space-y-3 px-4 py-3">
        <GatePanel gates={gates} title="Before approving" />
        {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
        {armed ? (
          <ConfirmAct
            heading="Approve this payout?"
            consequence={
              <>
                {formatMoney(payout.currentAmount)} is requested from the payment provider and sent
                to <strong>{payout.payeeRef}</strong>.
              </>
            }
            /* The rail has no cancel. Once the disbursement is accepted the only remedy is to
               recover the money from whoever received it. */
            reversal="Nothing here recalls it. A payout sent to the wrong destination is recovered by asking for it back."
            confirmLabel="Approve and pay"
            busy={acting?.status === 'loading'}
            onConfirm={() => {
              void approve(payout.instalmentId, startMutation());
              setArmed(false);
            }}
            onCancel={() => setArmed(false)}
          />
        ) : (
          <Button size="sm" disabled={refused} onClick={() => setArmed(true)}>
            Approve
          </Button>
        )}
      </div>
    </Panel>
  );
}

function RetryAction({ payout }: { payout: PayoutInstalmentView }) {
  const retry = useBenefitPayoutStore((s) => s.retry);
  const acting = useBenefitPayoutStore((s) => s.acting[payout.instalmentId]);
  const [armed, setArmed] = useState(false);
  const gates = retryGates(payout);
  const refused = gates.some((g) => !g.ok && g.hard);

  return (
    <Panel title="The payment did not go through">
      <div className="space-y-3 px-4 py-3">
        <GatePanel gates={gates} title="Before trying again" />
        {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
        {armed ? (
          <ConfirmAct
            heading="Try this payment again?"
            consequence={
              <>
                {formatMoney(payout.currentAmount)} is requested again, to{' '}
                <strong>{payout.payeeRef}</strong>. The approval already given stands.
              </>
            }
            reversal="Nothing here recalls it if it succeeds this time."
            confirmLabel="Try again"
            busy={acting?.status === 'loading'}
            onConfirm={() => {
              void retry(payout.instalmentId, startMutation());
              setArmed(false);
            }}
            onCancel={() => setArmed(false)}
          />
        ) : (
          <Button size="sm" disabled={refused} onClick={() => setArmed(true)}>
            Try again
          </Button>
        )}
      </div>
    </Panel>
  );
}
