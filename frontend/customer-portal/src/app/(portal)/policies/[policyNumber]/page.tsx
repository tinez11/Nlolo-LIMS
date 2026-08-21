'use client';

import { use, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { MoneyText } from '@/components/money';
import { BeneficiaryForm, type BeneficiaryInput } from '@/components/beneficiary-form';
import { mapApiError, type ApiProblem } from '@/lib/problem';
import type { components } from '@/types/api/policy';

type PolicyView = components['schemas']['PolicyView'];
type CoverageStatusView = components['schemas']['CoverageStatusView'];
/** The wire shape the backend actually expects -- distinct from BeneficiaryForm's UI-only type. */
type ApiBeneficiaryInput = components['schemas']['BeneficiaryInput'];
type SurrenderValueQuote = {
  policyNumber?: string;
  quotedValue?: components['schemas']['Money'];
  quotedAt?: string;
};

/**
 * Client-side counterpart to `lib/backend.ts`'s `ApiError` (same pattern as the dashboard's
 * `PoliciesFetchError`): the Route Handlers under `app/api/policies/[policyNumber]/**` already
 * normalize backend failures into a plain ProblemDetails JSON body, decoded here.
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

/**
 * Surrender explanation panel, NOT a working submit control. `POST /policies/{n}/surrender` and
 * `GET /policies/{n}/processes/{id}` both really do return 501 CHOREOGRAPHY_NOT_IMPLEMENTED today
 * (the surrender/maturity loan-netting choreography is deferred pending a workflow-engine choice --
 * Camunda 7 is EOL, Camunda 8 needs a licence and a cross-tenant leak review). This portal never
 * calls either endpoint; the copy below comes from the same `mapApiError` table the backend's real
 * 501 response would map to, so it can never drift from what a real failed attempt would say.
 */
const SURRENDER_NOT_AVAILABLE = mapApiError({
  type: 'about:blank',
  title: 'Not Implemented',
  status: 501,
  errorCode: 'CHOREOGRAPHY_NOT_IMPLEMENTED',
  traceId: 'n/a',
});

/**
 * UI rows (strings, exactly-one-of partyId/freeformDesignee, no wire-only fields) -> the wire
 * BeneficiaryInput[] the backend actually expects (`type` discriminator, numeric `sharePercent`,
 * required `revocable`). Each share string was already validated and integer-summed by
 * BeneficiaryForm before `onSave` is ever invoked, so this `Number()` is a single parse of an
 * already-validated value for serialization -- not a summing operation, and cannot reintroduce the
 * float rounding the integer-cents helper exists to avoid.
 *
 * `revocable` is round-tripped from whatever the row actually carries, NOT hardcoded to `true`: a
 * beneficiary an agent set as irrevocable at manual issue must not be silently flipped to revocable
 * just because the customer edited a different row's share or name. `?? true` only fills in the
 * OpenAPI schema's own documented default for a row that never had the field set at all (e.g. a
 * brand-new row added via "Add beneficiary" already carries `revocable: true` from `emptyRow()`, so
 * this fallback is a defensive no-op in practice, not a real behavior change).
 */
function toWireBeneficiaries(rows: BeneficiaryInput[]): ApiBeneficiaryInput[] {
  return rows.map((row) => ({
    type: row.partyId ? 'PARTY' : 'FREEFORM',
    partyId: row.partyId,
    freeformDesignee: row.freeformDesignee ?? null,
    sharePercent: Number(row.sharePercentage),
    revocable: row.revocable ?? true,
  }));
}

/**
 * The inverse mapping, for seeding the form from the policy the backend already returned. Reads
 * the wire `revocable` field through unchanged (defaulting to `true` only when genuinely absent,
 * matching the OpenAPI schema's own `default: true`) so an existing irrevocable designation stays
 * irrevocable through an edit-and-save round trip.
 */
function fromWireBeneficiaries(rows: ApiBeneficiaryInput[] | undefined): BeneficiaryInput[] {
  if (!rows || rows.length === 0) {
    return [{ freeformDesignee: '', sharePercentage: '', revocable: true }];
  }
  return rows.map((row) => ({
    partyId: row.partyId ?? undefined,
    freeformDesignee: row.freeformDesignee ?? undefined,
    sharePercentage: String(row.sharePercent),
    revocable: row.revocable ?? true,
  }));
}

export default function PolicyDetailPage({
  params,
}: {
  params: Promise<{ policyNumber: string }>;
}) {
  const { policyNumber } = use(params);
  const queryClient = useQueryClient();
  const [saveMessage, setSaveMessage] = useState<string | null>(null);

  const policyQuery = useQuery({
    queryKey: ['policy', policyNumber],
    queryFn: () => fetchJson<PolicyView>(`/api/policies/${encodeURIComponent(policyNumber)}`),
  });
  const coverageQuery = useQuery({
    queryKey: ['policy', policyNumber, 'coverage-status'],
    queryFn: () =>
      fetchJson<CoverageStatusView>(`/api/policies/${encodeURIComponent(policyNumber)}/coverage-status`),
  });
  const surrenderQuery = useQuery({
    queryKey: ['policy', policyNumber, 'surrender-value'],
    queryFn: () =>
      fetchJson<SurrenderValueQuote>(`/api/policies/${encodeURIComponent(policyNumber)}/surrender-value`),
  });

  async function saveBeneficiaries(rows: BeneficiaryInput[]) {
    setSaveMessage(null);
    const response = await fetch(`/api/policies/${encodeURIComponent(policyNumber)}/beneficiaries`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(toWireBeneficiaries(rows)),
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new PortalFetchError(problem as ApiProblem | null, response.status);
    }
    setSaveMessage('Beneficiaries updated.');
    await queryClient.invalidateQueries({ queryKey: ['policy', policyNumber] });
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Policy {policyNumber}</h1>
        <nav className="flex gap-3 text-sm">
          <Link href={`/policies/${policyNumber}/billing`} className="underline underline-offset-2">
            Billing
          </Link>
          <Link href={`/policies/${policyNumber}/claims`} className="underline underline-offset-2">
            Claims
          </Link>
          <Link href={`/policies/${policyNumber}/loan`} className="underline underline-offset-2">
            Loan
          </Link>
        </nav>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>Policy details</CardTitle>
          {policyQuery.data?.status && <Badge>{policyQuery.data.status}</Badge>}
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          {policyQuery.isLoading && <Skeleton className="h-24" />}
          {policyQuery.isError && <p className="text-destructive">{errorMessage(policyQuery.error)}</p>}
          {policyQuery.data && (
            <>
              <div>Issue date: {policyQuery.data.issueDate}</div>
              {policyQuery.data.sumAssured && (
                <div>
                  Sum assured: <MoneyText {...policyQuery.data.sumAssured} />
                </div>
              )}
              {policyQuery.data.premium && (
                <div>
                  Premium: <MoneyText {...policyQuery.data.premium} />
                  {policyQuery.data.premiumFrequency ? ` / ${policyQuery.data.premiumFrequency.toLowerCase()}` : null}
                </div>
              )}
              {policyQuery.data.cashValue && (
                <div>
                  Cash value: <MoneyText {...policyQuery.data.cashValue} />
                </div>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Coverage status</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          {coverageQuery.isLoading && <Skeleton className="h-16" />}
          {coverageQuery.isError && <p className="text-destructive">{errorMessage(coverageQuery.error)}</p>}
          {coverageQuery.data &&
            ((coverageQuery.data.activeCoverages?.length ?? 0) === 0 ? (
              <p className="text-muted-foreground">No active coverages as of {coverageQuery.data.asOf}.</p>
            ) : (
              <ul className="space-y-1">
                {coverageQuery.data.activeCoverages?.map((coverage, index) => (
                  <li key={index}>
                    {coverage.benefitType}
                    {coverage.sumAssured ? (
                      <>
                        {' '}
                        — <MoneyText {...coverage.sumAssured} />
                      </>
                    ) : null}
                  </li>
                ))}
              </ul>
            ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Surrender value</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {surrenderQuery.isLoading && <Skeleton className="h-16" />}
          {surrenderQuery.isError && <p className="text-destructive">{errorMessage(surrenderQuery.error)}</p>}
          {surrenderQuery.data?.quotedValue && (
            <div>
              Current quote: <MoneyText {...surrenderQuery.data.quotedValue} />
              {surrenderQuery.data.quotedAt && (
                <span className="text-muted-foreground"> (as of {surrenderQuery.data.quotedAt})</span>
              )}
            </div>
          )}
          {/* Explanation panel only -- there is no button or form here that submits to
              POST /policies/{policyNumber}/surrender. That endpoint, and the process-status
              poll it would return, both return 501 today. */}
          <Alert>
            <AlertTitle>Surrender is not available online</AlertTitle>
            <AlertDescription>{SURRENDER_NOT_AVAILABLE}</AlertDescription>
          </Alert>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Beneficiaries</CardTitle>
        </CardHeader>
        <CardContent>
          {policyQuery.isLoading && <Skeleton className="h-24" />}
          {policyQuery.data && (
            <BeneficiaryForm
              key={policyQuery.dataUpdatedAt}
              initial={fromWireBeneficiaries(policyQuery.data.beneficiaries)}
              onSave={saveBeneficiaries}
            />
          )}
          {saveMessage && <p className="pt-2 text-sm text-muted-foreground">{saveMessage}</p>}
        </CardContent>
      </Card>
    </div>
  );
}
