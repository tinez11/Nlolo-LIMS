import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Claim detail. The backend performs the object-level ownership check itself
 * (`ClaimController.enforceCustomerOwnClaimOnly`) -- a customers-realm token may only read a claim
 * whose `claimantPartyId` matches its own `party_id` claim, 403 otherwise -- this handler is a thin
 * pass-through.
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ claimId: string }> },
) {
  const { claimId } = await params;
  try {
    const data = await callBackend<unknown>(`/claims/${encodeURIComponent(claimId)}`);
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
