import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Lightweight "what do I owe?" lookup for the billing page's summary banner. A 404 here is a
 * legitimate outcome (no invoice currently due), not a fault -- the page treats it as "nothing due"
 * rather than an error state, same as any other ApiError-shaped response passed through as-is.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  try {
    const data = await callBackend<unknown>(
      `/policies/${encodeURIComponent(policyNumber)}/invoices/next-due`,
    );
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
