import { Smartphone } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listInvoices, requestPaymentForInvoice } from '@/api/policies';
import { getCustomerDashboard, getMe } from '@/api/portal';
import type { InvoiceView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { isMobileNumber, owed, payableNow } from './customerPayments';
import { money } from './customerText';

interface PolicyDue {
  policyNumber: string;
  productName: string | null;
  invoices: InvoiceView[];
}

const LATE_TEXT: Record<string, string> = {
  OVERDUE: 'Overdue', IN_GRACE: 'In the grace period — pay now to keep your cover', PARTIALLY_PAID: 'Part paid',
};

/**
 * Pay a premium (2026-10-08, the customer portal design step 6; PRD §26-28). Each policy's late premiums and the next
 * one coming up, paid by mobile money: the customer gives the number, the network asks them to approve on their phone,
 * and the premium shows as paid once the money arrives. Receipts are in Documents.
 */
export function CustomerPayPage() {
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
    <PageHeader title="Pay a premium" description={<>Pay by mobile money. Your receipts are in <Link className="underline" to="/customers/documents">Documents</Link>.</>} />
  );
  if (error) return <>{header}<div className="px-4 pt-4 sm:px-6"><ErrorPanel error={error} onRetry={refresh} /></div></>;
  if (!due || phone === null) return <LoadingBlock label="Loading what is due" />;

  return (
    <>
      {header}
      <div className="space-y-4 px-4 pb-8 sm:px-6">
        {due.length === 0 ? (
          <EmptyState title="Nothing to pay" description="Your premiums are paid up. The next one appears here when it is due." />
        ) : (
          due.map((p) => (
            <Panel key={p.policyNumber} title={p.productName ?? p.policyNumber} subtitle={p.policyNumber}>
              <ul className="divide-y divide-border">
                {p.invoices.map((i) => <InvoiceRow key={i.invoiceId} invoice={i} defaultPhone={phone} onRefresh={refresh} />)}
              </ul>
            </Panel>
          ))
        )}
      </div>
    </>
  );
}

function InvoiceRow({ invoice, defaultPhone, onRefresh }: { invoice: InvoiceView; defaultPhone: string; onRefresh: () => void }) {
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
    <li className="space-y-2 px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm">
          <span className="font-medium tabular-nums">{amount ? money(amount.amount, amount.currency) : '—'}</span>
          <span className="block text-xs text-muted-foreground">
            Due {formatDate(invoice.dueDate)}{LATE_TEXT[invoice.status ?? ''] ? ` · ${LATE_TEXT[invoice.status ?? '']}` : ''}
          </span>
        </span>
        {!paying && !sent && <Button variant="primary" size="sm" onClick={() => setPaying(true)}>Pay</Button>}
      </div>
      {sent ? (
        <div className="rounded-md bg-control px-3 py-2 text-sm" role="status">
          <Smartphone className="mr-1 inline size-4" aria-hidden />
          Check your phone and approve the payment of {amount ? money(amount.amount, amount.currency) : 'the premium'}.
          It shows as paid here once it goes through.
          <Button variant="ghost" size="sm" className="ml-2" onClick={onRefresh}>Refresh</Button>
        </div>
      ) : paying && (
        <div className="flex flex-wrap items-end gap-2">
          <FormField label="Mobile money number" hint="M-Pesa, Tigo Pesa, Airtel Money or Halopesa" className="min-w-0 flex-1">
            <Input inputMode="tel" value={number}
              onChange={(e) => { setNumber(e.target.value); setAttempt(null); }} placeholder="0754 123 456" />
          </FormField>
          <Button variant="primary" disabled={!isMobileNumber(number)} pending={sending} onClick={() => void pay()}>
            Send payment request
          </Button>
          <Button variant="ghost" disabled={sending} onClick={() => setPaying(false)}>Cancel</Button>
        </div>
      )}
      {error && <InlineError error={error} lead="The payment was not requested" />}
    </li>
  );
}
