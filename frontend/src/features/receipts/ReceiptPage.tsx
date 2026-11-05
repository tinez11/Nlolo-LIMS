import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
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
import { barredAsOwn } from '@/lib/ownWork';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReceiptStore } from '@/store/receiptStore';
import { depositTitle, methodOf, receiptOrigin, REQUESTED_MOBILE_MONEY } from './receiptForm';


/**
 * One recorded receipt (2026-10-27): what came in, how, against which invoice, who recorded it -- and, for a second
 * finance person, confirming it (the invoice is paid from it and the ledger posts it) or refusing it with a reason.
 */
export function ReceiptPage() {
  const { receiptId = '' } = useParams();
  const resource = useReceiptStore((s) => s.receipt[receiptId]);
  const load = useReceiptStore((s) => s.load);
  const [params] = useSearchParams();

  useEffect(() => {
    void load(receiptId);
  }, [load, receiptId]);

  if (!resource || isInitialLoad(resource)) return <LoadingBlock label="Loading deposit" />;
  if (!resource.data && resource.status === 'error' && resource.error) {
    return (
      <>
        <PageHeader breadcrumb={receiptOrigin(params.get('from'), null).crumbs} title="Deposit" />
        <div className="px-6">
          <ErrorPanel error={resource.error} onRetry={() => void load(receiptId)} />
        </div>
      </>
    );
  }
  const receipt = resource.data;
  if (!receipt) return null;
  const method = methodOf(receipt.method);
  const origin = receiptOrigin(params.get('from'), receipt.policyNumber);
  const requested = receipt.method === REQUESTED_MOBILE_MONEY.value;

  return (
    <>
      <PageHeader
        breadcrumb={origin.crumbs}
        title={depositTitle(method?.label ?? receipt.method)}
        status={<StatusBadge kind="receipt" value={receipt.status} />}
      />
      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <ReceiptDecision receipt={receipt} />
        </div>
        <Panel title="This deposit">
          <dl className="px-4 pb-2">
            <Field label="Amount" value={formatMoney(receipt.amount)} emphasis />
            <Field label="How it came in" value={method?.label ?? receipt.method} />
            <Field label={method?.reference ?? 'Reference'} value={<span className="font-mono">{receipt.reference}</span>} />
            {receipt.receivedOn && <Field label="Received on" value={formatDate(receipt.receivedOn)} />}
            <Field label="Paid by" value={receipt.payerRef} />
            <Field
              label="Policy"
              value={
                receipt.policyNumber ? (
                  <Link className="underline"
                    to={`/staff/policies/${encodeURIComponent(receipt.policyNumber)}?tab=${receipt.loanId ? 'loans' : 'billing'}`}>
                    {receipt.policyNumber}
                  </Link>
                ) : (
                  '—'
                )
              }
            />
            {receipt.loanId && (
              <Field label="Loan" value={<span className="font-mono">{receipt.loanId.slice(0, 8)}</span>} />
            )}
            {receipt.note && <Field label="Note" value={receipt.note} />}
            {receipt.fieldReceiptId && <Field label="Brings in" value="Cash an agent collected" />}
            <Field
              label={requested ? 'Requested by' : 'Recorded by'}
              value={receipt.recordedBy === 'system' ? 'The platform' : <StaffName subject={receipt.recordedBy} />}
            />
            <Field label={requested ? 'Requested' : 'Recorded'} value={formatInstant(receipt.recordedAt)} />
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
  // Money onto the policy's balance: a premium deposit named by its invoice, or since money movement step 5 by the
  // policy alone (DEPOSIT). Either is paid out oldest first, and either can be reversed.
  const onBalance = receipt.purpose === 'PREMIUM' || receipt.purpose === 'DEPOSIT';
  // What it pays (money movement step 1, 2026-11-04), by its purpose: a loan, a savings account, or the balance.
  const target = receipt.purpose === 'LOAN_REPAYMENT' ? 'the loan'
    : receipt.purpose === 'ACCOUNT_TOP_UP' || receipt.purpose === 'UL_TOP_UP' ? 'the savings account'
      : `the balance of ${receipt.policyNumber}`;
  const auth = useAuth();
  const viewerSubject = readIdentity(auth.user?.access_token)?.subject ?? undefined;
  const approve = useReceiptStore((s) => s.approve);
  const reject = useReceiptStore((s) => s.reject);
  const deciding = useReceiptStore((s) => s.deciding[receipt.receiptId]);
  const [armed, setArmed] = useState<'confirm' | 'refuse' | null>(null);
  const [reason, setReason] = useState('');

  if (receipt.status === 'CONFIRMED') {
    return (
      <>
        <Panel title="Confirmed">
          <p className="px-4 pb-4 text-sm">
            This deposit paid {formatMoney(receipt.amount)} {onBalance ? 'onto' : 'against'} {target}, and the ledger has
            posted it.
          </p>
        </Panel>
        {/* Money movement step 5: a premium deposit whose money never really arrived is reversed from here. */}
        {onBalance && <DepositReversalPanel receipt={receipt} viewerSubject={viewerSubject} />}
      </>
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
    <Panel title="Confirm this deposit"
      subtitle={receipt.loanId ? 'Only a confirmed deposit repays the loan.'
        : onBalance ? 'Only a confirmed deposit goes onto the policy’s balance.' : `Only a confirmed deposit pays into ${target}.`}>
      <div className="space-y-3 px-4 py-3">
        {receipt.method === REQUESTED_MOBILE_MONEY.value && <p className="text-sm">{REQUESTED_MOBILE_MONEY.hint}</p>}
        <GatePanel gates={gates} title="Before confirming" />
        {deciding?.status === 'error' && deciding.error && <InlineError error={deciding.error} />}
        {armed === 'confirm' ? (
          <ConfirmAct
            heading="Confirm this deposit?"
            consequence={
              <>
                <strong>{formatMoney(receipt.amount)}</strong>{' '}
                {onBalance
                  ? <>goes onto the balance of {receipt.policyNumber} and pays what it owes, oldest first; anything left
                    pays its next premiums. The ledger</>
                  : <>is applied to {target} on {receipt.policyNumber}, and the ledger</>}{' '}
                posts it to the account a {methodOf(receipt.method)?.label.toLowerCase() ?? 'payment'} reaches.
              </>
            }
            reversal={onBalance
              ? 'If the money never really arrives (a bounced cheque), the deposit can be reversed from this page.'
              : 'Nothing here undoes it. A deposit confirmed in error is corrected by a manual journal.'}
            confirmLabel="Confirm deposit"
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
                Refuse deposit
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

/**
 * A confirmed premium deposit's reversal (money movement step 3b): Reverse opens its own page; one asked waits for a
 * second person (collections.approve) -- or the asker, holding collections.approve-own -- and one done says so.
 */
function DepositReversalPanel({ receipt, viewerSubject }: { receipt: Receipt; viewerSubject: string | undefined }) {
  const reversals = useReceiptStore((s) => s.reversals[receipt.receiptId]);
  const load = useReceiptStore((s) => s.loadReversals);
  const decide = useReceiptStore((s) => s.decideReversal);
  const acting = useReceiptStore((s) => s.reversing[receipt.receiptId]);
  const [armed, setArmed] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');

  useEffect(() => {
    void load(receipt.receiptId);
  }, [receipt.receiptId, load]);

  if (!reversals || isInitialLoad(reversals)) return null;
  const live = (reversals.data ?? []).find((r) => r.status === 'REQUESTED' || r.status === 'CONFIRMED');
  const busy = acting?.status === 'loading';

  if (live?.status === 'CONFIRMED') {
    return (
      <Panel title="Reversed">
        <p className="px-4 pb-4 text-sm">
          {formatMoney(live.amount)} came back off the balance{live.decidedBy && <> when <StaffName subject={live.decidedBy} /> confirmed it</>}:
          {' '}{live.reason}. Any premium it had paid and the balance could not cover is owed again.
        </p>
      </Panel>
    );
  }
  if (live?.status === 'REQUESTED') {
    const own = barredAsOwn(viewerSubject, live.requestedBy, 'receipts');
    return (
      <Panel title="Reversal waiting" subtitle="Nothing comes off the balance until a second person confirms it.">
        <div className="space-y-3 px-4 py-3">
          <p className="text-sm">
            Asked by <StaffName subject={live.requestedBy} />: {live.reason}
          </p>
          {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
          {can('collections.approve') && (
            <>
              {own && <p className="text-xs text-muted-foreground">You asked for this reversal, so someone else must confirm it.</p>}
              {armed ? (
                <ConfirmAct
                  heading="Reverse this deposit?"
                  consequence={
                    <>
                      <strong>{formatMoney(live.amount)}</strong> comes off the balance of {live.policyNumber}. If the balance
                      cannot cover it, the premiums it paid are owed again, newest first, and their commission is taken back.
                    </>
                  }
                  reversal="A reversal cannot be undone. Money that does arrive later is recorded as a new deposit."
                  confirmLabel="Reverse deposit"
                  tone="danger"
                  busy={busy}
                  onConfirm={() => {
                    void decide(receipt.receiptId, live.reversalId, null);
                    setArmed(false);
                  }}
                  onCancel={() => setArmed(false)}
                />
              ) : rejecting ? (
                <div className="space-y-2">
                  <FormField label="Why is it rejected?">
                    <Input value={reason} onChange={(e) => setReason(e.target.value)} />
                  </FormField>
                  <div className="flex gap-2">
                    <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                      onClick={() => void decide(receipt.receiptId, live.reversalId, reason.trim())}>
                      Reject reversal
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
                      Cancel
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="flex gap-2">
                  <Button size="sm" disabled={own} onClick={() => setArmed(true)}>
                    Confirm
                  </Button>
                  <Button size="sm" variant="outline" disabled={own} onClick={() => setRejecting(true)}>
                    Reject
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </Panel>
    );
  }
  if (!can('collections.manage')) return null;
  return (
    <Panel title="Money never arrived?">
      <div className="space-y-2 px-4 py-3">
        <p className="text-sm">A bounced cheque or a deposit recorded in error is reversed, saying why, and a second person confirms it.</p>
        <Button asChild size="sm" variant="outline">
          <Link to={`/staff/receipts/${encodeURIComponent(receipt.receiptId)}/reverse`}>Reverse</Link>
        </Button>
      </div>
    </Panel>
  );
}
