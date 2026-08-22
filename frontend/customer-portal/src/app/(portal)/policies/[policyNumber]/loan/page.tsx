'use client';

import { use, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { MoneyText } from '@/components/money';
import { ReferenceNote } from '@/components/reference-note';
import { useSubmitGuard } from '@/hooks/use-submit-guard';
import { mapApiError, type ApiProblem } from '@/lib/problem';
import type { components } from '@/types/api/policyloan';
import type { components as RefdataComponents } from '@/types/api/refdata';

type LoanView = components['schemas']['LoanView'];
type ReferenceCodeSetView = RefdataComponents['schemas']['ReferenceCodeSetView'];

/** A loan that no longer needs attention on this screen -- it is fully repaid. Everything else
 * (including a failed disbursement) still belongs in "current", since a new attempt or a retry is
 * plausible next action for the customer. */
const CLOSED_STATUSES = new Set(['SETTLED']);
/** Repayment only makes sense once money has actually moved to the customer. */
const REPAYABLE_STATUSES = new Set(['DISBURSED', 'REPAYING']);

const CURRENCY_CODE = 'TZS';
const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;

/**
 * Client-side counterpart to `lib/backend.ts`'s `ApiError`, same pattern as the billing and
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

async function fetchLoans(policyNumber: string): Promise<LoanView[]> {
  const response = await fetch(`/api/policies/${encodeURIComponent(policyNumber)}/loans`);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new PortalFetchError(body as ApiProblem | null, response.status);
  }
  return body as LoanView[];
}

async function fetchInterestRateNote(): Promise<ReferenceCodeSetView | null> {
  const response = await fetch('/api/reference-codes/TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE');
  if (response.status === 404) {
    return null;
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new PortalFetchError(body as ApiProblem | null, response.status);
  }
  return body as ReferenceCodeSetView;
}

/**
 * Borrow form: originates a new policy loan. Wrapped in `useSubmitGuard` (Layer 1) and posts to a
 * Route Handler wrapped in `runGuardedMutation` (Layer 2) -- the full two-layer hard guard from
 * spec §6, since `POST /policies/{n}/loans` accepts `Idempotency-Key` and never reads it. The
 * `clientKey` is generated ONCE per form instance (this component's lifetime), not per submit
 * attempt, so a retry of the same attempt reuses the same claim.
 */
function BorrowForm({ policyNumber, onOriginated }: { policyNumber: string; onOriginated: () => void }) {
  const [amount, setAmount] = useState('');
  const [payeeRef, setPayeeRef] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [clientKey, setClientKey] = useState(() => crypto.randomUUID());

  const { submit, isSubmitting } = useSubmitGuard(async () => {
    setError(null);
    if (!AMOUNT_PATTERN.test(amount)) {
      setError('Enter a valid amount, e.g. 50000 or 50000.00.');
      return;
    }
    const response = await fetch(`/api/policies/${encodeURIComponent(policyNumber)}/loans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ amount, currencyCode: CURRENCY_CODE, payeeRef, clientKey }),
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      setError(mapApiError(problem as ApiProblem | null, response.status));
      return;
    }
    setAmount('');
    setPayeeRef('');
    // A successful submission is a definitive outcome recorded under this clientKey. Rotate it
    // now so the NEXT submission from this still-mounted form is a genuinely new idempotency
    // claim, not a replay of this one's recorded success (see C2 in the final review).
    setClientKey(crypto.randomUUID());
    onOriginated();
  });

  return (
    <form onSubmit={(event) => { event.preventDefault(); void submit(); }} className="space-y-3">
      <div className="space-y-1">
        <Label htmlFor="loan-amount">Amount to borrow</Label>
        <Input
          id="loan-amount"
          placeholder="e.g. 50000"
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="loan-payee-ref">Disbursement destination (mobile money number)</Label>
        <Input
          id="loan-payee-ref"
          placeholder="e.g. 255712345678"
          value={payeeRef}
          onChange={(event) => setPayeeRef(event.target.value)}
        />
      </div>
      {error && <p role="alert" className="text-destructive text-sm">{error}</p>}
      <Button type="submit" disabled={isSubmitting || !amount.trim() || !payeeRef.trim()}>
        Request loan
      </Button>
    </form>
  );
}

/**
 * Repayment form: same two-layer guard as `BorrowForm`, targeting `POST /loans/{id}/repayments`,
 * the other endpoint with no server-side idempotency.
 */
function RepayForm({ loanId, onRepaid }: { loanId: string; onRepaid: () => void }) {
  const [amount, setAmount] = useState('');
  const [paymentReference, setPaymentReference] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [clientKey, setClientKey] = useState(() => crypto.randomUUID());

  const { submit, isSubmitting } = useSubmitGuard(async () => {
    setError(null);
    if (!AMOUNT_PATTERN.test(amount)) {
      setError('Enter a valid amount, e.g. 10000 or 10000.00.');
      return;
    }
    const response = await fetch(`/api/loans/${encodeURIComponent(loanId)}/repayments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ amount, currencyCode: CURRENCY_CODE, paymentReference, clientKey }),
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      setError(mapApiError(problem as ApiProblem | null, response.status));
      return;
    }
    setAmount('');
    setPaymentReference('');
    // Same reasoning as BorrowForm: rotate the clientKey after a definitive success so a second,
    // genuinely new repayment from this still-mounted form is not silently replayed (see C2).
    setClientKey(crypto.randomUUID());
    onRepaid();
  });

  return (
    <form onSubmit={(event) => { event.preventDefault(); void submit(); }} className="space-y-3">
      <div className="space-y-1">
        <Label htmlFor="repay-amount">Repayment amount</Label>
        <Input
          id="repay-amount"
          placeholder="e.g. 10000"
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="repay-reference">Payment reference</Label>
        <Input
          id="repay-reference"
          placeholder="Mobile money transaction reference"
          value={paymentReference}
          onChange={(event) => setPaymentReference(event.target.value)}
        />
      </div>
      {error && <p role="alert" className="text-destructive text-sm">{error}</p>}
      <Button type="submit" disabled={isSubmitting || !amount.trim() || !paymentReference.trim()}>
        Make repayment
      </Button>
    </form>
  );
}

