import { useEffect, useState } from 'react';
import { getSchemeMember, listInvoices } from '@/api/policies';
import type { InvoiceView, PolicyMemberView } from '@/api/types';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';

/**
 * The invoice for the monthly file a credit-life borrower joined on (2026-11-02).
 *
 * A claim on a borrower is paid only once the lender has paid for the file that put them on cover (the user's choice
 * "B"): it may be assessed and approved meanwhile, but finance holds the transfer. Null when the borrower came in on
 * no file (the scheme's opening schedule) or the invoice cannot be found -- nothing to hold for.
 */
export async function borrowerFileInvoice(policyNumber: string, member: PolicyMemberView | null): Promise<InvoiceView | null> {
  const fileId = member?.arrivedOnSubmissionId;
  if (!fileId) return null;
  const invoices = await listInvoices(policyNumber).catch(() => [] as InvoiceView[]);
  return invoices.find((invoice) => invoice.enrolmentSubmissionId === fileId) ?? null;
}

/** Whether that invoice is still owed: anything but paid or waived. */
export function fileUnpaid(invoice: InvoiceView | null): boolean {
  return invoice != null && invoice.status !== 'PAID' && invoice.status !== 'WAIVED';
}

/** "TZS 2,503,200.00 due Dec 2, 2026": what the lender still owes on the file, in one phrase. */
export function fileOwed(invoice: InvoiceView): string {
  const owed = invoice.balanceDue ?? invoice.amount;
  return `${owed ? formatMoney(owed) : 'its premium'} due ${formatDate(invoice.dueDate)}`;
}

/** The file invoice for one borrower, for a page that knows only the claim's policy and member. */
export function useBorrowerFileInvoice(policyNumber: string, policyMemberId: string | null | undefined, enabled: boolean) {
  const [invoice, setInvoice] = useState<InvoiceView | null>(null);
  useEffect(() => {
    if (!enabled || !policyMemberId) return undefined;
    let cancelled = false;
    void (async () => {
      const member = await getSchemeMember(policyNumber, policyMemberId).catch(() => null);
      const found = await borrowerFileInvoice(policyNumber, member);
      if (!cancelled) setInvoice(found);
    })();
    return () => {
      cancelled = true;
    };
  }, [policyNumber, policyMemberId, enabled]);
  return invoice;
}
