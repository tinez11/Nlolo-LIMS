import { can } from '@/auth/access';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { decideLenderRefund, undoWaiver } from '@/api/policies';
import type { EnrolmentSubmissionView, InvoiceView, PolicyMemberView, PremiumCreditView } from '@/api/types';
import type { ApiError } from '@/lib/apiError';
import { FilterChip } from '@/components/FilterChip';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { compareAmounts, formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectDetail,
  selectInvoices,
  selectWaivingInvoice,
  usePolicyStore,
} from '@/store/policyStore';
import { hasMovement, owesSomething } from './invoiceReconciliation';
import { exitReasonLabel } from './memberStanding';
import { useInvoiceReconciliation, type CreditLine } from './useInvoiceReconciliation';
import {
  blankWaiveInvoiceForm,
  toApiRequest as toWaiverApiRequest,
  waiveInvoiceFormSchema,
  type WaiveInvoiceFormValues,
} from './waiveInvoiceForm';
import { Input } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { Tip } from '@/components/ui/tooltip';
import type { Receipt } from '@/api/receipts';
import { StaffName } from '@/components/StaffName';
import { methodWords } from '@/features/payments/paymentWords';
import { useReceiptStore } from '@/store/receiptStore';

/**
 * `POST /invoices/{invoiceId}/waiver` (**finance staff only** -- FINANCE_OFFICER
 * or ADMIN, tightened from plain staff, because writing off a premium is a final
 * ledger movement and an underwriter has no business authorising one) and
 * `.../payment-request` (staff/customer/agent) live on each invoice row --
 * unlike the read-only table this replaced, an invoice is now something staff
 * can act on.
 *
 * Since 2026-11-08 the server refuses a waiver of an invoice already waived or paid, and of a proposed
 * policy's premium (it starts the cover, and is paid, not waived) -- POL-3E440FE9's starting premium was
 * waived and the policy could never come on cover. A waiver can be undone, saying why, which owes the
 * premium again. The row offers exactly what the server allows.
 */
export function InvoicesPanel({
  policyNumber,
}: {
  policyNumber: string;
}) {
  const loadInvoices = usePolicyStore((s) => s.loadInvoices);
  const invoices = usePolicyStore(selectInvoices(policyNumber));
  const category = usePolicyStore(selectDetail(policyNumber)).data?.productCategory;
  const isScheme = category === 'CREDIT_LIFE' || category === 'GROUP_LIFE';
  // Credits and the monthly file behind each invoice -- see useInvoiceReconciliation.
  const reconciliation = useInvoiceReconciliation(policyNumber, isScheme);
  // Declared before the early returns below, as every hook must be.
  const [statusFilter, setStatusFilter] = useState<string | null>(null);

  useEffect(() => {
    void loadInvoices(policyNumber);
  }, [policyNumber, loadInvoices]);

  if (isInitialLoad(invoices)) return <LoadingBlock />;
  if (invoices.status === 'error' && invoices.error && invoices.data === null) {
    return <ErrorPanel error={invoices.error} onRetry={() => void loadInvoices(policyNumber)} />;
  }
  const rows = invoices.data ?? [];
  if (rows.length === 0) {
    return <EmptyState title="No invoices" description="Nothing has been billed on this policy." />;
  }

  /*
    Filtered HERE rather than by refetching with a `status` param, and not paged at all.
    `GET /policies/{n}/invoices` takes no page/pageSize and does not need one: a policy's
    invoices are pre-generated about twelve months ahead, so the whole list is bounded by
    construction and already in hand. A pager over a fully-downloaded array would lie about
    the network, and a refetch per chip would be a round trip for data on the screen.

    Only the statuses actually present get a chip. A row of six filters where four can never
    match is furniture, and on a fresh policy every invoice is DUE.
  */
  const present = [...new Set(rows.map((r) => r.status).filter(Boolean))] as string[];
  /*
    The filter is only honoured while the status it names still exists, and that is a real
    trap rather than defensive coding. Waiving is the one action here that MOVES an invoice
    between statuses: filter to DUE on a policy holding {DUE, WAIVED}, waive the last DUE
    one, and `present` collapses to a single status -- so the chip row below unmounts while
    `statusFilter` still says 'DUE', stranding the reader on an empty list with no control
    left to clear it. Deriving the effective filter from what is actually there means the
    view falls back to All the moment its status is gone.
  */
  const effective = statusFilter && present.includes(statusFilter) ? statusFilter : null;
  const shown = effective ? rows.filter((r) => r.status === effective) : rows;

  return (
    <div>
      {present.length > 1 && (
        <div className="flex flex-wrap items-center gap-1.5 gap-y-2 border-b border-border px-4 py-2.5">
          <FilterChip
            label="All"
            active={effective === null}
            onClick={() => setStatusFilter(null)}
          />
          {present.map((value) => (
            <FilterChip
              key={value}
              label={<StatusBadge kind="invoice" value={value} />}
              bare
              active={effective === value}
              onClick={() => setStatusFilter(value)}
            />
          ))}
        </div>
      )}

      {shown.length === 0 ? (
        <EmptyState
          title="None with that status"
          description="Choose All above to see every invoice on this policy."
        />
      ) : (
        <div className="divide-y divide-border">
          {shown.map((invoice) => (
        <InvoiceRow
          key={invoice.invoiceId ?? JSON.stringify(invoice)}
          policyNumber={policyNumber}
          invoice={invoice}
          credits={reconciliation.creditsByInvoice[invoice.invoiceId ?? ''] ?? []}
          onRefundDecided={reconciliation.reload}
          file={
            invoice.enrolmentSubmissionId
              ? (reconciliation.filesById[invoice.enrolmentSubmissionId] ?? null)
              : null
              }
            />
          ))}
        </div>
      )}
    </div>
  );
}

