'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { PolicyList, type PolicySummary } from '@/components/policy-list';
import { mapApiError, type ApiProblem } from '@/lib/problem';

const STATUS_TABS = ['ALL', 'ACTIVE', 'LAPSED', 'SUSPENDED', 'MATURED'] as const;
type StatusTab = (typeof STATUS_TABS)[number];
const PAGE_SIZE = 20;

type PageMeta = { page: number; pageSize: number; totalElements: number };
type PoliciesResponse = { items: PolicySummary[]; page: PageMeta };

/**
 * Client-side counterpart to `lib/backend.ts`'s `ApiError`: that class imports `server-only` and
 * cannot be used from a Client Component, so this carries the same `problem`/`status` shape for
 * `mapApiError` to branch on. The Route Handler at `/api/policies` already normalizes backend
 * failures into a plain ProblemDetails JSON body (see `route.ts`), which is what gets parsed here.
 */
class PoliciesFetchError extends Error {
  constructor(readonly problem: ApiProblem | null, readonly status: number) {
    super(problem?.title ?? `Request failed with status ${status}`);
    this.name = 'PoliciesFetchError';
  }
}

async function fetchPolicies(page: number, status: StatusTab): Promise<PoliciesResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(PAGE_SIZE) });
  if (status !== 'ALL') {
    params.set('status', status);
  }
  const response = await fetch(`/api/policies?${params.toString()}`);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new PoliciesFetchError(body as ApiProblem | null, response.status);
  }
  return body as PoliciesResponse;
}

export default function DashboardPage() {
  const [status, setStatus] = useState<StatusTab>('ALL');
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['policies', status, page],
    queryFn: () => fetchPolicies(page, status),
  });

  const totalElements = data?.page.totalElements ?? 0;
  const pageSize = data?.page.pageSize ?? PAGE_SIZE;
  const hasNextPage = (page + 1) * pageSize < totalElements;

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Your policies</h1>

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
              {tab === 'ALL' ? 'All' : tab}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      {isLoading && (
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
        </div>
      )}

      {isError && (
        <p className="text-destructive">
          {error instanceof PoliciesFetchError
            ? mapApiError(error.problem, error.status)
            : mapApiError(null)}
        </p>
      )}

      {data && (
        <>
          <PolicyList policies={data.items} />
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
