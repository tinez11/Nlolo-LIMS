import { NextRequest, NextResponse } from 'next/server';
import { callBackendRaw } from '@/lib/backend';

/**
 * Binary passthrough for the claim-evidence download M11 built. Deliberately uses `callBackendRaw`,
 * never `callBackend`: the response is a file, not JSON, and the backend already set its own
 * Content-Type/Content-Disposition (one of the four allowlisted evidence media types, or the
 * document's stored filename) after its own dual ownership check -- claim ownership via
 * `ClaimController.enforceCustomerOwnClaimOnly`, AND that `documentRef` actually belongs to THIS
 * claim (`ClaimEvidenceController.downloadEvidence`'s `metadata.ownerContext()` check, a mismatch
 * reported as 404, not 403, so a caller cannot tell the two apart). This handler forwards those
 * headers rather than inventing its own, and streams the body straight through with no buffering,
 * no caching, and no generic `/documents/{ref}` shortcut that could bypass the claim-scoped check.
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ claimId: string; documentRef: string }> },
) {
  const { claimId, documentRef } = await params;
  const upstream = await callBackendRaw(
    `/claims/${encodeURIComponent(claimId)}/evidence/${encodeURIComponent(documentRef)}`,
  );

  if (!upstream.ok) {
    let problem = null;
    try {
      problem = await upstream.json();
    } catch {
      problem = null;
    }
    return NextResponse.json(problem, { status: upstream.status });
  }

  return new NextResponse(upstream.body, {
    status: 200,
    headers: {
      'Content-Type': upstream.headers.get('content-type') ?? 'application/octet-stream',
      ...(upstream.headers.get('content-disposition')
        ? { 'Content-Disposition': upstream.headers.get('content-disposition')! }
        : {}),
    },
  });
}
