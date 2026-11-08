import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useMemo, useState, type FocusEvent } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { getAccount, recordReceivedTopUp, requestTopUp } from '@/api/accumulation';
import { getPolicy, listInvoices, listLoans, requestLoanPayment, requestPaymentForInvoice } from '@/api/policies';
import {
  getUnitLinkedTerms, recordReceivedTopUp as recordReceivedFundsTopUp, requestTopUp as requestFundsTopUp,
} from '@/api/unitlinked';
import { ownSplit, ownSplitProblem } from '@/features/unitlinked/u2Forms';
import type { AccountView, InvoiceView, LoanView } from '@/api/types';
import type { Realm } from '@/auth/realms';
import { can } from '@/auth/access';
import { DatePicker } from '@/components/DatePicker';
import { FormField, RequiredNote } from '@/components/FormField';
import { FormLayout } from '@/components/FormLayout';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Receipt, type ReceiptLine } from '@/components/Receipt';
import { UnsavedGuard } from '@/components/UnsavedGuard';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { todayIso } from '@/lib/dates';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { startMutation } from '@/lib/idempotency';
import { compareAmounts, formatMoney } from '@/lib/money';
import { keepsAccountValue } from '@/features/policies/policyValue';
import { useReceiptStore } from '@/store/receiptStore';
import {
  blankReceiptForm,
  methodOf,
  RECEIPT_METHODS,
  receiptFormSchema,
  receiptOrigin,
  REQUESTED_MOBILE_MONEY,
  toApiRequest,
  type PaysFor,
  type ReceiptFormValues,
} from './receiptForm';

/** Invoices still owed: not paid, not waived. */
const OPEN = new Set(['DUE', 'PARTIALLY_PAID', 'IN_GRACE', 'OVERDUE']);

/** Loans a deposit can still repay -- the two statuses the server accepts a repayment on. */
const REPAYABLE = new Set(['DISBURSED', 'REPAYING']);

const FOR_LABEL: Record<PaysFor, string> = {
  PREMIUM: 'Premium', LOAN: 'Loan repayment', SAVINGS: 'Savings', FUNDS: 'Funds (top-up)',
};

/** A savings account's or a unit-linked policy's own money, rather than a premium or a loan. */
const isTopUp = (paysFor: PaysFor) => paysFor === 'SAVINGS' || paysFor === 'FUNDS';

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

/** Who may take a deposit which way (2026-11-07): finance records money received; a phone may be asked by servicing. */
function mayAsk(paysFor: PaysFor, agent = false) {
  // An agent asks a phone for a premium, and only that (2026-11-07): the invoice request is the one its endpoint lets
  // an agent make.
  if (agent) return paysFor === 'PREMIUM';
  return isTopUp(paysFor)
    ? can('accounts.transact') || can('collections.manage')
    : can('policies.service') || can('collections.manage');
}

/** What was sent, kept on screen in place of the form (the Receipt pattern). */
type Sent = { heading: string; lines: ReceiptLine[]; note: string };

/**
 * Deposit (2026-11-07, the user: one place, every method, and the method decides what happens behind).
 *
 * For: Premium, Loan repayment or Savings, offered as the policy has them. Mobile money asks the payer's phone and
 * counts when they approve; any other method records money already received, which counts when a second finance
 * person confirms it. A premium or loan deposit recorded by hand opens its own page; anything else closes into a
 * receipt here. Opened bare from Deposits, from a policy (Balance, Account or Loans), or from an agent's cash receipt.
 */