export default function LoanPage({
  params,
}: {
  params: Promise<{ policyNumber: string }>;
}) {
  const { policyNumber } = use(params);
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState<string | null>(null);

  const loansQuery = useQuery({
    queryKey: ['policy', policyNumber, 'loans'],
    queryFn: () => fetchLoans(policyNumber),
  });
  const rateQuery = useQuery({
    queryKey: ['reference-codes', 'TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE'],
    queryFn: fetchInterestRateNote,
  });

  const loans = loansQuery.data ?? [];
  const currentLoan = loans.find((loan) => !CLOSED_STATUSES.has(loan.status ?? ''));
  const history = loans.filter((loan) => loan !== currentLoan);

  function refreshLoans(message: string) {
    setNotice(message);
    void queryClient.invalidateQueries({ queryKey: ['policy', policyNumber, 'loans'] });
  }

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">Policy loan — {policyNumber}</h1>

      <Card>
        <CardHeader>
          <CardTitle>Loan interest rate</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1">
          {rateQuery.isLoading && <Skeleton className="h-6" />}
          {rateQuery.isError && <p className="text-destructive text-sm">{errorMessage(rateQuery.error)}</p>}
          {rateQuery.data && rateQuery.data.values.length > 0 ? (
            rateQuery.data.values.map((entry) => (
              <ReferenceNote key={entry.code} label={entry.label} value={entry.value} />
            ))
          ) : rateQuery.data ? (
            <p className="text-sm text-muted-foreground">No published rate yet.</p>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Current loan</CardTitle>
          {currentLoan?.status && <Badge>{currentLoan.status}</Badge>}
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {loansQuery.isLoading && <Skeleton className="h-24" />}
          {loansQuery.isError && <p className="text-destructive">{errorMessage(loansQuery.error)}</p>}
          {!loansQuery.isLoading && !loansQuery.isError && !currentLoan && (
            <p className="text-muted-foreground">No active loan against this policy.</p>
          )}
          {currentLoan && (
            <>
              {currentLoan.principalAmount && (
                <div>
                  Principal: <MoneyText {...currentLoan.principalAmount} />
                </div>
              )}
              {currentLoan.outstandingBalance && (
                <div>
                  Outstanding balance: <MoneyText {...currentLoan.outstandingBalance} />
                </div>
              )}
              {typeof currentLoan.currentInterestRate === 'number' && (
                <div>Current interest rate: {currentLoan.currentInterestRate}%</div>
              )}
            </>
          )}

          {currentLoan?.loanId && REPAYABLE_STATUSES.has(currentLoan.status ?? '') && (
            <div className="pt-2">
              <h2 className="text-sm font-medium pb-2">Make a repayment</h2>
              <RepayForm
                key={currentLoan.loanId}
                loanId={currentLoan.loanId}
                onRepaid={() => refreshLoans('Your repayment was submitted.')}
              />
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Borrow against this policy</CardTitle>
        </CardHeader>
        <CardContent>
          {/* Always shown, even though loan origination currently always 409s
              INSUFFICIENT_LOAN_VALUE platform-wide (cash value is never credited yet) -- that is
              rendered as the expected outcome via mapApiError below, not hidden or faked. This
              becomes live functionality the moment the backend gap closes, with no portal change
              needed. */}
          <BorrowForm
            key={policyNumber}
            policyNumber={policyNumber}
            onOriginated={() => refreshLoans('Your loan request was submitted.')}
          />
        </CardContent>
      </Card>

      {notice && (
        <Alert>
          <AlertTitle>Request submitted</AlertTitle>
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      )}

      {history.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Loan history</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            {history.map((loan) => (
              <div key={loan.loanId} className="flex items-center justify-between border-b pb-2 last:border-b-0">
                <span>{loan.loanId}</span>
                {loan.principalAmount && <MoneyText {...loan.principalAmount} />}
                {loan.status && <Badge variant="secondary">{loan.status}</Badge>}
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
