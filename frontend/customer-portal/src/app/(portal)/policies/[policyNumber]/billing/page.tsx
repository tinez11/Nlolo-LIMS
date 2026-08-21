'use client';

import { use, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { MoneyText } from '@/components/money';
import { InvoiceTable, type InvoiceRow } from '@/components/invoice-table';
import { useSubmitGuard } from '@/hooks/use-submit-guard';
import { mapApiError, type ApiProblem } from '@/lib/problem';

const STATUS_TABS = ['ALL', 'DUE', 'OVERDUE', 'IN_GRACE', 'PAID', 'WAIVED'] as const;
type StatusTab = (typeof STATUS_TABS)[number];

/**
 * Client-side counterpart to `lib/backend.ts`'s `ApiError`, same pattern as the dashboard and
 * policy-detail pages: the Route Handlers under `app/api/**` already normalize backend failures
 * into a plain ProblemDetails JSON body, decoded here.
 */
class PortalFetchError extends Error {
  constructor(readonly problem: ApiProblem | null, readonly status: number) {
    super(problem?.title ?? `Request failed with status ${status}`);
    this.name = 'PortalFetchError';
  }
}

function errorMessage(error: unknown): string {
  return error instanceof PortalFetchError ? mapApiError(error.problem, error.status) : mapApiError(null);
}

/**
 * `GET .../invoices/next-due` returns a real 404 when nothing is currently due for the policy --
 * that is an expected "nothing owed" state, not a fault, so it resolves to `null` here instead of
 * throwing. Any other non-OK status is a genuine fetch error.
 */
async function fetchNextDue(policyNumber: string): Promise<InvoiceRow | null> {
  const response = await fetch(`/api/policies/${encodeURIComponent(policyNumber)}/invoices/next-due`);
  if (response.status === 404) {
    return null;
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new PortalFetchError(body as ApiProblem | null, response.status);
  }
  return body as InvoiceRow;
}

/**
 * Deliberately sends only `status` -- no `page`/`pageSize`. `GET /policies/{n}/invoices` declares
 * no such params (verified against openapi-billing.yaml): invoices are pre-generated ~12 months
 * ahead, a bounded list by construction, so there is no pager to drive here, unlike the policy list
 * in Task 8.
 */
async function fetchInvoices(policyNumber: string, status: StatusTab): Promise<InvoiceRow[]> {
  const query = status === 'ALL' ? '' : `?status=${encodeURIComponent(status)}`;
  const response = await fetch(`/api/policies/${encodeURIComponent(policyNumber)}/invoices${query}`);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new PortalFetchError(body as ApiProblem | null, response.status);
  }
  return body as InvoiceRow[];
}

/**
 * Prompts for the mobile-money `payerRef` and submits `POST /api/invoices/{id}/payment-request`.
 * The Idempotency-Key is generated ONCE when this dialog instance is created (the parent mounts a
 * fresh instance per invoice via `key={invoiceId}`), not per keystroke and not per submit click --
 * a client-side retry of the SAME dialog interaction (e.g. re-clicking "Request payment" after a
 * transient failure) must reuse the same key so the backend's real DB-backed dedup registry
 * deduplicates it. Only Layer 1 (`useSubmitGuard`) guards the submit; there is no Redis claim here
 * because, unlike the loan endpoints, billing's payment-request genuinely enforces the key
 * server-side already.
 */
