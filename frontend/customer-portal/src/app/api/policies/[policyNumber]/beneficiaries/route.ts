import { NextRequest, NextResponse } from 'next/server';
import { callBackendRaw } from '@/lib/backend';
import type { ApiProblem } from '@/lib/problem';

/**
 * Replace the beneficiary designation set. The backend enforces exactly-one-of (partyId,
 * freeformDesignee) per beneficiary AND that active shares sum to 100%, returning 422 otherwise —
 * this handler is a thin pass-through (client-side validation in BeneficiaryForm is UX only, the
 * backend remains the authority).
 *
 * Deliberately uses `callBackendRaw` instead of `callBackend`: the real controller
 * (PolicyController#replaceBeneficiaries) returns `ResponseEntity.ok().build()` on success —
 * HTTP 200 with a genuinely empty body, per the OpenAPI type (`200` response has `content?:
 * never`) — NOT 204. `callBackend`'s empty-body special case only fires on an exact 204, so
 * calling it here would call `.json()` on that empty 200 body and throw (`SyntaxError: Unexpected
 * end of JSON input`) on every SUCCESSFUL save. Handling the raw Response directly avoids that.
 */
export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ policyNumber: string }> },
) {
  const { policyNumber } = await params;
  const body = await request.json();
  const response = await callBackendRaw(
    `/policies/${encodeURIComponent(policyNumber)}/beneficiaries`,
    { method: 'PUT', body },
  );

  if (!response.ok) {
    let problem: ApiProblem | null = null;
    try {
      problem = (await response.json()) as ApiProblem;
    } catch {
      problem = null; // HTML error page, empty body, gateway failure
    }
    return NextResponse.json(problem, { status: response.status });
  }

  return new NextResponse(null, { status: 204 });
}
