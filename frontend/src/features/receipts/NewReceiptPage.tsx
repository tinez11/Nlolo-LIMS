import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState, type FocusEvent } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { listInvoices, listLoans } from '@/api/policies';
import type { InvoiceView, LoanView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { UnsavedGuard } from '@/components/UnsavedGuard';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { todayIso } from '@/lib/dates';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMoney } from '@/lib/money';
import { useReceiptStore } from '@/store/receiptStore';
import {
  blankReceiptForm,
  methodOf,
  RECEIPT_METHODS,
  receiptFormSchema,
  receiptOrigin,
  toApiRequest,
  type ReceiptFormValues,
} from './receiptForm';

/** Invoices still owed: not paid, not waived. */
const OPEN = new Set(['DUE', 'PARTIALLY_PAID', 'IN_GRACE', 'OVERDUE']);

/** Loans a deposit can still repay -- the two statuses the server accepts a repayment on. */
const REPAYABLE = new Set(['DISBURSED', 'REPAYING']);

/** What is still owed on an invoice, as the server stated it; the charge itself where no balance is stated. */
function owed(i: InvoiceView) {
  return i.balanceDue ?? i.amount ?? { amount: '0', currencyCode: 'TZS' };
}

/** What the invoices fallen due owe together, in their currency; null when none is owed. */
function totalOwed(due: InvoiceView[]) {
  if (due.length === 0) return null;
  const sum = due.reduce((total, i) => total + Number(owed(i).amount), 0);
  return { amount: sum.toFixed(2), currencyCode: owed(due[0]!).currencyCode };
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
    // Only a method recorded by hand: a mobile-money request is never typed in here.
    const method = RECEIPT_METHODS.find((m) => m.value === params.get('method'))?.value;
    return blankReceiptForm({
      policyNumber: params.get('policy') ?? '',
      paysFor: params.get('for') === 'LOAN' ? 'LOAN' : 'PREMIUM',
      loanId: params.get('loan') ?? '',
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
  const paysFor = useWatch({ control, name: 'paysFor' });
  const loanId = useWatch({ control, name: 'loanId' });
  const chosenMethod = methodOf(method);

  // The policy's open invoices, read once the policy number is asked for -- not to choose one (a deposit goes onto the
  // policy's balance, money movement step 5), but to say what it owes now and to catch a policy number that is wrong.
  const [lookedUp, setLookedUp] = useState(prefill.policyNumber);
  const [invoices, setInvoices] = useState<InvoiceView[] | null>(null);
  const [invoiceError, setInvoiceError] = useState<string | null>(null);
  useEffect(() => {
    if (!lookedUp) return;
    let cancelled = false;
    listInvoices(lookedUp)
      .then((all) => {
        if (cancelled) return;
        setInvoices(all.filter((i) => OPEN.has(i.status ?? '') && (i.dueDate ?? '') <= todayIso()));
        setInvoiceError(all.length === 0 ? `No policy ${lookedUp} is billed here.` : null);
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
  }, [lookedUp]);

  // The policy's loans that can still take a repayment (money movement step 1), read once a loan deposit is asked for.
  const [loans, setLoans] = useState<LoanView[] | null>(null);
  const [loanError, setLoanError] = useState<string | null>(null);
  useEffect(() => {
    if (!lookedUp || paysFor !== 'LOAN') return;
    let cancelled = false;
    listLoans(lookedUp)
      .then((all) => {
        if (cancelled) return;
        const repayable = all.filter((l) => REPAYABLE.has(l.status ?? ''));
        setLoans(repayable);
        setLoanError(repayable.length === 0 ? `No loan on ${lookedUp} has anything to repay.` : null);
        if (repayable.length > 0 && !repayable.some((l) => l.loanId === prefill.loanId)) {
          setValue('loanId', repayable[0]!.loanId ?? '', { shouldDirty: true });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoans(null);
          setLoanError(`No policy ${lookedUp} could be read.`);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [lookedUp, paysFor, prefill.loanId, setValue]);

  const loan = paysFor === 'LOAN' ? (loans?.find((l) => l.loanId === loanId) ?? null) : null;
  // The loan's balance, which caps a repayment, or what the policy's premiums owe today, which caps nothing.
  const owedNow = loan ? (loan.outstandingBalance ?? null) : invoices ? totalOwed(invoices) : null;
  const from = params.get('from');
  const origin = receiptOrigin(from, prefill.policyNumber || null);

  async function onSubmit(values: ReceiptFormValues) {
    const saved = await record(toApiRequest(values, owedNow?.currencyCode ?? 'TZS'));
    if (saved) navigate(`/staff/receipts/${saved.receiptId}${from ? `?from=${encodeURIComponent(from)}` : ''}`);
  }

  return (
    <>
      <PageHeader
        breadcrumb={origin.crumbs}
        title="Record deposit"
        description="Premium or a loan repayment paid by cash, bank deposit, cheque, Lipa number or TANQR. It counts once a second finance person confirms it."
      />
      <FormLayout formId="new-receipt-form" outlineLabel="Deposit sections">
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <UnsavedGuard when={isDirty && !isSubmitting} what="This deposit" />

          <div data-outline="What it pays" className="space-y-4">
            <div className="flex flex-wrap items-end gap-2">
              <FormField label="Policy number" error={errors.policyNumber?.message}>
                <Input
                  className="w-48 font-mono"
                  placeholder="POL-XXXXXXXX"
                  {...register('policyNumber', {
                    setValueAs: (v: string) => v.toUpperCase(),
                    // Looked up as soon as the number is in, so what it owes shows without another click.
                    onBlur: (e: FocusEvent<HTMLInputElement>) => {
                      const typed = e.target.value.trim().toUpperCase();
                      if (typed) setLookedUp(typed);
                    },
                  })}
                />
              </FormField>
              <Button type="button" variant="outline" onClick={() => setLookedUp(policyNumber.trim().toUpperCase())}>
                Look up policy
              </Button>
            </div>
            <FormField label="For" error={errors.paysFor?.message}>
              <Select {...register('paysFor')}>
                <option value="PREMIUM">Premium</option>
                <option value="LOAN">Loan repayment</option>
              </Select>
            </FormField>
            {paysFor === 'LOAN' && loanError && <p className="text-sm text-status-warning-fg">{loanError}</p>}
            {paysFor === 'LOAN' && loans && loans.length > 0 && (
              <FormField label="Loan it repays" error={errors.loanId?.message}>
                <Select {...register('loanId')}>
                  {loans.map((l) => (
                    <option key={l.loanId} value={l.loanId}>
                      {(l.loanId ?? '').slice(0, 8)} · {formatMoney(l.outstandingBalance)} owed
                    </option>
                  ))}
                </Select>
              </FormField>
            )}
            {paysFor === 'PREMIUM' && invoiceError && <p className="text-sm text-status-warning-fg">{invoiceError}</p>}
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
                label={`Amount (${owedNow?.currencyCode ?? 'TZS'})`}
                hint={owedNow
                  ? loan
                    ? `At most ${formatMoney(owedNow)}, what the loan owes.`
                    : `Owed now ${formatMoney(owedNow)}. It goes onto the policy's balance, which pays what is due oldest first; the rest pays the next premiums.`
                  : undefined}
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
            <InlineError lead="Could not record the deposit" error={recording.error} />
          )}

          <div className="flex gap-2">
            <Button type="submit" variant="primary" pending={recording.status === 'loading'}>
              Record deposit
            </Button>
            <Button asChild variant="ghost">
              <Link to={origin.back}>Cancel</Link>
            </Button>
          </div>
        </form>
      </FormLayout>
    </>
  );
}
