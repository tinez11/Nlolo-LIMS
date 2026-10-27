import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import type { Receipt } from '@/api/receipts';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { GatePanel } from '@/components/GatePanel';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { confirmReceiptGates } from '@/gates/receiptGates';
import { formatDate, formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReceiptStore } from '@/store/receiptStore';
import { methodOf } from './receiptForm';

const BREADCRUMB = [{ label: 'Payments', to: '/staff/payments' }];

/**
 * One recorded receipt (2026-10-27): what came in, how, against which invoice, who recorded it -- and, for a second
 * finance person, confirming it (the invoice is paid from it and the ledger posts it) or refusing it with a reason.
 */
export function ReceiptPage() {
  const { receiptId = '' } = useParams();
  const resource = useReceiptStore((s) => s.receipt[receiptId]);
  const load = useReceiptStore((s) => s.load);

  useEffect(() => {
    void load(receiptId);
  }, [load, receiptId]);

  if (!resource || isInitialLoad(resource)) return <LoadingBlock label="Loading receipt" />;
  if (!resource.data && resource.status === 'error' && resource.error) {
    return (
      <>
        <PageHeader breadcrumb={BREADCRUMB} title="Receipt" />
        <div className="px-6">
          <ErrorPanel error={resource.error} onRetry={() => void load(receiptId)} />
        </div>
      </>
    );
  }
  const receipt = resource.data;
  if (!receipt) return null;
  const method = methodOf(receipt.method);

  return (
    <>
      <PageHeader
        breadcrumb={BREADCRUMB}
        title={`${method?.label ?? receipt.method} receipt`}
        description={receipt.policyNumber ?? undefined}
        status={<StatusBadge kind="receipt" value={receipt.status} />}
      />
      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <ReceiptDecision receipt={receipt} />
        </div>
        <Panel title="This receipt">
          <dl className="px-4 pb-2">
            <Field label="Amount" value={formatMoney(receipt.amount)} emphasis />
            <Field label="How it came in" value={method?.label ?? receipt.method} />
            <Field label={method?.reference ?? 'Reference'} value={<span className="font-mono">{receipt.reference}</span>} />
            <Field label="Received on" value={formatDate(receipt.receivedOn)} />
            <Field label="Paid by" value={receipt.payerRef} />
            <Field
              label="Policy"
              value={
                receipt.policyNumber ? (
                  <Link className="underline" to={`/staff/policies/${encodeURIComponent(receipt.policyNumber)}?tab=billing`}>
                    {receipt.policyNumber}
                  </Link>
                ) : (
                  '—'
                )
              }
            />
            {receipt.note && <Field label="Note" value={receipt.note} />}
            {receipt.fieldReceiptId && <Field label="Brings in" value="An agent's field receipt" />}
            <Field label="Recorded by" value={<StaffName subject={receipt.recordedBy} />} />
            <Field label="Recorded" value={formatInstant(receipt.recordedAt)} />
            {receipt.decidedBy && (
              <Field
                label={receipt.status === 'REJECTED' ? 'Refused by' : 'Confirmed by'}
                value={<StaffName subject={receipt.decidedBy} />}
              />
            )}
            {receipt.decisionReason && <Field label="Why refused" value={receipt.decisionReason} />}
          </dl>
        </Panel>
      </div>
    </>
  );
}

function ReceiptDecision({ receipt }: { receipt: Receipt }) {
  const auth = useAuth();
  const viewerSubject = readIdentity(auth.user?.access_token)?.subject ?? undefined;
  const approve = useReceiptStore((s) => s.approve);
  const reject = useReceiptStore((s) => s.reject);
  const deciding = useReceiptStore((s) => s.deciding[receipt.receiptId]);
  const [armed, setArmed] = useState<'confirm' | 'refuse' | null>(null);
  const [reason, setReason] = useState('');

  if (receipt.status === 'CONFIRMED') {
    return (
      <Panel title="Confirmed">
        <p className="px-4 pb-4 text-sm">
          This receipt paid {formatMoney(receipt.amount)} against the invoice, and the ledger has posted it.
        </p>
      </Panel>
    );
  }
  if (receipt.status === 'REJECTED') {
    return (
      <Panel title="Refused">
        <p className="px-4 pb-4 text-sm">It paid nothing. If the money did come in, record it again with the right details.</p>
      </Panel>
    );
  }
  if (!can('collections.approve')) {
    return (
      <Panel title="Waiting for confirmation">
        <p className="px-4 pb-4 text-sm">A finance officer other than the one who recorded it confirms it before it pays anything.</p>
      </Panel>
    );
  }

  const gates = confirmReceiptGates(receipt, viewerSubject);
  const refused = gates.some((g) => !g.ok && g.hard);
  return (
    <Panel title="Confirm this receipt" subtitle="Only a confirmed receipt pays the invoice.">
      <div className="space-y-3 px-4 py-3">
        <GatePanel gates={gates} title="Before confirming" />
        {deciding?.status === 'error' && deciding.error && <InlineError error={deciding.error} />}
        {armed === 'confirm' ? (
          <ConfirmAct
            heading="Confirm this receipt?"
            consequence={
              <>
                <strong>{formatMoney(receipt.amount)}</strong> is applied to the invoice on {receipt.policyNumber}, and the
                ledger posts it to the account a {methodOf(receipt.method)?.label.toLowerCase() ?? 'payment'} reaches.
              </>
            }
            reversal="Nothing here undoes it. A receipt confirmed in error is corrected by a manual journal."
            confirmLabel="Confirm receipt"
            busy={deciding?.status === 'loading'}
            onConfirm={() => {
              void approve(receipt.receiptId);
              setArmed(null);
            }}
            onCancel={() => setArmed(null)}
          />
        ) : armed === 'refuse' ? (
          <div className="space-y-2">
            <FormField label="Why it is refused">
              <Input value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
            </FormField>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="danger"
                disabled={reason.trim() === ''}
                pending={deciding?.status === 'loading'}
                onClick={() => {
                  void reject(receipt.receiptId, reason.trim());
                  setArmed(null);
                }}
              >
                Refuse receipt
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setArmed(null)}>
                Cancel
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex gap-2">
            <Button size="sm" disabled={refused} onClick={() => setArmed('confirm')}>
              Confirm
            </Button>
            <Button size="sm" variant="outline" disabled={refused} onClick={() => setArmed('refuse')}>
              Refuse
            </Button>
          </div>
        )}
      </div>
    </Panel>
  );
}
