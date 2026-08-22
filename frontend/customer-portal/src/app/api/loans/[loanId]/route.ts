import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Loan detail (current outstanding balance etc). Read-only pass-through -- neither layer of the
 * hard guard applies, only the two mutating loan endpoints need it.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ loanId: string }> },
) {
  const { loanId } = await params;
  try {
    const data = await callBackend<unknown>(`/loans/${encodeURIComponent(loanId)}`);
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
