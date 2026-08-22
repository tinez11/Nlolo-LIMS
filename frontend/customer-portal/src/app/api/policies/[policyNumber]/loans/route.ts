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
 * List loans against a policy. Simple pass-through -- `GET /policies/{n}/loans` is read-only and
 * needs neither layer of the hard guard.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  try {
    const data = await callBackend<unknown>(`/policies/${encodeURIComponent(policyNumber)}/loans`);
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}

/**
 * Originate a policy loan -- one of the two endpoints with NO server-side idempotency
 * (`PolicyLoanController`/`PolicyLoanApiImpl` accept `Idempotency-Key` and never read it). Both
 * layers of the hard guard from spec §6 apply here: Layer 1 (`useSubmitGuard`) lives in the page
 * component; this is Layer 2, the server-side Redis claim via `runGuardedMutation`.
 *
 * Currently always 409s `INSUFFICIENT_LOAN_VALUE` because cash value is never credited
 * platform-wide -- that is handled generically by the page's `mapApiError` rendering, not specially
 * here.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  const body = (await request.json()) as {
    amount: string; currencyCode: string; payeeRef: string; clientKey: string;
  };

  // getToken(), NOT auth() -- same reason as Task 6's resolveAccessToken (lib/backend.ts): auth()
  // resolves through Task 5's session() callback, which deliberately strips everything but
  // `error`. `sub` is a standard JWT claim NextAuth itself sets from the Keycloak account and never
  // touched by our jwt()/session() callbacks, so it survives on the raw token getToken() returns.
  // Calling convention matches lib/backend.ts's resolveAccessToken, verified against the installed
  // next-auth@5.0.0-beta.32: `req: { headers }` is sufficient on its own since getToken() reads the
  // `cookie` header directly off it -- there is no separate `cookies` field in the real signature.
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
    realm: 'customers', subject, operation: 'loan-origination', clientKey: body.clientKey,
  });

  const result = await runGuardedMutation({
    store: redisIdempotencyStore(),
    key,
    perform: async (): Promise<PerformResult> => {
      try {
        const created = await callBackend<unknown>(
          `/policies/${encodeURIComponent(policyNumber)}/loans`,
          {
            method: 'POST',
            body: {
              requestedAmount: { amount: body.amount, currencyCode: body.currencyCode },
              payeeRef: body.payeeRef,
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
