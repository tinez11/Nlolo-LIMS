import { NextRequest, NextResponse } from 'next/server';
import { ApiError, callBackend, callBackendRaw } from '@/lib/backend';
import type { ApiProblem } from '@/lib/problem';

/**
 * List evidence attached to a claim. Same object-level ownership check as claim detail
 * (`ClaimController.enforceCustomerOwnClaimOnly`, re-applied here per
 * `ClaimEvidenceController.listEvidence`'s own javadoc) -- a thin pass-through.
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ claimId: string }> },
) {
  const { claimId } = await params;
  try {
    const data = await callBackend<unknown>(`/claims/${encodeURIComponent(claimId)}/evidence`);
    return NextResponse.json(data);
  } catch (error) {
    if (error instanceof ApiError) {
      return NextResponse.json(error.problem, { status: error.status });
    }
    throw error;
  }
}

/**
 * Attach evidence to a claim. The incoming request is `multipart/form-data`; it is read once via
 * `request.formData()` and forwarded to the backend as a `FormData` body through `callBackendRaw`
 * (NOT `callBackend`, and NOT JSON) so `fetch` computes its own multipart boundary -- setting a
 * `Content-Type` header by hand here would carry no boundary parameter and break the upload.
 *
 * `callBackendRaw` is used directly (not `callBackend`) purely to keep this handler's response
 * shape consistent with the other Route Handlers in this file (JSON success body verbatim, decoded
 * ProblemDetails on failure) without relying on `callBackend`'s narrower throw/catch contract for
 * what is, on the way out, an ordinary JSON response.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ claimId: string }> },
) {
  const { claimId } = await params;
  const formData = await request.formData();

  const upstream = await callBackendRaw(`/claims/${encodeURIComponent(claimId)}/evidence`, {
    method: 'POST',
    body: formData,
  });

  if (!upstream.ok) {
    let problem: ApiProblem | null = null;
    try {
      problem = (await upstream.json()) as ApiProblem;
    } catch {
      problem = null; // HTML error page, empty body, gateway failure
    }
    return NextResponse.json(problem, { status: upstream.status });
  }

  const data = await upstream.json();
  return NextResponse.json(data, { status: 201 });
}
