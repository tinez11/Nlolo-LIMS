import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReceiptStore } from '@/store/receiptStore';
import { depositTitle, methodOf } from './receiptForm';

/**
 * Reverse a confirmed deposit whose money never really arrived -- a bounced cheque (money movement step 5, design
 * §7.3): a full page, as Record deposit and Withdraw are. One person asks, saying why; a second confirms it on the
 * deposit's page, and only then does it come off the balance.
 */
export function ReverseDepositPage() {
  const { receiptId = '' } = useParams();
  const navigate = useNavigate();
  const resource = useReceiptStore((s) => s.receipt[receiptId]);
  const load = useReceiptStore((s) => s.load);
  const request = useReceiptStore((s) => s.requestReversal);
  const acting = useReceiptStore((s) => s.reversing[receiptId]);
  const [reason, setReason] = useState('');
  const [armed, setArmed] = useState(false);
  const back = `/staff/receipts/${encodeURIComponent(receiptId)}`;

  useEffect(() => {
    void load(receiptId);
  }, [receiptId, load]);

  if (!resource || isInitialLoad(resource)) return <LoadingBlock label="Loading deposit" />;
  if (!resource.data && resource.status === 'error' && resource.error) {
    return <ErrorPanel error={resource.error} onRetry={() => void load(receiptId)} />;
  }
  const receipt = resource.data;
  if (!receipt) return null;
  const method = methodOf(receipt.method);

  async function send() {
    setArmed(false);
    if (await request(receiptId, reason.trim())) navigate(back);
  }

  return (
    <>
      <PageHeader
        breadcrumb={[
          { label: 'Deposits', to: '/staff/payments' },
          { label: depositTitle(method?.label ?? receipt.method), to: back },
        ]}
        title="Reverse deposit"
        description="For money that never really arrived. Nothing comes off the balance until a second person confirms it."
      />
      <FormLayout formId="reverse-deposit-form" outlineLabel="Reversal sections">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (reason.trim() !== '') setArmed(true);
          }}
        >
          <div data-outline="The deposit" className="space-y-4">
            <dl className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
              <Field label="Amount" value={formatMoney(receipt.amount)} />
              <Field label="Policy" value={receipt.policyNumber ?? '—'} />
              <Field label={method?.reference ?? 'Reference'} value={<span className="font-mono">{receipt.reference}</span>} />
              {receipt.receivedOn && <Field label="Received on" value={formatDate(receipt.receivedOn)} />}
            </dl>
          </div>
          <div data-outline="Why" className="max-w-xl">
            <FormField required label="Why is it reversed?" hint="For example: the cheque bounced.">
              <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
            </FormField>
          </div>

          {acting?.status === 'error' && acting.error && (
            <InlineError lead="Could not ask for the reversal" error={acting.error} />
          )}

          {armed ? (
            <ConfirmAct
              heading="Ask to reverse this deposit?"
              consequence={
                <>
                  <strong>{formatMoney(receipt.amount)}</strong> is to come off the balance of {receipt.policyNumber}. If
                  the balance cannot cover it, the premiums it paid are owed again, newest first.
                </>
              }
              reversal="Nothing moves until a second person confirms it; they can reject it instead."
              confirmLabel="Ask to reverse"
              busy={acting?.status === 'loading'}
              onConfirm={() => void send()}
              onCancel={() => setArmed(false)}
            />
          ) : (
            <div className="flex gap-2">
              <Button type="submit" variant="primary" disabled={reason.trim() === ''}>
                Continue
              </Button>
              <Button asChild variant="ghost">
                <Link to={back}>Cancel</Link>
              </Button>
            </div>
          )}
        </form>
      </FormLayout>
    </>
  );
}
