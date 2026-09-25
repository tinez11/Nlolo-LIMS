import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { AwaitingEftView } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DataTable, type Column } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { humanizeStatus } from '@/lib/status';
import { transferVerdict } from './transferVerdict';
import { useTransferContexts } from './useTransferContexts';
import { CountLine, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { InlineError } from '@/components/InlineError';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAwaitingEft, selectExecuting, usePaymentStore } from '@/store/paymentStore';

/**
 * Bank transfers waiting on a person.
 *
 * ## Why this screen has to exist
 *
 * Every other payout on this platform completes without anybody looking at it: the mobile-money
 * aggregator calls back and the row moves itself. The EFT rail has no callback because it has no
 * integration — a finance officer signs in to the bank, makes the transfer, and comes back to
 * record that they did.
 *
 * Both endpoints have been live since credit-life plan 4 and <b>nothing in this console called
 * either of them</b>. So a credit-life claim could be registered, adjudicated, approved, valued
 * against the borrower's outstanding balance and instructed for payment — and then stop, with the
 * lender unpaid and no screen anywhere that admitted the instruction existed. This is the drain
 * on a queue that could only fill.
 *
 * ## Why the queue is the work surface
 *
 * There is no disbursement record page and there should not be: a transfer is not a thing anybody
 * navigates to, it is a thing somebody clears. So the act lives on the row, with the confirmation
 * above the table where it has room — the same arrangement, and the same reasoning, as the field
 * receipts queue.
 *
 * ## Why confirming asks for the bank reference
 *
 * Recording an execution publishes `payment.DisbursementCompleted`, which settles the claim,
 * exits the borrower from cover and books the expense to the general ledger. An execution
 * recorded with nothing to look it up by is a claim the platform believes is paid that nobody can
 * trace to a transfer — which is exactly the shape of an unnoticed unpaid claim. The backend
 * demands it (`@NotBlank`); asking for it inside the confirmation rather than before it keeps the
 * commitment and the fact in one place.
 */
