'use client';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import { MoneyText } from '@/components/money';
import { useSubmitGuard } from '@/hooks/use-submit-guard';

type Money = { amount: string; currencyCode: string };
export type InvoiceStatus = 'DUE' | 'PARTIALLY_PAID' | 'PAID' | 'IN_GRACE' | 'OVERDUE' | 'WAIVED';
export type InvoiceRow = {
  invoiceId: string;
  policyNumber: string;
  dueDate: string;
  amount: Money;
  status: InvoiceStatus;
  gracePeriodEndsAt: string | null;
  dunningLevel: number | null;
};

/** Statuses that still represent money owed -- a fully PAID or staff-WAIVED invoice cannot be paid again. */
const PAYABLE_STATUSES: ReadonlySet<InvoiceStatus> = new Set(['DUE', 'PARTIALLY_PAID', 'IN_GRACE', 'OVERDUE']);

function PayButton({ invoiceId, onPay }: { invoiceId: string; onPay: (invoiceId: string) => Promise<void> }) {
  // Layer 1 only (spec §6): the backend's payment-request registers a real DB-backed idempotency
  // claim, so this guard exists purely to stop a same-tick double-click from firing onPay twice --
  // not to survive a refresh or a second tab, which is what Layer 2 (Redis) would be for.
  const { submit, isSubmitting } = useSubmitGuard(() => onPay(invoiceId));
  return (
    <Button size="sm" disabled={isSubmitting} onClick={() => { void submit(); }}>
      Pay
    </Button>
  );
}

export function InvoiceTable({
  invoices, onPay,
}: { invoices: InvoiceRow[]; onPay: (invoiceId: string) => Promise<void> }) {
  if (invoices.length === 0) {
    return <p className="text-muted-foreground">No invoices for this filter.</p>;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Due date</TableHead>
          <TableHead>Amount</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Reminder level</TableHead>
          <TableHead />
        </TableRow>
      </TableHeader>
      <TableBody>
        {invoices.map((invoice) => (
          <TableRow key={invoice.invoiceId}>
            <TableCell>{invoice.dueDate}</TableCell>
            <TableCell>
              <MoneyText {...invoice.amount} />
            </TableCell>
            <TableCell>
              <Badge>{invoice.status}</Badge>
            </TableCell>
            <TableCell>
              {invoice.dunningLevel != null ? `Reminder ${invoice.dunningLevel}` : null}
            </TableCell>
            <TableCell>
              {PAYABLE_STATUSES.has(invoice.status) && (
                <PayButton invoiceId={invoice.invoiceId} onPay={onPay} />
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