function InvoiceRow({
  policyNumber,
  invoice,
  credits,
  file,
  onRefundDecided,
}: {
  policyNumber: string;
  invoice: InvoiceView;
  credits: CreditLine[];
  file: EnrolmentSubmissionView | null;
  onRefundDecided: () => void;
}) {
  const [action, setAction] = useState<'waive' | 'undo' | null>(null);
  // Mirrors the endpoint exactly: REALM_STAFF and (FINANCE_OFFICER or ADMIN).
  const mayWaive = can('collections.manage');
  const policyStatus = usePolicyStore(selectDetail(policyNumber)).data?.status;
  // What the server allows (2026-11-08): a waiver of what is still owed, once the policy is on cover; an undo of a waiver.
  const canWaive = mayWaive && invoice.status !== 'WAIVED' && invoice.status !== 'PAID' && policyStatus !== 'PROPOSED';
  const canUndo = mayWaive && invoice.status === 'WAIVED';
  const invoiceId = invoice.invoiceId ?? '';
  // What has been recorded against this invoice and still waits for a second person (2026-10-28). Without it a
  // recorded receipt showed nowhere here, the invoice still read Due, and POL-C0B3B81A was receipted twice.
  const loadWaiting = useReceiptStore((s) => s.loadWaiting);
  const waitingResource = useReceiptStore((s) => s.waiting[invoiceId]);
  const waiting = waitingResource?.data ?? NONE_WAITING;
  const owing = owesSomething(invoice);
  useEffect(() => {
    if (invoiceId && owing && can('collections.view')) void loadWaiting(invoiceId);
  }, [invoiceId, owing, loadWaiting]);

  return (
    <div className="px-4 py-3">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <span className="text-sm">{formatDate(invoice.dueDate)}</span>
          <span className="ml-2 inline-flex items-center gap-1.5">
            <StatusBadge kind="invoice" value={invoice.status} />
            {typeof invoice.dunningLevel === 'number' && (
              <Tip content="Dunning escalation level (1-5)">
                <span className="text-xs text-status-danger-fg">
                  L{invoice.dunningLevel}
                  <span className="sr-only"> — dunning escalation level, of 5</span>
                </span>
              </Tip>
            )}
          </span>
        </div>
        {/* What is OWED leads. This used to be the charged amount alone, so an invoice with
            4,200 credited back read 13,800 when 9,600 was due. */}
        <span className="shrink-0 text-right">
          <span className="block text-sm font-medium">
            {formatMoney(invoice.balanceDue ?? invoice.amount)}
          </span>
          {invoice.balanceDue && invoice.balanceDue.amount !== invoice.amount?.amount && (
            <span className="block text-xs text-muted-foreground">due of {formatMoney(invoice.amount)} charged</span>
          )}
        </span>
      </div>

      {/* Which monthly file this invoice charged, so it can be traced to its borrowers. */}
      {invoice.enrolmentSubmissionId && (
        <p className="mt-0.5 text-xs text-muted-foreground">
          Charged by{' '}
          <Link to={`/staff/credit-life-schemes/${policyNumber}`} className="underline hover:text-foreground">
            {file?.fileName ?? 'a monthly enrolment file'}
          </Link>
          {file?.acceptedAt && <> · accepted {formatDate(file.acceptedAt)}</>}
          {file?.enrolledCount != null && <> · {file.enrolledCount} borrower{file.enrolledCount === 1 ? '' : 's'}</>}
        </p>
      )}

      <InvoiceReconciliationLines invoice={invoice} credits={credits} />
      {credits.filter(({ credit }) => credit.refundStatus).map(({ credit }) => (
        <LenderRefundLine key={credit.creditId} credit={credit} onDecided={onRefundDecided} />
      ))}

      {waiting.length > 0 && <WaitingReceipts receipts={waiting} />}

      {invoice.gracePeriodEndsAt && (
        <p className="mt-0.5 text-xs text-muted-foreground">
          Grace ends {formatDate(invoice.gracePeriodEndsAt)}
        </p>
      )}

      {/* Every deposit, mobile money included, is taken on the one Deposit page (2026-11-07): an invoice row only
          waives. Waiving is finance-gated server-side, so this only avoids offering a button that would 403. */}
      {invoiceId && action === null && (canWaive || canUndo) && (
        <div className="mt-1.5 flex items-center gap-1.5">
          <Button size="sm" variant="ghost" className="-ml-2" onClick={() => setAction(canUndo ? 'undo' : 'waive')}>
            {canUndo ? 'Undo waiver' : 'Waive'}
          </Button>
        </div>
      )}
      {mayWaive && policyStatus === 'PROPOSED' && owing && (
        <p className="mt-0.5 text-xs text-muted-foreground">
          This premium starts the cover: it is paid, not waived.
        </p>
      )}

      {action === 'waive' && invoiceId && (
        <WaiveForm
          policyNumber={policyNumber}
          invoice={invoice}
          onDone={() => setAction(null)}
        />
      )}
      {action === 'undo' && invoiceId && (
        <UndoWaiverForm policyNumber={policyNumber} invoice={invoice} onDone={() => setAction(null)} />
      )}
    </div>
  );
}

