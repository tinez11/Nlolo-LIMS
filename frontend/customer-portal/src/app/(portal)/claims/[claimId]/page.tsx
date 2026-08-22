'use client';

import { use } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { MoneyText } from '@/components/money';
import { EvidencePanel, type EvidenceItem } from '@/components/evidence-panel';
import { mapApiError, type ApiProblem } from '@/lib/problem';
import type { components } from '@/types/api/claims';

type ClaimView = components['schemas']['ClaimView'];
type ClaimEvidenceView = components['schemas']['ClaimEvidenceView'];

/**
 * Client-side counterpart to `lib/backend.ts`'s `ApiError`, same pattern as every other detail page
 * in this portal: the Route Handlers under `app/api/claims/**` already normalize backend failures
 * into a plain ProblemDetails JSON body, decoded here.
 */
class PortalFetchError extends Error {
  constructor(readonly problem: ApiProblem | null, readonly status: number) {
    super(problem?.title ?? `Request failed with status ${status}`);
    this.name = 'PortalFetchError';
  }
}

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new PortalFetchError(body as ApiProblem | null, response.status);
  }
  return body as T;
}

function errorMessage(error: unknown): string {
  return error instanceof PortalFetchError ? mapApiError(error.problem, error.status) : mapApiError(null);
}

function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

/**
 * `ClaimEvidenceView` carries no original filename -- only an optional free-text `description` --
 * so the visible label falls back to that, or to the opaque `documentRef` itself when even the
 * description is absent. This never invents a filename that was not actually supplied.
 */
function toEvidenceItem(evidence: ClaimEvidenceView): EvidenceItem {
  return { documentRef: evidence.documentRef, fileName: evidence.description ?? evidence.documentRef };
}

export default function ClaimDetailPage({
  params,
}: {
  params: Promise<{ claimId: string }>;
}) {
  const { claimId } = use(params);
  const queryClient = useQueryClient();

  const claimQuery = useQuery({
    queryKey: ['claim', claimId],
    queryFn: () => fetchJson<ClaimView>(`/api/claims/${encodeURIComponent(claimId)}`),
  });
  const evidenceQuery = useQuery({
    queryKey: ['claim', claimId, 'evidence'],
    queryFn: () => fetchJson<ClaimEvidenceView[]>(`/api/claims/${encodeURIComponent(claimId)}/evidence`),
  });

  /**
   * Multipart upload, forwarded through `/api/claims/{claimId}/evidence` -- the same
   * `FormData`-as-`callBackendRaw`-body path the Route Handler expects. No `Content-Type` is set
   * here either: the browser's own `fetch` computes the multipart boundary once given a `FormData`
   * body, exactly as it does one hop further on when the Route Handler forwards it to the backend.
   */
  async function handleUpload(file: File) {
    const formData = new FormData();
    formData.set('file', file);
    const response = await fetch(`/api/claims/${encodeURIComponent(claimId)}/evidence`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new PortalFetchError(problem as ApiProblem | null, response.status);
    }
    await queryClient.invalidateQueries({ queryKey: ['claim', claimId, 'evidence'] });
  }

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">Claim {claimId}</h1>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Claim details</CardTitle>
          {claimQuery.data?.status && <Badge>{titleCase(claimQuery.data.status)}</Badge>}
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          {claimQuery.isLoading && <Skeleton className="h-24" />}
          {claimQuery.isError && <p className="text-destructive">{errorMessage(claimQuery.error)}</p>}
          {claimQuery.data && (
            <>
              <div>Policy: {claimQuery.data.policyNumber}</div>
              <div>Type: {titleCase(claimQuery.data.claimType)}</div>
              <div>Date of event: {claimQuery.data.dateOfEvent}</div>
              {claimQuery.data.approvedAmount && (
                <div>
                  Approved amount: <MoneyText {...claimQuery.data.approvedAmount} />
                </div>
              )}
              {/*
                requiresContestabilityReview is an internal assessment signal (re-derived on every
                read), never surfaced as customer-facing copy -- same rule ClaimList follows.
              */}
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Evidence</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {evidenceQuery.isLoading && <Skeleton className="h-16" />}
          {evidenceQuery.isError && <p className="text-destructive">{errorMessage(evidenceQuery.error)}</p>}
          {evidenceQuery.data && (
            <EvidencePanel
              claimId={claimId}
              evidence={evidenceQuery.data.map(toEvidenceItem)}
              onUpload={handleUpload}
            />
          )}
        </CardContent>
      </Card>
    </div>
  );
}
