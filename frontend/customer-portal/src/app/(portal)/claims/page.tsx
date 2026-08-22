'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ClaimList, type ClaimSummary } from '@/components/claim-list';
import { mapApiError, type ApiProblem } from '@/lib/problem';

const STATUS_TABS = [
  'ALL', 'REGISTERED', 'UNDER_ASSESSMENT', 'APPROVED', 'REJECTED',
  'SETTLEMENT_REQUESTED', 'SETTLED', 'REOPENED',
] as const;
type StatusTab = (typeof STATUS_TABS)[number];
const PAGE_SIZE = 20;

type PageMeta = { page: number; pageSize: number; totalElements: number };
type ClaimsResponse = { items: ClaimSummary[]; page: PageMeta };

/**
 * Client-side counterpart to `lib/backend.ts`'s `ApiError`, same pattern as the dashboard and
 * billing pages: `/api/claims` already normalizes backend failures into a plain ProblemDetails JSON
 * body, decoded here.
 */
class ClaimsFetchError extends Error {
  constructor(readonly problem: ApiProblem | null, readonly status: number) {
    super(problem?.title ?? `Request failed with status ${status}`);
    this.name = 'ClaimsFetchError';
  }
}

/**
 * Real server-side pagination and status filtering, same discipline as the policy list (Task 8):
 * `page`/`pageSize`/`status` are all forwarded as query params, never sliced client-side.
 * `claimantPartyId` is never sent -- there is no field for it anywhere in this file.
 */
async function fetchClaims(page: number, status: StatusTab): Promise<ClaimsResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(PAGE_SIZE) });
  if (status !== 'ALL') {
    params.set('status', status);
  }
  const response = await fetch(`/api/claims?${params.toString()}`);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ClaimsFetchError(body as ApiProblem | null, response.status);
  }
  return body as ClaimsResponse;
}

export default function ClaimsPage() {
  const [status, setStatus] = useState<StatusTab>('ALL');
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['claims', status, page],
    queryFn: () => fetchClaims(page, status),
  });

  const totalElements = data?.page.totalElements ?? 0;
  const pageSize = data?.page.pageSize ?? PAGE_SIZE;
  const hasNextPage = (page + 1) * pageSize < totalElements;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Your claims</h1>
        <Button size="sm" render={<Link href="/claims/new" />}>
          Register a claim
        </Button>
      </div>

      <Tabs
        value={status}
        onValueChange={(value) => {
          setStatus(value as StatusTab);
          setPage(0);
        }}
      >
        <TabsList>
          {STATUS_TABS.map((tab) => (
            <TabsTrigger key={tab} value={tab}>
              {tab === 'ALL' ? 'All' : tab.replaceAll('_', ' ')}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {isLoading && <Skeleton className="h-32" />}

      {isError && (
        <p className="text-destructive">
          {error instanceof ClaimsFetchError
            ? mapApiError(error.problem, error.status)
            : mapApiError(null)}
        </p>
      )}

      {data && (
        <>
          <ClaimList claims={data.items} />
          <div className="flex items-center justify-between pt-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </Button>
            <span className="text-sm text-muted-foreground">Page {page + 1}</span>
            <Button
              variant="outline"
              size="sm"
              disabled={!hasNextPage}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