const NONE_WAITING: Receipt[] = [];

/**
 * The receipts recorded against an invoice that a second person has not yet confirmed -- each with how it came in,
 * its reference, who recorded it and a way to it. The invoice is paid only when one is confirmed under Approvals.
 */
/** Deposits recorded and waiting for a second finance person; `lands` says what each will pay once confirmed. */
export function WaitingReceipts({
  receipts,
  lands = 'It pays this invoice once a second finance person confirms it under Approvals.',
}: {
  receipts: Receipt[];
  lands?: string;
}) {
  return (
    <div className="mt-2 rounded-md border border-border bg-band px-3 py-2" aria-label="Deposits waiting to be confirmed">
      <p className="text-xs font-medium">
        {receipts.length === 1 ? 'A deposit is waiting to be confirmed' : `${receipts.length} deposits are waiting to be confirmed`}
      </p>
      <ul className="mt-1 space-y-1">
        {receipts.map((r) => (
          <li key={r.receiptId} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 text-xs">
            <span className="min-w-0 text-muted-foreground">
              <span className="font-medium text-foreground">{methodWords(r.method)}</span> {r.reference} · recorded by{' '}
              <StaffName subject={r.recordedBy} />
            </span>
            <span className="flex shrink-0 items-baseline gap-3">
              <span className="font-medium tabular-nums text-foreground">{formatMoney(r.amount)}</span>
              <Link to={`/staff/receipts/${encodeURIComponent(r.receiptId)}`} className="underline hover:text-foreground">
                Open
              </Link>
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-1 text-xs text-muted-foreground">{lands}</p>
    </div>
  );
}

/**
 * Charged, less each credit (with WHO it was for and why), less paid, equals owed -- written out,
 * so an invoice can be checked against the monthly files line by line. Only rendered once the
 * invoice has moved; an untouched invoice owes exactly what it says.
 */
function InvoiceReconciliationLines({ invoice, credits }: { invoice: InvoiceView; credits: CreditLine[] }) {
  if (!hasMovement(invoice) && credits.length === 0) return null;
  return (
    <dl className="mt-1.5 space-y-0.5 rounded-md bg-surface-muted px-2.5 py-1.5 text-xs">
      <div className="flex justify-between gap-2">
        <dt className="text-label">Charged</dt>
        <dd>{formatMoney(invoice.amount)}</dd>
      </div>
      {credits.map(({ credit, member }) => (
        <div key={credit.creditId} className="flex justify-between gap-2">
          <dt className="text-label">
            Credited · {member?.memberName ?? 'a member'}
            {member?.memberReference && ` (${member.memberReference})`}
            {' · '}
            {exitReasonLabel(credit.exitReason as PolicyMemberView['exitReason'])?.toLowerCase() ??
              credit.exitReason}
            {' · '}
            {formatDate(credit.exitDate)}
          </dt>
          <dd>−{formatMoney(credit.amount)}</dd>
        </div>
      ))}
      {invoice.amountPaid && compareAmounts(invoice.amountPaid.amount, '0.00') > 0 && (
        <div className="flex justify-between gap-2">
          <dt className="text-label">Paid</dt>
          <dd>−{formatMoney(invoice.amountPaid)}</dd>
        </div>
      )}
      <div className="flex justify-between gap-2 border-t border-border pt-0.5 font-medium">
        <dt>Balance due</dt>
        <dd>{formatMoney(invoice.balanceDue ?? invoice.amount)}</dd>
      </div>
    </dl>
  );
}

function WaiveForm({
  policyNumber,
  invoice,
  onDone,
}: {
  policyNumber: string;
  // The whole invoice, so the confirmation can name the amount and say what
  // waiving a PAID one would mean. An id alone cannot describe the consequence.
  invoice: InvoiceView;
  onDone: () => void;
}) {
  const invoiceId = invoice.invoiceId ?? '';
  const waiveInvoice = usePolicyStore((s) => s.waiveInvoice);
  const resetWaiveInvoice = usePolicyStore((s) => s.resetWaiveInvoice);
  const waiving = usePolicyStore(selectWaivingInvoice(invoiceId));

  useEffect(() => {
    resetWaiveInvoice(invoiceId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [invoiceId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<WaiveInvoiceFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(waiveInvoiceFormSchema),
    defaultValues: blankWaiveInvoiceForm(),
  });

  const [pending, setPending] = useState<WaiveInvoiceFormValues | null>(null);

  async function commit(values: WaiveInvoiceFormValues) {
    await waiveInvoice(policyNumber, invoiceId, toWaiverApiRequest(values));
    if (usePolicyStore.getState().waivingInvoice[invoiceId]?.status === 'success') onDone();
  }

  return (
    <form className="mt-2 space-y-2 rounded-md border border-border p-2.5" onSubmit={(e) => void handleSubmit(setPending)(e)}>
      <FormField required label="Reason" error={errors.reason?.message}>
        <Input
          inputSize="sm"
          placeholder="Goodwill gesture, customer hardship"
          {...register('reason')}
        />
      </FormField>

      {waiving.status === 'error' && waiving.error && (
        <InlineError error={waiving.error} />
      )}

      {pending ? (
        <ConfirmAct
          heading="Waive this invoice?"
          tone="danger"
          consequence={
            <>
              Write off <strong>{formatMoney(invoice.amount)}</strong> due{' '}
              {formatDate(invoice.dueDate)}, on the grounds:{' '}
              <strong>{pending.reason}</strong>.
            </>
          }
          // A payment arriving against a waived invoice does not clear it; finance can undo the waiver (2026-11-08).
          reversal={
            invoice.status === 'PARTIALLY_PAID'
              ? 'This invoice is partly paid: the payment stays recorded, and the rest is written off. A payment arriving later will not clear it; finance can undo the waiver, saying why.'
              : 'A payment arriving later will not clear a waived premium; finance can undo the waiver, saying why.'
          }
          // Not "Waive invoice" again: the arming button already says that, and
          // two identical buttons a click apart defeat the point of the second.
          confirmLabel="Write off invoice"
          busy={waiving.status === 'loading'}
          onConfirm={() => void commit(pending)}
          onCancel={() => setPending(null)}
        />
      ) : (
        <div className="flex items-center gap-1.5">
          <Button type="submit" size="sm">
            Waive invoice
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Cancel
          </Button>
        </div>
      )}
    </form>
  );
}

/** A waiver taken back, saying why (2026-11-08): the premium is owed again and the ledger carries it again. */
function UndoWaiverForm({ policyNumber, invoice, onDone }: { policyNumber: string; invoice: InvoiceView; onDone: () => void }) {
  const invoiceId = invoice.invoiceId ?? '';
  const loadInvoices = usePolicyStore((s) => s.loadInvoices);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState<WaiveInvoiceFormValues | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<WaiveInvoiceFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(waiveInvoiceFormSchema),
    defaultValues: blankWaiveInvoiceForm(),
  });

  async function commit(values: WaiveInvoiceFormValues) {
    setBusy(true);
    setError(null);
    try {
      await undoWaiver(invoiceId, toWaiverApiRequest(values));
      await loadInvoices(policyNumber);
      onDone();
    } catch (cause) {
      setError(cause as ApiError);
      setPending(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="mt-2 space-y-2 rounded-md border border-border p-2.5" onSubmit={(e) => void handleSubmit(setPending)(e)}>
      <FormField required label="Why is the waiver undone?" error={errors.reason?.message}>
        <Input inputSize="sm" placeholder="Waived in error; the customer will pay" {...register('reason')} />
      </FormField>
      {error && <InlineError error={error} />}
      {pending ? (
        <ConfirmAct
          heading="Undo this waiver?"
          consequence={
            <>
              The premium due {formatDate(invoice.dueDate)} is owed again —{' '}
              <strong>{formatMoney(invoice.amount)}</strong> charged, less anything paid — on the grounds:{' '}
              <strong>{pending.reason}</strong>.
            </>
          }
          reversal="It can be waived again later, saying why."
          confirmLabel="Owe it again"
          busy={busy}
          onConfirm={() => void commit(pending)}
          onCancel={() => setPending(null)}
        />
      ) : (
        <div className="flex items-center gap-1.5">
          <Button type="submit" size="sm">
            Undo waiver
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Cancel
          </Button>
        </div>
      )}
    </form>
  );
}

const REFUND_WORDS: Record<NonNullable<PremiumCreditView['refundStatus']>, string> = {
  AWAITING_APPROVAL: 'waiting for a second person to approve it',
  REQUESTED: 'approved; waiting for finance to make the bank transfer',
  PAID: 'paid',
  FAILED: 'the transfer failed; still owed to the lender',
  REFUSED: 'refused; still owed to the lender',
};

/**
 * The part of a credit the lender had already paid, going back to it by bank transfer (billing V13), and where it
 * stands. One raised automatically waits for a second person (payouts.approve, money movement step 4).
 */
function LenderRefundLine({ credit, onDecided }: { credit: PremiumCreditView; onDecided: () => void }) {
  const [armed, setArmed] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  if (!credit.refundStatus || !credit.refundAmount) return null;
  const amount = formatMoney(credit.refundAmount);

  async function decide(rejectReason: string | null) {
    setBusy(true);
    setError(null);
    try {
      await decideLenderRefund(credit.creditId, rejectReason);
      onDecided();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
      setArmed(false);
    }
  }

  return (
    <div className="mt-1 space-y-1.5 text-xs">
      <p className="text-muted-foreground">
        Paid back to the lender: {amount} · {REFUND_WORDS[credit.refundStatus]}
        {credit.refundRefusalReason ? ` (${credit.refundRefusalReason})` : ''}
      </p>
      {error && <InlineError error={error} />}
      {credit.refundStatus === 'AWAITING_APPROVAL' && can('payouts.approve') && (
        armed ? (
          <ConfirmAct
            heading="Approve this refund to the lender?"
            consequence={<><strong>{amount}</strong> goes to finance&rsquo;s queue of bank transfers to {credit.refundPayee}.</>}
            reversal="If the transfer fails, it stays owed to the lender."
            confirmLabel={`Pay ${amount}`}
            tone="danger"
            busy={busy}
            onConfirm={() => void decide(null)}
            onCancel={() => setArmed(false)}
          />
        ) : rejecting ? (
          <div className="space-y-2">
            <FormField required label="Why is it rejected?">
              <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
            </FormField>
            <div className="flex gap-2">
              <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                onClick={() => void decide(reason.trim())}>
                Reject refund
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
                Cancel
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => setArmed(true)}>
              Approve refund
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setRejecting(true)}>
              Reject
            </Button>
          </div>
        )
      )}
    </div>
  );
}
