import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Invoice list for the signed-in customer's own policy. Deliberately forwards ONLY `status` --
 * `GET /policies/{n}/invoices` declares no `page`/`pageSize` at all (unlike `/policies` in Task 8),
 * because a policy's invoices are pre-generated roughly 12 months ahead: a bounded list by
 * construction, not one that grows without limit. Adding pagination params here would be
 * inconsistent with the real backend contract, not with this handler.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  const status = request.nextUrl.searchParams.get('status') ?? undefined;
  try {
    const data = await callBackend<unknown>(
      `/policies/${encodeURIComponent(policyNumber)}/invoices`,
      { searchParams: { status } },
    );
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
