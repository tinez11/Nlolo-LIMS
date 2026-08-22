import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend } from '@/lib/backend';

/**
 * Hard client-side allowlist, exactly the 5 keys the customers realm may read (verified against
 * `ReferenceDataController.PUBLICLY_DISCLOSED`, M11). The backend fails closed on the same list
 * too, but the portal must never even ATTEMPT a request for a key it has no business showing --
 * this 404s before `callBackend` is reached, with no backend round trip at all.
 */
const CUSTOMER_READABLE = new Set([
  'TZ_CONTESTABILITY_MONTHS',
  'TZ_REINSTATEMENT_WINDOW_MONTHS',
  'TZ_SUSPENSION_TO_LAPSE_MONTHS',
  'TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE',
  'DUNNING_ESCALATION_DAYS',
]);

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ codeSetKey: string }> },
) {
  const { codeSetKey } = await params;
  if (!CUSTOMER_READABLE.has(codeSetKey)) {
    return NextResponse.json(
      {
        type: 'about:blank', title: 'Not Found', status: 404,
        errorCode: 'NOT_FOUND', traceId: 'portal-allowlist',
        detail: 'This reference key is not available to customers.',
      },
      { status: 404 },
    );
  }

  try {
    const data = await callBackend<unknown>(`/reference-codes/${encodeURIComponent(codeSetKey)}`);
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}
