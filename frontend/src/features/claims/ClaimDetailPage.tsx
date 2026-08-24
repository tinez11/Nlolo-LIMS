import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/AppShell';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaimDetail, useClaimStore } from '@/store/claimStore';
import { ClaimDetailsFields } from './ClaimDetailsFields';

/**
 * The "acts" half of drawer-previews-page-acts. No mutating action lives here
 * yet -- assessment, settlement decision, and reopen are staff-role-gated
 * workflows deliberately out of this slice's scope, not omitted by oversight.
 * Unlike Surrender on the Policy detail page, there is no disabled button for
 * them here: those are real, currently-501 backend paths worth surfacing as
 * blocked; these are simply not built in this console yet, and a disabled
 * button would misrepresent "not built" as "broken."
 */
export function ClaimDetailPage() {
  const { claimId = '' } = useParams();

  const detail = useClaimStore(selectClaimDetail(claimId));
  const loadDetail = useClaimStore((s) => s.loadDetail);

  useEffect(() => {
    if (claimId) void loadDetail(claimId);
  }, [claimId, loadDetail]);

  const claim = detail.data;

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading claim" />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={detail.error} onRetry={() => void loadDetail(claimId)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={claim?.claimType ?? 'Claim'}
        description={
          claim?.policyNumber ? (
            <span className="font-mono text-xs">policy {claim.policyNumber}</span>
          ) : undefined
        }
        actions={claim?.status && <StatusBadge kind="claim" value={claim.status} />}
      />

      {claim && (
        <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="space-y-5">
            <Panel title="Event details">
              <dl className="px-4 pb-2">
                <Field label="Date of event" value={formatDate(claim.dateOfEvent)} />
                <ClaimDetailsFields details={claim.details} />
              </dl>
            </Panel>
          </div>

          <div className="space-y-5">
            <Panel title="Claim">
              <dl className="px-4 pb-2">
                {claim.approvedAmount && (
                  <Field label="Approved amount" value={formatMoney(claim.approvedAmount)} emphasis />
                )}
                <Field
                  label="Claimant"
                  value={<span className="font-mono text-xs">{claim.claimantPartyId}</span>}
                  note="No party lookup endpoint exists yet"
                />
                <Field
                  label="Contestability"
                  value={claim.requiresContestabilityReview ? 'Requires review' : 'Clear'}
                  note="Re-derived on every read, not a stored column"
                />
              </dl>
            </Panel>
          </div>
        </div>
      )}
    </>
  );
}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to=".." relative="path">
        <ArrowLeft />
        All claims
      </Link>
    </Button>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
      </div>
      {children}
    </section>
  );
}
