import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Coverage-status-as-of-a-date for the signed-in customer's own policy. Made customer-reachable in
 * M12 Task 1; the backend still runs the same object-level ownership check as the policy-detail
 * endpoint.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  const asOf = request.nextUrl.searchParams.get('asOf') ?? undefined;
  try {
    const data = await callBackend<unknown>(
      `/policies/${encodeURIComponent(policyNumber)}/coverage-status`,
      { searchParams: { asOf } },
    );
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