function PayDialog({
  invoiceId, onClose, onSubmitted,
}: { invoiceId: string; onClose: () => void; onSubmitted: () => void }) {
  const [payerRef, setPayerRef] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  const { submit, isSubmitting } = useSubmitGuard(async () => {
    setError(null);
    const response = await fetch(`/api/invoices/${encodeURIComponent(invoiceId)}/payment-request`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ payerRef, idempotencyKey }),
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      setError(mapApiError(problem as ApiProblem | null, response.status));
      return;
    }
    onSubmitted();
  });

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Pay this invoice</DialogTitle>
          <DialogDescription>
            Enter the mobile-money number to collect this premium payment from.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(event) => { event.preventDefault(); void submit(); }}
          className="space-y-3"
        >
          <div className="space-y-1">
            <Label htmlFor="payerRef">Mobile money number</Label>
            <Input
              id="payerRef"
              placeholder="e.g. 255712345678"
              value={payerRef}
              onChange={(event) => setPayerRef(event.target.value)}
            />
          </div>
          {error && <p role="alert" className="text-destructive text-sm">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={isSubmitting}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSubmitting || !payerRef.trim()}>
              Request payment
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export default function BillingPage({
  params,
}: {
  params: Promise<{ policyNumber: string }>;
}) {
  const { policyNumber } = use(params);
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<StatusTab>('ALL');
  const [payingInvoiceId, setPayingInvoiceId] = useState<string | null>(null);
  const [payMessage, setPayMessage] = useState<string | null>(null);

  const nextDueQuery = useQuery({
    queryKey: ['policy', policyNumber, 'invoices', 'next-due'],
    queryFn: () => fetchNextDue(policyNumber),
  });
  const invoicesQuery = useQuery({
    queryKey: ['policy', policyNumber, 'invoices', status],
    queryFn: () => fetchInvoices(policyNumber, status),
  });

  // Opening the dialog is the whole job of this handler -- the actual POST happens once the
  // customer supplies a payerRef inside PayDialog. useSubmitGuard still wraps this in
  // InvoiceTable so a same-tick double-click on "Pay" can't open two dialog instances (and,
  // if a future change moves the request itself here, is already guarded against a double-send).
  async function handlePay(invoiceId: string) {
    setPayMessage(null);
    setPayingInvoiceId(invoiceId);
  }

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">Billing — {policyNumber}</h1>

      <Card>
        <CardHeader>
          <CardTitle>Next due</CardTitle>
        </CardHeader>
        <CardContent className="text-sm">
          {nextDueQuery.isLoading && <Skeleton className="h-8" />}
          {nextDueQuery.isError && <p className="text-destructive">{errorMessage(nextDueQuery.error)}</p>}
          {nextDueQuery.data === null && (
            <p className="text-muted-foreground">Nothing currently due.</p>
          )}
          {nextDueQuery.data && (
            <div>
              <MoneyText {...nextDueQuery.data.amount} /> due {nextDueQuery.data.dueDate}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Invoices</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <Tabs value={status} onValueChange={(value) => setStatus(value as StatusTab)}>
            <TabsList>
              {STATUS_TABS.map((tab) => (
                <TabsTrigger key={tab} value={tab}>
                  {tab === 'ALL' ? 'All' : tab}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          {/* Deliberately no pagination controls: the endpoint has none (GET /policies/{n}/invoices
              takes only `status`, no page/pageSize) and the list is bounded by construction --
              invoices are pre-generated ~12 months ahead. Status tabs replace a pager entirely. */}
          {invoicesQuery.isLoading && <Skeleton className="h-32" />}
          {invoicesQuery.isError && (
            <p className="text-destructive">{errorMessage(invoicesQuery.error)}</p>
          )}
          {invoicesQuery.data && (
            <InvoiceTable invoices={invoicesQuery.data} onPay={handlePay} />
          )}

          {payMessage && (
            <Alert>
              <AlertTitle>Payment requested</AlertTitle>
              <AlertDescription>{payMessage}</AlertDescription>
            </Alert>
          )}
        </CardContent>
      </Card>

      {payingInvoiceId && (
        <PayDialog
          key={payingInvoiceId}
          invoiceId={payingInvoiceId}
          onClose={() => setPayingInvoiceId(null)}
          onSubmitted={() => {
            setPayingInvoiceId(null);
            setPayMessage('Your payment request was submitted for collection.');
            void queryClient.invalidateQueries({ queryKey: ['policy', policyNumber, 'invoices'] });
          }}
        />
      )}
    </div>
  );
}
