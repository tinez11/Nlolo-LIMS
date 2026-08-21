import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Single policy detail. The backend performs its own object-level authorization (a customer token
 * may only fetch a policy where policyholderPartyId matches the token's party_id claim; a
 * cross-tenant or someone-else's policy comes back as 404, never 403 — see PolicyNotFound in
 * ProblemDetails) so this handler is a thin pass-through, same shape as Task 8's list route.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  try {
    const data = await callBackend<unknown>(`/policies/${encodeURIComponent(policyNumber)}`);
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
