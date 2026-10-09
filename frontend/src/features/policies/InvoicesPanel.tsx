import { zodResolver } from '@hookform/resolvers/zod';
import { useAuth } from 'react-oidc-context';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import type { EnrolmentSubmissionView, InvoiceView, PolicyMemberView } from '@/api/types';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import { FilterChip } from '@/components/FilterChip';
import { InlineError } from '@/components/InlineError';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { compareAmounts, formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectDetail,
  selectInvoices,
  selectRequestingPayment,
  selectWaivingInvoice,
  usePolicyStore,
} from '@/store/policyStore';
import {
  blankRequestPaymentForm,
  requestPaymentFormSchema,
  toApiRequest as toPaymentApiRequest,
  type RequestPaymentFormValues,
} from './requestPaymentForm';
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

/**
 * `POST /invoices/{invoiceId}/waiver` (**finance staff only** -- FINANCE_OFFICER
 * or ADMIN, tightened from plain staff, because writing off a premium is a final
 * ledger movement and an underwriter has no business authorising one) and
 * `.../payment-request` (staff/customer/agent) live on each invoice row --
 * unlike the read-only table this replaced, an invoice is now something staff
 * can act on.
 *
 * `PremiumInvoice.waive()` has genuinely no status guard at all (confirmed by
 * reading the domain method): it unconditionally sets WAIVED regardless of
 * current status, even PAID. This is a real platform quirk, not something to
 * paper over with an invented client-side rule the backend does not enforce
 * -- the action stays enabled for every invoice, and staff judgement is the
 * only guard that exists.
 */
export function InvoicesPanel({ policyNumber }: { policyNumber: string }) {
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
}: {
  policyNumber: string;
  invoice: InvoiceView;
  credits: CreditLine[];
  file: EnrolmentSubmissionView | null;
}) {
  const [action, setAction] = useState<'waive' | 'payment' | null>(null);
  // Mirrors the endpoint exactly: REALM_STAFF and (FINANCE_OFFICER or ADMIN).
  const canWaive = canSeeFinance(readIdentity(useAuth().user?.access_token));
  const invoiceId = invoice.invoiceId ?? '';

  return (
    <div className="px-4 py-3">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <span className="text-sm">{formatDate(invoice.dueDate)}</span>
          <span className="ml-2 inline-flex items-center gap-1.5">
            <StatusBadge kind="invoice" value={invoice.status} />
            {typeof invoice.dunningLevel === 'number' && (
              <span className="text-xs text-status-danger-fg" title="Dunning escalation level (1-5)">
                L{invoice.dunningLevel}
              </span>
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

      {invoice.gracePeriodEndsAt && (
        <p className="mt-0.5 text-xs text-muted-foreground">
          Grace ends {formatDate(invoice.gracePeriodEndsAt)}
        </p>
      )}

      {/* Hidden while a form is open, not merely redundant: "Request payment" is
          otherwise both this toggle's label and the open form's own submit
          button label, an ambiguous duplicate on screen and for any test
          querying by accessible name. */}
      {invoiceId && action === null && (
        <div className="mt-1.5 flex items-center gap-1.5">
          {/* Waiving is finance-gated server-side, so this only avoids offering a button that
              can now do nothing but 403. Requesting payment stays open to everyone, because it
              takes nothing away from anyone -- agents and customers can both do it. */}
          {canWaive && (
            <Button size="sm" variant="ghost" className="-ml-2" onClick={() => setAction('waive')}>
              Waive
            </Button>
          )}
          {/* Only while something is owed. The request asks for the balance due, and the
              backend refuses one on an invoice payments and credits already cover. */}
          {owesSomething(invoice) && (
            <Button
              size="sm"
              variant="ghost"
              className={canWaive ? undefined : '-ml-2'}
              onClick={() => setAction('payment')}
            >
              Request payment
            </Button>
          )}
        </div>
      )}

      {action === 'waive' && invoiceId && (
        <WaiveForm
          policyNumber={policyNumber}
          invoice={invoice}
          onDone={() => setAction(null)}
        />
      )}
      {action === 'payment' && invoiceId && (
        <PaymentRequestForm
          policyNumber={policyNumber}
          invoiceId={invoiceId}
          onDone={() => setAction(null)}
        />
      )}
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
        <dt className="text-muted-foreground">Charged</dt>
        <dd>{formatMoney(invoice.amount)}</dd>
      </div>
      {credits.map(({ credit, member }) => (
        <div key={credit.creditId} className="flex justify-between gap-2">
          <dt className="text-muted-foreground">
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
          <dt className="text-muted-foreground">Paid</dt>
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
      <FormField label="Reason" error={errors.reason?.message}>
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
          /*
            Both halves are PremiumInvoice's real behaviour, not a caution:
            waive() sets WAIVED with no status guard whatsoever -- it will
            happily waive a PAID invoice -- and the payment path documents WAIVED
            as terminal and never overwritten by a late payment. So this is one
            of the few genuinely one-way doors in the console, and on an already
            paid invoice it is also a silent contradiction of the money received.
          */
          reversal={
            invoice.status === 'PAID' || invoice.status === 'PARTIALLY_PAID'
              ? `This invoice is already ${invoice.status === 'PAID' ? 'paid' : 'partly paid'}. Waiving is still permitted and cannot be undone — the payment stays recorded against an invoice that then reads as written off.`
              : 'Waived is permanent: it cannot be un-waived, and a payment arriving later will not clear it.'
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

function PaymentRequestForm({
  policyNumber,
  invoiceId,
  onDone,
}: {
  policyNumber: string;
  invoiceId: string;
  onDone: () => void;
}) {
  const requestPaymentForInvoice = usePolicyStore((s) => s.requestPaymentForInvoice);
  const resetRequestPaymentForInvoice = usePolicyStore((s) => s.resetRequestPaymentForInvoice);
  const requesting = usePolicyStore(selectRequestingPayment(invoiceId));
  // Minted once per mount, reused across retries -- same idiom as every other
  // MutationAttempt on this console. A genuinely new attempt (e.g. retrying
  // with a fresh key after a decline) naturally gets one by reopening this
  // form, which remounts it.
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    resetRequestPaymentForInvoice(invoiceId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [invoiceId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RequestPaymentFormValues>({
    resolver: zodResolver(requestPaymentFormSchema),
    defaultValues: blankRequestPaymentForm(),
  });

  async function onSubmit(values: RequestPaymentFormValues) {
    await requestPaymentForInvoice(policyNumber, invoiceId, toPaymentApiRequest(values), attempt);
    if (usePolicyStore.getState().requestingPayment[invoiceId]?.status === 'success') onDone();
  }

  return (
    <form className="mt-2 space-y-2 rounded-md border border-border p-2.5" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <FormField label="Payer reference" error={errors.payerRef?.message}>
        <Input inputSize="sm" placeholder="Mobile-money source" {...register('payerRef')} />
      </FormField>

      {requesting.status === 'error' && requesting.error && (
        <InlineError error={requesting.error} />
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" pending={requesting.status === 'loading'}>
          Request payment
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
