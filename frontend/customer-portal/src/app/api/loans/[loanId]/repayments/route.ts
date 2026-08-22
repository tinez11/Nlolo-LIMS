import { NextRequest, NextResponse } from 'next/server';
import { getToken } from 'next-auth/jwt';
import { headers } from 'next/headers';
import { ApiError, callBackend } from '@/lib/backend';
import { runGuardedMutation, type PerformResult } from '@/lib/guarded-mutation';
import { idempotencyKeyFor } from '@/lib/idempotency';
import { redisIdempotencyStore } from '@/lib/redis';

/** 408/429 and every 5xx are indeterminate -- the backend may have applied the change. Any other
 * 4xx is a correctable rejection that changed nothing. */
const INDETERMINATE = new Set([408, 429]);

/**
 * Record a loan repayment -- the second of the two endpoints with NO server-side idempotency
 * (same defect as loan origination, verified against `PolicyLoanController`/`PolicyLoanApiImpl`).
 * Deliberately near-identical to `.../policies/[policyNumber]/loans/route.ts`'s POST rather than a
 * shared helper: path, body shape, and the `operation` string passed to `idempotencyKeyFor` all
 * differ, and factoring out two call sites this early would obscure that rather than simplify it.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ loanId: string }> },
) {
  const { loanId } = await params;
  const body = (await request.json()) as {
    amount: string; currencyCode: string; paymentReference: string; clientKey: string;
  };

  // getToken(), NOT auth() -- same reason as Task 6's resolveAccessToken (lib/backend.ts) and the
  // loan-origination handler above: auth() resolves through Task 5's session() callback, which
  // deliberately strips everything but `error`. `sub` is a standard JWT claim untouched by our
  // jwt()/session() callbacks, so it survives on the raw token getToken() returns.
  const token = (await getToken({
    req: { headers: await headers() },
    secret: process.env.AUTH_SECRET,
  })) as { sub?: string } | null;
  const subject = token?.sub;
  if (!subject) {
    return NextResponse.json(
      { type: 'about:blank', title: 'Unauthorized', status: 401, errorCode: 'UNAUTHENTICATED', traceId: 'portal-guard' },
      { status: 401 },
    );
  }

  const key = idempotencyKeyFor({
    realm: 'customers', subject, operation: 'loan-repayment', clientKey: body.clientKey,
  });

  const result = await runGuardedMutation({
    store: redisIdempotencyStore(),
    key,
    perform: async (): Promise<PerformResult> => {
      try {
        const created = await callBackend<unknown>(
          `/loans/${encodeURIComponent(loanId)}/repayments`,
          {
            method: 'POST',
            body: {
              amount: { amount: body.amount, currencyCode: body.currencyCode },
              paymentReference: body.paymentReference,
            },
            headers: { 'Idempotency-Key': body.clientKey },
          },
        );
        return { kind: 'success', body: created };
      } catch (error) {
        if (error instanceof ApiError) {
          if (error.status >= 500 || INDETERMINATE.has(error.status)) {
            return { kind: 'indeterminate', status: error.status };
          }
          return { kind: 'rejected', status: error.status, problem: error.problem };
        }
        return { kind: 'indeterminate', status: 504 };
      }
    },
  });

  return NextResponse.json(result.body ?? null, { status: result.status });
}
