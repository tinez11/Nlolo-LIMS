import { NextRequest, NextResponse } from 'next/server';
import { randomUUID } from 'node:crypto';
import { ApiError, callBackend, resolveOwnPartyId } from '@/lib/backend';

/**
 * Claim list for the signed-in customer. `claimantPartyId` is deliberately NEVER forwarded, even if
 * a client somehow sent one: `GET /claims` force-overrides it from the token's `party_id` claim for
 * customers-realm tokens (verified against `ClaimController.listClaims`), the same discipline as the
 * policy list's `policyholderPartyId` (Task 8). The portal has no business sending it at all.
 */
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  try {
    const data = await callBackend<unknown>('/claims', {
      searchParams: {
        page: params.get('page') ?? 0,
        pageSize: params.get('pageSize') ?? 20,
        status: params.get('status') ?? undefined,
      },
    });
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}

/**
 * Register a new claim. Unlike the list filter above, `POST /claims` does NOT override
 * `claimantPartyId` server-side -- it VALIDATES the request body's value against the token's own
 * `party_id` claim and returns 403 on a mismatch (`ClaimController.enforceCustomerOwnClaimantOnly`),
 * so the field is genuinely required on the wire. The only safe source for it is still never the
 * browser: this handler derives it itself from the verified access token via `resolveOwnPartyId`
 * and ignores any `claimantPartyId` a caller might put in the request body.
 *
 * Layer 1 only (`useSubmitGuard`, client-side) -- no Redis claim here. `POST /claims` is genuinely
 * idempotent server-side (claims/V3's registration-idempotency index keyed on Idempotency-Key),
 * unlike the two loan endpoints Task 12 guards with a full two-layer claim.
 */
export async function POST(request: NextRequest) {
  const body = (await request.json()) as {
    policyNumber: string;
    claimType: 'DEATH' | 'DISABILITY' | 'CRITICAL_ILLNESS' | 'MATURITY';
    dateOfEvent: string;
    details: unknown;
    idempotencyKey?: string;
  };
  try {
    const claimantPartyId = await resolveOwnPartyId();
    const claim = await callBackend('/claims', {
      method: 'POST',
      body: {
        policyNumber: body.policyNumber,
        claimantPartyId,
        claimType: body.claimType,
        dateOfEvent: body.dateOfEvent,
        details: body.details,
      },
      headers: { 'Idempotency-Key': body.idempotencyKey ?? randomUUID() },
    });
    return NextResponse.json(claim, { status: 201 });
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
