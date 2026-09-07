import { zodResolver } from '@hookform/resolvers/zod';
import { useAuth } from 'react-oidc-context';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { InvoiceView } from '@/api/types';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { ConfirmAct } from '@/components/ConfirmAct';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
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

  return (
    <div className="divide-y divide-border">
      {rows.map((invoice) => (
        <InvoiceRow
          key={invoice.invoiceId ?? JSON.stringify(invoice)}
          policyNumber={policyNumber}
          invoice={invoice}
        />
      ))}
    </div>
  );
}

function InvoiceRow({ policyNumber, invoice }: { policyNumber: string; invoice: InvoiceView }) {
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
              <span className="text-[11px] text-status-danger-fg" title="Dunning escalation level (1-5)">
                L{invoice.dunningLevel}
              </span>
            )}
          </span>
        </div>
        <span className="shrink-0 text-sm font-medium">{formatMoney(invoice.amount)}</span>
      </div>

      {invoice.gracePeriodEndsAt && (
        <p className="mt-0.5 text-[11px] text-muted-foreground">
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
          <Button
            size="sm"
            variant="ghost"
            className={canWaive ? undefined : '-ml-2'}
            onClick={() => setAction('payment')}
          >
            Request payment
          </Button>
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
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {waiving.error.detail ?? waiving.error.title}
        </p>
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
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {requesting.error.detail ?? requesting.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" disabled={requesting.status === 'loading'}>
          {requesting.status === 'loading' ? 'Requesting…' : 'Request payment'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
