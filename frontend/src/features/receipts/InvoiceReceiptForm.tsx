import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import type { Receipt, ReceiptMethod } from '@/api/receipts';
import type { InvoiceView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { useReceiptStore } from '@/store/receiptStore';
import { blankReceiptForm, methodOf, receiptFormSchema, toApiRequest, type ReceiptFormValues } from './receiptForm';

/**
 * Money already paid some way other than mobile money, recorded where the invoice is (2026-10-27): the method chosen
 * under "Request payment", its reference, the amount (what is owed, to start), the day and the payer. Recording keeps
 * you on the policy; the receipt pays the invoice once a second finance person confirms it.
 */
export function InvoiceReceiptForm({
  policyNumber,
  invoice,
  method,
  onDone,
}: {
  policyNumber: string;
  invoice: InvoiceView;
  method: ReceiptMethod;
  onDone: () => void;
}) {
  const record = useReceiptStore((s) => s.record);
  const recording = useReceiptStore((s) => s.recording);
  const [saved, setSaved] = useState<Receipt | null>(null);
  const owed = invoice.balanceDue ?? invoice.amount;
  const chosen = methodOf(method);

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<ReceiptFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(receiptFormSchema),
    values: blankReceiptForm({
      policyNumber,
      invoiceId: invoice.invoiceId ?? '',
      method,
      amount: owed?.amount ?? '',
    }),
    resetOptions: { keepDirtyValues: true },
  });

  async function onSubmit(values: ReceiptFormValues) {
    const result = await record(toApiRequest({ ...values, method }, owed?.currencyCode ?? 'TZS'));
    if (result) setSaved(result);
  }

  if (saved) {
    return (
      <div className="space-y-2" role="status">
        <p className="text-sm">
          Recorded {formatMoney(saved.amount)} by {chosen?.label.toLowerCase()}. It pays this invoice once a second
          finance person confirms it.
        </p>
        <div className="flex items-center gap-1.5">
          <Button asChild size="sm" variant="outline">
            <Link to={`/staff/receipts/${saved.receiptId}`}>Open the receipt</Link>
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={onDone}>
            Done
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form className="space-y-2" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      {chosen && <p className="text-xs text-muted-foreground">{chosen.hint}</p>}
      <FormField label={chosen?.reference ?? 'Reference'} error={errors.reference?.message}>
        <Input inputSize="sm" className="font-mono" {...register('reference')} />
      </FormField>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <FormField label={`Amount (${owed?.currencyCode ?? 'TZS'})`} error={errors.amount?.message}>
          <Input inputSize="sm" inputMode="decimal" {...register('amount')} />
        </FormField>
        <FormField label="Received on" error={errors.receivedOn?.message}>
          <Controller
            control={control}
            name="receivedOn"
            render={({ field }) => <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
      </div>
      <FormField label="Paid by (optional)" error={errors.payerRef?.message}>
        <Input inputSize="sm" {...register('payerRef')} />
      </FormField>

      {recording.status === 'error' && recording.error && (
        <InlineError lead="Could not record the receipt" error={recording.error} />
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" pending={recording.status === 'loading'}>
          Record payment received
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
