import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState, type FocusEvent } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { listInvoices } from '@/api/policies';
import type { InvoiceView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { UnsavedGuard } from '@/components/UnsavedGuard';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { useReceiptStore } from '@/store/receiptStore';
import { blankReceiptForm, methodOf, RECEIPT_METHODS, receiptFormSchema, toApiRequest, type ReceiptFormValues } from './receiptForm';

/** Invoices a receipt can still pay: not paid, not waived. */
const OPEN = new Set(['DUE', 'PARTIALLY_PAID', 'IN_GRACE', 'OVERDUE']);

/** What is still owed on an invoice, as the server stated it; the charge itself where no balance is stated. */
function owed(i: InvoiceView) {
  return i.balanceDue ?? i.amount ?? { amount: '0', currencyCode: 'TZS' };
}

/**
 * Record a receipt for premium the gateway did not pull (2026-10-27): cash, a bank deposit, a cheque, a Lipa-number
 * payment or a TANQR scan.
 *
 * Opened three ways, each prefilling what it knows through the URL: from a policy's invoice (policy and invoice), from
 * an agent's field receipt (policy, amount, cash, and the field receipt it closes), or bare from Payments. Saving
 * records it as awaiting approval -- a second finance person confirms it on its own page before it pays anything.
 */
export function NewReceiptPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const record = useReceiptStore((s) => s.record);
  const recording = useReceiptStore((s) => s.recording);

  const prefill = useMemo(() => {
    const method = methodOf(params.get('method') ?? '')?.value;
    return blankReceiptForm({
      policyNumber: params.get('policy') ?? '',
      invoiceId: params.get('invoice') ?? '',
      amount: params.get('amount') ?? '',
      reference: params.get('reference') ?? '',
      fieldReceiptId: params.get('fieldReceipt') ?? '',
      ...(method ? { method } : {}),
    });
  }, [params]);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ReceiptFormValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(receiptFormSchema), defaultValues: prefill });

  const policyNumber = useWatch({ control, name: 'policyNumber' });
  const method = useWatch({ control, name: 'method' });
  const invoiceId = useWatch({ control, name: 'invoiceId' });
  const chosenMethod = methodOf(method);

  // The policy's open invoices, read once the policy number is asked for.
  const [lookedUp, setLookedUp] = useState(prefill.policyNumber);
  const [invoices, setInvoices] = useState<InvoiceView[] | null>(null);
  const [invoiceError, setInvoiceError] = useState<string | null>(null);
  useEffect(() => {
    if (!lookedUp) return;
    let cancelled = false;
    listInvoices(lookedUp)
      .then((all) => {
        if (cancelled) return;
        const open = all.filter((i) => OPEN.has(i.status ?? '')).sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''));
        setInvoices(open);
        setInvoiceError(open.length === 0 ? `Nothing is owed on ${lookedUp}: every invoice is paid or waived.` : null);
        // The oldest open invoice is the one a payment usually settles; a prefilled one is kept.
        if (open.length > 0 && !open.some((i) => i.invoiceId === prefill.invoiceId)) {
          setValue('invoiceId', open[0]!.invoiceId ?? '', { shouldDirty: true });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setInvoices(null);
          setInvoiceError(`No policy ${lookedUp} could be read.`);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [lookedUp, prefill.invoiceId, setValue]);

  const invoice = invoices?.find((i) => i.invoiceId === invoiceId) ?? null;

  async function onSubmit(values: ReceiptFormValues) {
    const saved = await record(toApiRequest(values, invoice ? owed(invoice).currencyCode : 'TZS'));
    if (saved) navigate(`/staff/receipts/${saved.receiptId}`);
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Payments', to: '/staff/payments' }]}
        title="Record a receipt"
        description="Premium paid by cash, bank deposit, cheque, Lipa number or TANQR. It pays the invoice once a second finance person confirms it."
      />
      <FormLayout formId="new-receipt-form" outlineLabel="Receipt sections">
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <UnsavedGuard when={isDirty && !isSubmitting} what="This receipt" />

          <div data-outline="What it pays" className="space-y-4">
            <div className="flex flex-wrap items-end gap-2">
              <FormField label="Policy number" error={errors.policyNumber?.message}>
                <Input
                  className="w-48 font-mono"
                  placeholder="POL-XXXXXXXX"
                  {...register('policyNumber', {
                    setValueAs: (v: string) => v.toUpperCase(),
                    // Looked up as soon as the number is in, so the invoice choice follows without another click.
                    onBlur: (e: FocusEvent<HTMLInputElement>) => {
                      const typed = e.target.value.trim().toUpperCase();
                      if (typed) setLookedUp(typed);
                    },
                  })}
                />
              </FormField>
              <Button type="button" variant="outline" onClick={() => setLookedUp(policyNumber.trim().toUpperCase())}>
                Find invoices
              </Button>
            </div>
            {invoiceError && <p className="text-sm text-status-warning-fg">{invoiceError}</p>}
            {invoices && invoices.length > 0 && (
              <FormField label="Invoice it pays" error={errors.invoiceId?.message}>
                <Select {...register('invoiceId')}>
                  {invoices.map((i) => (
                    <option key={i.invoiceId} value={i.invoiceId}>
                      Due {formatDate(i.dueDate)} · {formatMoney(owed(i))} owed
                    </option>
                  ))}
                </Select>
              </FormField>
            )}
          </div>

          <div data-outline="How it was paid" className="space-y-4">
            <FormField label="How the money came in" hint={chosenMethod?.hint} error={errors.method?.message}>
              <Select {...register('method')}>
                {RECEIPT_METHODS.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label={chosenMethod?.reference ?? 'Reference'} error={errors.reference?.message}>
              <Input className="font-mono" {...register('reference')} />
            </FormField>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField
                label={`Amount (${invoice ? owed(invoice).currencyCode : 'TZS'})`}
                hint={invoice ? `At most ${formatMoney(owed(invoice))}, what is owed.` : undefined}
                error={errors.amount?.message}
              >
                <Input inputMode="decimal" {...register('amount')} />
              </FormField>
              <FormField label="Received on" error={errors.receivedOn?.message}>
                <Controller
                  control={control}
                  name="receivedOn"
                  render={({ field }) => <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />}
                />
              </FormField>
            </div>
            <FormField label="Paid by (optional)" hint="The payer's name or number, as the slip shows it." error={errors.payerRef?.message}>
              <Input {...register('payerRef')} />
            </FormField>
            <FormField label="Note (optional)" error={errors.note?.message}>
              <Input {...register('note')} />
            </FormField>
          </div>

          {recording.status === 'error' && recording.error && (
            <InlineError lead="Could not record the receipt" error={recording.error} />
          )}

          <div className="flex gap-2">
            <Button type="submit" variant="primary" pending={recording.status === 'loading'}>
              Record receipt
            </Button>
            <Button asChild variant="ghost">
              <Link to="/staff/payments">Cancel</Link>
            </Button>
          </div>
        </form>
      </FormLayout>
    </>
  );
}