export function NewReceiptPage({ realm = 'staff' }: { realm?: Realm } = {}) {
  // The agent console's Deposit (2026-11-07): the same page, asking a phone for a premium only.
  const agent = realm === 'agents';
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const record = useReceiptStore((s) => s.record);
  const recording = useReceiptStore((s) => s.recording);
  const send = useReceiptStore((s) => s.send);
  const sending = useReceiptStore((s) => s.sending);
  const resetSending = useReceiptStore((s) => s.resetSending);
  const mayRecord = !agent && can('collections.manage');
  const [sent, setSent] = useState<Sent | null>(null);
  // One attempt per page visit, reused across retries so a double click is one request.
  const [attempt] = useState(() => startMutation());

  useEffect(() => {
    resetSending();
  }, [resetSending]);

  const prefill = useMemo(() => {
    const askedFor = params.get('for');
    const paysFor: PaysFor = !agent && (askedFor === 'LOAN' || askedFor === 'SAVINGS' || askedFor === 'FUNDS')
      ? askedFor : 'PREMIUM';
    const named = params.get('method');
    const method = named === 'MOBILE_MONEY' || RECEIPT_METHODS.some((m) => m.value === named)
      ? (named as ReceiptFormValues['method'])
      // Mobile money first where it may be asked; finance alone opens on a received method.
      : mayAsk(paysFor, agent) ? 'MOBILE_MONEY' : 'BANK_DEPOSIT';
    return blankReceiptForm({
      policyNumber: params.get('policy') ?? '',
      paysFor,
      loanId: params.get('loan') ?? '',
      amount: params.get('amount') ?? '',
      reference: params.get('reference') ?? '',
      fieldReceiptId: params.get('fieldReceipt') ?? '',
      method,
    });
  }, [params, agent]);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ReceiptFormValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(receiptFormSchema), defaultValues: prefill });

  const policyNumber = useWatch({ control, name: 'policyNumber' });
  const method = useWatch({ control, name: 'method' });
  const paysFor = useWatch({ control, name: 'paysFor' });
  const loanId = useWatch({ control, name: 'loanId' });
  const mobile = method === 'MOBILE_MONEY';
  const chosenMethod = methodOf(method);
  const askable = mayAsk(paysFor, agent);

  // What the policy has, read once its number is in: its open invoices (what a premium owes now, and the one a
  // mobile-money request asks for), its loans that can take a repayment, and its savings account.
  const [lookedUp, setLookedUp] = useState(prefill.policyNumber);
  const [invoices, setInvoices] = useState<InvoiceView[] | null>(null);
  const [invoiceError, setInvoiceError] = useState<string | null>(null);
  const [loans, setLoans] = useState<LoanView[] | null>(null);
  const [account, setAccount] = useState<AccountView | null>(null);
  // A unit-linked policy takes a top-up into its funds (2026-11-07).
  const [unitLinked, setUnitLinked] = useState<boolean | null>(null);
  // A savings-type policy (2026-11-08): its premium deposit is capped at the premium; more is a savings deposit.
  const [keepsAccount, setKeepsAccount] = useState(false);
  // Its funds, for a top-up's own split (2026-11-08), and the split typed in -- empty is the policy's split.
  const [fundCodes, setFundCodes] = useState<string[]>([]);
  const [split, setSplit] = useState<Record<string, string>>({});
  const [splitProblem, setSplitProblem] = useState<string | null>(null);
  useEffect(() => {
    if (!lookedUp) return;
    let cancelled = false;
    listInvoices(lookedUp)
      .then((all) => {
        if (cancelled) return;
        setInvoices(all.filter((i) => OPEN.has(i.status ?? '')));
        setInvoiceError(all.length === 0 ? `No policy ${lookedUp} is billed here.` : null);
      })
      .catch(() => {
        if (!cancelled) {
          setInvoices(null);
          setInvoiceError(`No policy ${lookedUp} could be read.`);
        }
      });
    // An agent takes a premium only: no loans or accounts to read.
    if (agent) {
      return () => {
        cancelled = true;
      };
    }
    listLoans(lookedUp)
      .then((all) => {
        if (!cancelled) setLoans(all.filter((l) => REPAYABLE.has(l.status ?? '')));
      })
      .catch(() => {
        if (!cancelled) setLoans([]);
      });
    getAccount(lookedUp)
      .then((a) => {
        if (!cancelled) setAccount(a && a.status === 'OPEN' ? a : null);
      })
      .catch(() => {
        if (!cancelled) setAccount(null);
      });
    getPolicy(lookedUp)
      .then(async (p) => {
        if (cancelled) return;
        const isUnitLinked = p.productCategory === 'UNIT_LINKED';
        setUnitLinked(isUnitLinked);
        setKeepsAccount(keepsAccountValue(p));
        if (isUnitLinked && p.productId && p.productVersionId) {
          const terms = await getUnitLinkedTerms(p.productId, p.productVersionId);
          if (!cancelled) setFundCodes(terms?.fundCodes ?? []);
        }
      })
      .catch(() => {
        if (!cancelled) setUnitLinked(false);
      });
    return () => {
      cancelled = true;
    };
  }, [lookedUp, agent]);

  // A loan chosen by the link stays chosen; otherwise the first loan that can take a repayment.
  useEffect(() => {
    if (paysFor !== 'LOAN' || !loans || loans.length === 0) return;
    if (!loans.some((l) => l.loanId === loanId)) setValue('loanId', loans[0]!.loanId ?? '', { shouldDirty: true });
  }, [paysFor, loans, loanId, setValue]);

  // A person who may not ask a phone, or may not record by hand, is moved to a way they may use.
  useEffect(() => {
    if (mobile && !askable && mayRecord) setValue('method', 'BANK_DEPOSIT');
    if (!mobile && !mayRecord && askable) setValue('method', 'MOBILE_MONEY');
  }, [mobile, askable, mayRecord, setValue]);

  // For offers what the policy has; the one the link named stays offered while the policy is read.
  const forOptions: PaysFor[] = ['PREMIUM'];
  if ((loans && loans.length > 0) || prefill.paysFor === 'LOAN') forOptions.push('LOAN');
  if (account || prefill.paysFor === 'SAVINGS') forOptions.push('SAVINGS');
  if (unitLinked || prefill.paysFor === 'FUNDS') forOptions.push('FUNDS');

  const dueNow = invoices?.filter((i) => (i.dueDate ?? '') <= todayIso()) ?? null;
  // The oldest premium still owed: what a mobile-money request for a premium asks for.
  const oldestOpen = invoices && invoices.length > 0
    ? [...invoices].sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''))[0]!
    : null;
  const loan = paysFor === 'LOAN' ? (loans?.find((l) => l.loanId === loanId) ?? null) : null;
  const currency = loan?.outstandingBalance?.currencyCode ?? account?.balance.currencyCode ?? oldestOpen?.amount?.currencyCode ?? 'TZS';
  const from = params.get('from');
  const origin = agent ? agentOrigin(prefill.policyNumber) : receiptOrigin(from, prefill.policyNumber || null);
  const back = agent ? origin.back : paysFor === 'LOAN' && lookedUp
    ? `/staff/policies/${encodeURIComponent(lookedUp)}?tab=loans`
    : paysFor === 'SAVINGS' && lookedUp
      ? `/staff/policies/${encodeURIComponent(lookedUp)}?tab=account`
      : paysFor === 'FUNDS' && lookedUp
        ? `/staff/policies/${encodeURIComponent(lookedUp)}?tab=units`
        : origin.back;

  const forProblem =
    paysFor === 'LOAN' && loans && loans.length === 0 ? `No loan on ${lookedUp} has anything to repay.`
      : paysFor === 'SAVINGS' && lookedUp && invoices !== null && !account ? `${lookedUp} has no open savings account.`
      : paysFor === 'FUNDS' && lookedUp && unitLinked === false ? `${lookedUp} is not a unit-linked policy.`
        : paysFor === 'PREMIUM' && mobile && invoices !== null && !oldestOpen
          ? 'No premium is owed now, so there is nothing to ask the phone for.'
          : paysFor === 'PREMIUM' ? invoiceError : null;

  // A savings-type policy's premium deposit pays the oldest premium and no more (2026-11-08): the server refuses more.
  const premiumCap = paysFor === 'PREMIUM' && keepsAccount && oldestOpen ? owed(oldestOpen) : null;
  const beyondCap = account ? 'More than that is a savings deposit: choose For: Savings.'
    : 'More than that is a savings deposit, For: Savings, once the policy\'s savings account is open.';

  const amountHint = loan
    ? `At most ${formatMoney(loan.outstandingBalance)}, what the loan owes.`
    : premiumCap
      ? `At most ${formatMoney(premiumCap)}, what the premium due ${oldestOpen?.dueDate ?? ''} owes. ${beyondCap}`
    : paysFor === 'SAVINGS'
      ? 'It goes into the savings account, less any charge the product takes on a deposit.'
      : paysFor === 'FUNDS'
        ? 'It buys units by the policy\'s own split, at the first price after the money is confirmed.'
        : dueNow && totalOwed(dueNow)
        ? `Owed now ${formatMoney(totalOwed(dueNow))}. It goes onto the policy's balance, which pays what is due oldest first; the rest pays the next premiums.`
        : undefined;

  async function onSubmit(values: ReceiptFormValues) {
    const policy = values.policyNumber.trim().toUpperCase();
    const amount = Number(values.amount || '0').toFixed(2);
    const payer = values.payerRef.trim();
    // A fund top-up's own split, checked as every split is; nothing typed is the policy's split in force.
    const shares = fundCodes.map((fundCode) => ({ fundCode, percent: split[fundCode] ?? '' }));
    if (values.paysFor === 'FUNDS') {
      const problem = ownSplitProblem(shares);
      setSplitProblem(problem);
      if (problem) return;
    }

    if (values.method !== 'MOBILE_MONEY' && premiumCap && values.paysFor === 'PREMIUM'
        && compareAmounts(amount, premiumCap.amount ?? '0') > 0) {
      setError('amount', { message: `More than the premium owes (${formatMoney(premiumCap)}). Record the premium; ${beyondCap.charAt(0).toLowerCase()}${beyondCap.slice(1)}` });
      return;
    }

    if (values.method !== 'MOBILE_MONEY' && !isTopUp(values.paysFor)) {
      const saved = await record(toApiRequest(values, currency));
      if (saved) navigate(`/staff/receipts/${saved.receiptId}${from ? `?from=${encodeURIComponent(from)}` : ''}`);
      return;
    }

    if (values.method !== 'MOBILE_MONEY') {
      const received = {
        amount, method: values.method as Exclude<ReceiptFormValues['method'], 'MOBILE_MONEY'>,
        reference: values.reference.trim(), receivedOn: values.receivedOn, ...(payer ? { payerRef: payer } : {}),
      };
      const funds = values.paysFor === 'FUNDS';
      const ok = await send(() => funds
        ? recordReceivedFundsTopUp(policy, { ...received, split: ownSplit(shares) }, attempt)
        : recordReceivedTopUp(policy, received, attempt));
      if (ok) {
        setSent({
          heading: funds ? 'Fund top-up recorded' : 'Savings deposit recorded',
          lines: [
            { label: 'Policy', value: policy },
            { label: 'Amount', value: formatMoney({ amount, currencyCode: currency }) },
            { label: 'How it came in', value: `${methodOf(values.method)?.label ?? values.method}, ${values.reference.trim()}` },
          ],
          note: funds
            ? 'It buys units once a second finance person confirms it under Approvals.'
            : 'It goes into the savings account once a second finance person confirms it under Approvals.',
        });
      }
      return;
    }

    let ok = false;
    let asked = { amount, currencyCode: currency };
    if (values.paysFor === 'PREMIUM' && oldestOpen?.invoiceId) {
      asked = owed(oldestOpen);
      ok = await send(() => requestPaymentForInvoice(oldestOpen.invoiceId!, { payerRef: payer }, attempt));
    } else if (values.paysFor === 'LOAN') {
      ok = await send(() => requestLoanPayment(values.loanId, { payerRef: payer, amount: { amount, currencyCode: currency } }, attempt));
    } else if (values.paysFor === 'SAVINGS') {
      ok = await send(() => requestTopUp(policy, { amount, payerRef: payer }, attempt));
    } else if (values.paysFor === 'FUNDS') {
      // Its own split if one was typed; an empty one is the policy's split in force.
      ok = await send(() => requestFundsTopUp(policy, { amount, payerRef: payer, split: ownSplit(shares) }, attempt));
    }
    if (ok) {
      setSent({
        heading: 'Mobile-money request sent',
        lines: [
          { label: 'Policy', value: policy },
          { label: 'For', value: FOR_LABEL[values.paysFor] },
          { label: 'Asked of', value: payer },
          { label: 'Amount', value: formatMoney(asked) },
        ],
        note: 'The payer approves it on their phone. It counts once the money is confirmed, and waits on the policy until then.',
      });
    }
  }

  const error = recording.status === 'error' ? recording.error : sending.status === 'error' ? sending.error : null;
  const busy = recording.status === 'loading' || sending.status === 'loading';

  return (
    <>
      <PageHeader
        breadcrumb={origin.crumbs}
        title="Deposit"
        description={mobile
          ? 'A request goes to the payer’s phone. It counts once they approve it.'
          : 'Money already received. It counts once a second finance person confirms it.'}
      />
      <FormLayout formId="new-receipt-form" outlineLabel="Deposit sections">
        {sent ? (
          <Receipt
            heading={sent.heading}
            lines={sent.lines}
            note={sent.note}
            onward={
              <Button asChild size="sm" variant="outline">
                <Link to={back}>Back to {lookedUp || 'deposits'}</Link>
              </Button>
            }
          />
        ) : (
        <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
          <RequiredNote />
          <UnsavedGuard when={isDirty && !isSubmitting && !sent} what="This deposit" />

          <div data-outline="What it pays" className="space-y-4">
            <div className="flex flex-wrap items-end gap-2">
              <FormField required label="Policy number" error={errors.policyNumber?.message}>
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
            <FormField required label="For" error={errors.paysFor?.message}>
              <Select {...register('paysFor')}>
                {forOptions.map((f) => (
                  <option key={f} value={f}>
                    {FOR_LABEL[f]}
                  </option>
                ))}
              </Select>
            </FormField>
            {paysFor === 'LOAN' && loans && loans.length > 0 && (
              <FormField required label="Loan it repays" error={errors.loanId?.message}>
                <Select {...register('loanId')}>
                  {loans.map((l) => (
                    <option key={l.loanId} value={l.loanId}>
                      Taken {formatMoney(l.principalAmount)} · {formatMoney(l.outstandingBalance)} owed
                    </option>
                  ))}
                </Select>
              </FormField>
            )}
            {forProblem && <p className="text-sm text-status-warning-fg">{forProblem}</p>}
          </div>

          <div data-outline="How it is paid" className="space-y-4">
            <FormField required label="How is it being paid?" hint={mobile ? undefined : chosenMethod?.hint}
              error={errors.method?.message}>
              <Select {...register('method')}>
                {askable && <option value="MOBILE_MONEY">{REQUESTED_MOBILE_MONEY.label} — ask the payer’s phone</option>}
                {mayRecord && RECEIPT_METHODS.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
              </Select>
            </FormField>
            {!mayRecord && (
              <p className="text-xs text-muted-foreground">
                Money already received — cash, a bank deposit, a cheque — is recorded by finance.
              </p>
            )}

            {mobile ? (
              <>
                <FormField required label="Phone number" hint="The mobile-money number the request goes to."
                  error={errors.payerRef?.message}>
                  <Input inputMode="tel" {...register('payerRef')} />
                </FormField>
                {paysFor === 'PREMIUM' ? (
                  oldestOpen && (
                    <p className="text-sm">
                      Asks for <strong>{formatMoney(owed(oldestOpen))}</strong>, what the premium due{' '}
                      {oldestOpen.dueDate ?? ''} still owes.
                    </p>
                  )
                ) : (
                  <FormField required label={`Amount (${currency})`} hint={amountHint} error={errors.amount?.message}>
                    <Input inputMode="decimal" {...register('amount')} />
                  </FormField>
                )}
              </>
            ) : (
              <>
                <FormField required label={chosenMethod?.reference ?? 'Reference'} error={errors.reference?.message}>
                  <Input className="font-mono" {...register('reference')} />
                </FormField>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <FormField required label={`Amount (${currency})`} hint={amountHint} error={errors.amount?.message}>
                    <Input inputMode="decimal" {...register('amount')} />
                  </FormField>
                  <FormField required label="Received on" error={errors.receivedOn?.message}>
                    <Controller
                      control={control}
                      name="receivedOn"
                      render={({ field }) => <DatePicker value={field.value} onChange={(iso) => field.onChange(iso ?? '')} />}
                    />
                  </FormField>
                </div>
                <FormField label="Paid by" hint="The payer's name or number, as the slip shows it." error={errors.payerRef?.message}>
                  <Input {...register('payerRef')} />
                </FormField>
                {!isTopUp(paysFor) && (
                  <FormField label="Note" error={errors.note?.message}>
                    <Input {...register('note')} />
                  </FormField>
                )}
              </>
            )}

            {/* A fund top-up may have its own split (2026-11-08); left empty, the policy's split in force applies. */}
            {paysFor === 'FUNDS' && fundCodes.length > 0 && (
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">Its own split (optional; leave empty for the policy&apos;s split):</p>
                <div className="flex flex-wrap gap-3">
                  {fundCodes.map((code) => (
                    <FormField key={code} label={`Top-up into ${code} (%)`}>
                      <Input inputMode="numeric" className="w-20" value={split[code] ?? ''}
                        onChange={(e) => setSplit((s) => ({ ...s, [code]: e.target.value.trim() }))} />
                    </FormField>
                  ))}
                </div>
                {splitProblem && <p role="alert" className="text-xs text-status-danger-fg">{splitProblem}</p>}
              </div>
            )}
          </div>

          {error && <InlineError lead={mobile ? 'Could not send the request' : 'Could not record the deposit'} error={error} />}

          <div className="flex gap-2">
            <Button type="submit" variant="primary" pending={busy}
              disabled={paysFor === 'PREMIUM' && mobile && !oldestOpen}>
              {mobile ? 'Send request' : 'Record deposit'}
            </Button>
            <Button asChild variant="ghost">
              <Link to={back}>Cancel</Link>
            </Button>
          </div>
        </form>
        )}
      </FormLayout>
    </>
  );
}

/** The agent console's way back: its own policy page, never the staff console (2026-11-07). */
function agentOrigin(policyNumber: string) {
  if (!policyNumber) return { crumbs: [{ label: 'Policies', to: '/agents/policies' }], back: '/agents/policies' };
  const policy = `/agents/policies/${encodeURIComponent(policyNumber)}?tab=billing`;
  return { crumbs: [{ label: 'Policies', to: '/agents/policies' }, { label: policyNumber, to: policy }], back: policy };
}