export function EftExecutionPage() {
  const queue = usePaymentStore(selectAwaitingEft);
  const loadAwaitingEft = usePaymentStore((s) => s.loadAwaitingEft);
  const markExecuted = usePaymentStore((s) => s.markExecuted);

  // ONE confirmation at a time, held by the page. Arming several assertions that money has
  // moved is not an interaction anybody should be offered.
  const [confirming, setConfirming] = useState<AwaitingEftView | null>(null);
  const [bankReference, setBankReference] = useState('');
  const [executingId, setExecutingId] = useState<string | null>(null);
  const executeState = usePaymentStore(
    selectExecuting(executingId ?? confirming?.disbursementId ?? ''),
  );

  useEffect(() => {
    void loadAwaitingEft();
  }, [loadAwaitingEft]);

  const rows = useMemo(() => queue.data ?? [], [queue.data]);

  const count: Stat = {
    label: rows.length === 1 ? 'transfer to make' : 'transfers to make',
    value: queue.data ? rows.length : null,
    pending: isInitialLoad(queue),
    hint: queue.status === 'error' && !queue.data ? 'could not load' : 'none of this money has moved',
  };

  // Whose death each transfer pays, and whether it may be paid at all -- see transferVerdict.
  const contexts = useTransferContexts(rows);
  const verdictFor = (d: AwaitingEftView) => {
    const context = contexts[d.disbursementId];
    return context
      ? transferVerdict({ claimId: d.sourceRef, payeeRef: d.payeeRef, claim: context.claim,
          policy: context.policy, member: context.member })
      : null;
  };

  const columns: Column<AwaitingEftView>[] = [
    {
      key: 'payeeRef',
      header: 'Pay',
      /*
        On credit life the lender, by name -- always the payee (client answer 3.6, spec 2.9).
        Otherwise the payee as recorded. This column used to print whatever was typed at
        approval, so finance was shown "mobile" as the destination of an 800,000 transfer.
      */
      render: (d) => {
        const verdict = verdictFor(d);
        return (
          <span className="inline-flex flex-col gap-0.5">
            <span className="font-medium">
              {verdict?.lenderPartyId ? <PartyName partyId={verdict.lenderPartyId} /> : d.payeeRef}
            </span>
            {verdict?.notes.map((note) => (
              <span key={note} className="text-[11px] text-status-warning-fg">
                {note}
              </span>
            ))}
          </span>
        );
      },
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (d) => <span className="whitespace-nowrap font-medium">{formatMoney(d.amount)}</span>,
    },
    {
      key: 'sourceRef',
      header: 'For',
      // Whose death, on which scheme, as a link to the claim -- where this used to be a raw id.
      render: (d) => {
        const context = contexts[d.disbursementId];
        const claim = context?.claim;
        if (d.purpose !== 'CLAIM_SETTLEMENT' || !claim) {
          return <span className="whitespace-nowrap font-mono text-xs">{d.sourceRef}</span>;
        }
        const member = context.member;
        return (
          <span className="inline-flex flex-col gap-0.5 text-xs">
            <Link to={`/staff/claims/${claim.claimId}`} className="font-medium hover:underline">
              {humanizeStatus(claim.claimType)} claim
            </Link>
            {member && (
              <span>
                {/* A borrower is freeform and carries their own name; an employee is a party. */}
                {member.memberName ??
                  (member.memberPartyId ? <PartyName partyId={member.memberPartyId} /> : 'Member')}
                {member.memberReference && (
                  <span className="text-muted-foreground"> ({member.memberReference})</span>
                )}
              </span>
            )}
            <span className="text-muted-foreground">
              {member ? 'scheme' : 'policy'} {claim.policyNumber}
            </span>
          </span>
        );
      },
    },
    {
      key: 'purpose',
      header: 'Purpose',
      secondary: true,
      render: (d) =>
        d.purpose ? humanizeStatus(d.purpose) : <span className="text-subtle-foreground">—</span>,
    },
    {
      key: 'waitingSince',
      header: 'Waiting since',
      // Not decoration. Every row is money the insurer owes and has not paid, and on a
      // credit-life claim an ageing row is a lender still carrying a dead borrower's loan.
      render: (d) => <span className="whitespace-nowrap">{formatInstant(d.waitingSince)}</span>,
    },
    {
      key: 'execute',
      header: '',
      align: 'right',
      render: (d) => {
        const verdict = verdictFor(d);
        const blocked = verdict !== null && !verdict.payable;
        return (
          <span className="inline-flex flex-col items-end gap-1">
            <Button
              size="sm"
              variant="ghost"
              // Blocked when this life must not be paid (again). Said beside the button rather
              // than hidden behind a disabled control nobody can explain.
              disabled={executingId === d.disbursementId || blocked}
              onClick={() => {
                setBankReference('');
                setConfirming(d);
              }}
            >
              {executingId === d.disbursementId ? 'Recording…' : 'Record payment made'}
            </Button>
            {blocked &&
              verdict.reasons.map((reason) => (
                <span key={reason} className="max-w-xs text-right text-[11px] text-status-danger-fg">
                  Do not transfer. {reason}
                </span>
              ))}
          </span>
        );
      },
    },
  ];

  function renderBody() {
    if (isInitialLoad(queue)) return <TableSkeleton columns={columns.length} />;

    if (queue.status === 'error' && queue.error && !queue.data) {
      return <ErrorPanel error={queue.error} onRetry={() => void loadAwaitingEft()} />;
    }

    if (rows.length === 0 && queue.status === 'success') {
      return (
        <EmptyState
          title="Nothing is waiting on a transfer"
          // Said as a fact about the queue, not as congratulation: an empty queue here and an
          // unreachable backend look identical to somebody who has been told a claim was settled.
          description="Every payout instructed by bank transfer has been recorded as made. A credit-life claim settlement appears here the moment it is approved."
        />
      );
    }

    return (
      <>
        {queue.status === 'error' && queue.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {queue.error.traceId && <span className="ml-1 font-mono">({queue.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(d) => d.disbursementId}
          caption="Bank transfers awaiting execution"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Bank transfers"
        description="Payouts instructed by bank transfer, oldest first. The platform cannot make these: make the transfer in the bank, then press Record payment made. That settles the claim, takes the borrower off the scheme dated to their death, updates the scheme's totals and books the expense."
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          {confirming && (
            <div className="border-b border-border px-3 py-2.5">
              <ConfirmAct
                heading="Has this transfer been made?"
                // The real values. This is a statement about specific money that has specifically
                // already left the insurer's account.
                consequence={
                  <>
                    Records that <strong>{formatMoney(confirming.amount)}</strong> has been paid to{' '}
                    <strong>
                      {verdictFor(confirming)?.lenderPartyId ? (
                        <PartyName partyId={verdictFor(confirming)?.lenderPartyId ?? ''} />
                      ) : (
                        confirming.payeeRef
                      )}
                    </strong>
                    . The claim settles; the borrower leaves the scheme, dated to their death; the
                    scheme&rsquo;s member count and cover drop by them; and the expense is booked
                    to the ledger.
                  </>
                }
                // True, and checked: there is no endpoint that un-executes a disbursement.
                reversal="Nothing here can undo it. If the transfer did not happen, the correction is a manual journal and a new instruction."
                confirmLabel="Record the transfer"
                tone="danger"
                busy={executeState.status === 'loading'}
                confirmDisabled={bankReference.trim() === ''}
                onConfirm={() => {
                  const id = confirming.disbursementId;
                  const reference = bankReference.trim();
                  setConfirming(null);
                  setExecutingId(id);
                  void markExecuted(id, reference).finally(() => setExecutingId(null));
                }}
                onCancel={() => setConfirming(null)}
              >
                {/* Explicit htmlFor, and the hint OUTSIDE the label. Wrapping both in one label
                    makes the accessible name the whole paragraph, so `getByLabel('Bank
                    reference')` matches only by substring and any edit to the hint silently
                    changes what a spec is matching. The hint belongs to aria-describedby, which
                    is also what a screen reader wants: the name is what to type, the description
                    is why. */}
                <label htmlFor="eft-bank-reference" className="block text-xs font-medium">
                  Bank reference
                </label>
                <input
                  id="eft-bank-reference"
                  aria-describedby="eft-bank-reference-hint"
                  className="mt-1 block w-full max-w-xs rounded border border-border-strong bg-surface px-2 py-1 font-mono text-xs"
                  value={bankReference}
                  onChange={(e) => setBankReference(e.target.value)}
                  autoFocus
                />
                <p id="eft-bank-reference-hint" className="mt-1 text-[11px] text-muted-foreground">
                  The bank&rsquo;s own reference for the transfer. Without it this is a claim
                  marked paid that nobody can trace.
                </p>
              </ConfirmAct>
              {/* The button is held AND the reason is stated. A disabled control with no
                  explanation is the thing people file tickets about; the backend refuses a blank
                  reference anyway, so this is the same rule said earlier rather than a new one. */}
              {bankReference.trim() === '' && (
                <p className="mt-1 text-[11px] text-status-danger-fg">
                  A bank reference is required before this can be recorded.
                </p>
              )}
            </div>
          )}

          {executeState.status === 'error' && executeState.error && (
            <InlineError
              lead="Could not record the transfer"
              error={executeState.error}
              className="rounded-none border-b border-border px-4"
            />
          )}

          {renderBody()}
        </div>
      </div>
    </>
  );
}
