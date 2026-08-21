import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Surrender VALUE QUOTE only — this is the one surrender-family endpoint that actually works today
 * (produces SurrenderValueCalculated internally, never commits to a surrender). The POST
 * /surrender endpoint and its /processes/{id} poll target both return 501
 * CHOREOGRAPHY_NOT_IMPLEMENTED and are deliberately never called from this portal (see
 * policies/[policyNumber]/page.tsx) — there is no Route Handler for either.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  try {
    const data = await callBackend<unknown>(
      `/policies/${encodeURIComponent(policyNumber)}/surrender-value`,
    );
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
