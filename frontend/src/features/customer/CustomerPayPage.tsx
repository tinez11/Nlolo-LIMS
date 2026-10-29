import { Smartphone } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listInvoices, requestPaymentForInvoice } from '@/api/policies';
import { getCustomerDashboard, getMe } from '@/api/portal';
import type { InvoiceView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { isMobileNumber, owed, payableNow } from './customerPayments';
import { money } from './customerText';
import { useWords } from './words';

interface PolicyDue {
  policyNumber: string;
  productName: string | null;
  invoices: InvoiceView[];
}

/**
 * Pay a premium (2026-10-08, the customer portal design step 6; PRD §26-28). Each policy's late premiums and the next
 * one coming up, paid by mobile money: the customer gives the number, the network asks them to approve on their phone,
 * and the premium shows as paid once the money arrives. Receipts are in Documents.
 */
export function CustomerPayPage() {
  const w = useWords().portal.pay;
  const [due, setDue] = useState<PolicyDue[] | null>(null);
  const [phone, setPhone] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    Promise.all([getMe(), getCustomerDashboard()])
      .then(async ([me, dashboard]) => {
        const rows = await Promise.all(dashboard.policies.map(async (p) => ({
          policyNumber: p.policyNumber, productName: p.productName, invoices: payableNow(await listInvoices(p.policyNumber)),
        })));
        return { me, rows };
      })
      .then(
        ({ me, rows }) => {
          if (!live) return;
          setPhone(me.phoneNumber ?? '');
          setDue(rows.filter((r) => r.invoices.length > 0));
          setError(null);
        },
        (e: unknown) => { if (live) setError(toApiError(e)); },
      );
    return () => { live = false; };
  }, [reload]);

  const refresh = () => setReload((n) => n + 1);
  const header = (
    <PageHeader title={w.title} description={<Link className="underline" to="/customers/documents">{w.lead}</Link>} />
  );
  if (error) return <>{header}<div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={refresh} /></div></>;
  if (!due || phone === null) return <LoadingBlock label="Loading what is due" />;

  return (
    <>
      {header}
      <div className="w-full max-w-3xl space-y-4 px-4 pb-10 text-base sm:px-6">
        {due.length === 0 ? (
          <EmptyState title={w.nothing} description={w.nothingLead} />
        ) : (
          due.map((p) => (
            <section key={p.policyNumber} className="rounded-2xl border border-border bg-surface shadow-card">
              <header className="px-4 pt-4 sm:px-5">
                <h2 className="text-title">{p.productName ?? p.policyNumber}</h2>
                <p className="font-mono text-xs text-muted-foreground">{p.policyNumber}</p>
              </header>
              <ul className="divide-y divide-border">
                {p.invoices.map((i) => <InvoiceRow key={i.invoiceId} invoice={i} defaultPhone={phone} onRefresh={refresh} />)}
              </ul>
            </section>
          ))
        )}
      </div>
    </>
  );
}

function InvoiceRow({ invoice, defaultPhone, onRefresh }: { invoice: InvoiceView; defaultPhone: string; onRefresh: () => void }) {
  const w = useWords().portal.pay;
  const [paying, setPaying] = useState(false);
  const [number, setNumber] = useState(defaultPhone);
  const [attempt, setAttempt] = useState<MutationAttempt | null>(null);
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const amount = owed(invoice);

  async function pay() {
    // One attempt per number: a retry after a timeout is the same request, a new number is a new one.
    const current = attempt ?? startMutation();
    setAttempt(current);
    setSending(true);
    setError(null);
    try {
      await requestPaymentForInvoice(invoice.invoiceId ?? '', { payerRef: number.replace(/\s+/g, '') }, current);
      setSent(true);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setSending(false);
    }
  }

  return (
    <li className="space-y-3 px-4 py-4 sm:px-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span>
          <span className="block text-display tabular-nums">{amount ? money(amount.amount, amount.currency) : '—'}</span>
          <span className="block text-sm text-muted-foreground">{w.due(formatDate(invoice.dueDate))}</span>
          {w.late[invoice.status ?? ''] && (
            <span className="mt-1 inline-block rounded-lg bg-status-warning-bg px-2.5 py-0.5 text-sm font-semibold text-status-warning-fg">
              {w.late[invoice.status ?? '']}
            </span>
          )}
        </span>
        {!paying && !sent && <Button variant="primary" className="h-12 w-full px-8 text-base sm:w-auto" onClick={() => setPaying(true)}>{w.pay}</Button>}
      </div>
      {sent ? (
        <div className="flex flex-wrap items-center gap-3 rounded-xl bg-selected px-4 py-3" role="status">
          <Smartphone className="size-6 shrink-0 text-accent" aria-hidden />
          <span className="min-w-0 flex-1">{w.checkPhone(amount ? money(amount.amount, amount.currency) : '')}</span>
          <Button variant="outline" className="h-11" onClick={onRefresh}>{w.refresh}</Button>
        </div>
      ) : paying && (
        <div className="flex flex-wrap items-end gap-2">
          <FormField label={w.number} hint={w.numberHint} className="min-w-0 flex-1">
            <Input inputMode="tel" className="h-12 text-base" value={number}
              onChange={(e) => { setNumber(e.target.value); setAttempt(null); }} placeholder="0754 123 456" />
          </FormField>
          <Button variant="primary" className="h-12 text-base" disabled={!isMobileNumber(number)} pending={sending} onClick={() => void pay()}>
            {w.send}
          </Button>
          <Button variant="ghost" className="h-12" disabled={sending} onClick={() => setPaying(false)}>{w.cancel}</Button>
        </div>
      )}
      {error && <InlineError error={error} lead={w.notRequested} />}
    </li>
  );
}
