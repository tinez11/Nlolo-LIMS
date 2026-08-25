import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canSeeFinance, readIdentity, staffRoles } from '@/auth/claims';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/AppShell';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { RecoveriesPanel } from '@/features/reinsurance/RecoveriesPanel';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaimDetail, useClaimStore } from '@/store/claimStore';
import { ClaimAssessmentPanel } from './ClaimAssessmentPanel';
import { ClaimDetailsFields } from './ClaimDetailsFields';
import { ClaimReopenPanel } from './ClaimReopenPanel';
import { ClaimSettlementPanel } from './ClaimSettlementPanel';
import { EvidencePanel } from './EvidencePanel';

/**
 * The "acts" half of drawer-previews-page-acts.
 *
 * Assessment/settlement-decision/reopen are gated on the current user's OWN
 * token roles (`staffRoles`), the same convenience-only decode `AppShell` uses
 * for nav -- the backend's `@PreAuthorize` remains the real authority, this
 * only avoids showing a staff user a form that would 403. A staff.underwriter
 * session (no CLAIMS_ASSESSOR/CLAIMS_MANAGER role) sees none of the panels
 * below; that is correct, not a missing feature.
 */
export function ClaimDetailPage() {
  const { claimId = '' } = useParams();
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const roles = staffRoles(identity);
  const canSeeReinsurance = canSeeFinance(identity);

  const detail = useClaimStore(selectClaimDetail(claimId));
  const loadDetail = useClaimStore((s) => s.loadDetail);

  useEffect(() => {
    if (claimId) void loadDetail(claimId);
  }, [claimId, loadDetail]);

  const claim = detail.data;

  const canAssess =
    roles.CLAIMS_ASSESSOR &&
    !!claim &&
    (claim.status === 'REGISTERED' || claim.status === 'REOPENED' || claim.status === 'UNDER_ASSESSMENT');

  // MATURITY auto-approves straight from REGISTERED with no assessment at all
  // (Claim.approve()'s own doc, Cl3) -- every other claim type needs
  // UNDER_ASSESSMENT first.
  const canDecide =
    roles.CLAIMS_MANAGER &&
    !!claim &&
    (claim.status === 'UNDER_ASSESSMENT' || (claim.claimType === 'MATURITY' && claim.status === 'REGISTERED'));

  const canReopen =
    roles.CLAIMS_MANAGER && !!claim && (claim.status === 'REJECTED' || claim.status === 'SETTLED');

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

            <Panel title="Evidence" subtitle="Photos, certificates, and reports attached to this claim">
              <EvidencePanel claimId={claimId} canAttach={claim.status !== 'SETTLED'} />
            </Panel>

            {canAssess && (
              <Panel
                title="Submit an assessment"
                subtitle="Claims may carry more than one before a decision is made."
              >
                <ClaimAssessmentPanel claimId={claimId} />
              </Panel>
            )}

            {canDecide && (
              <Panel title="Decide settlement" subtitle="Approve or reject -- distinct from assessing.">
                <ClaimSettlementPanel claimId={claimId} />
              </Panel>
            )}

            {canReopen && (
              <Panel title="Reopen">
                <ClaimReopenPanel claimId={claimId} wasSettled={claim.status === 'SETTLED'} />
              </Panel>
            )}
          </div>

          <div className="space-y-5">
            <Panel title="Claim">
              <dl className="px-4 pb-2">
                {claim.approvedAmount && (
                  <Field label="Approved amount" value={formatMoney(claim.approvedAmount)} emphasis />
                )}
                <Field
                  label="Claimant"
                  value={
                    <Link to={`/staff/parties/${claim.claimantPartyId}`} className="font-mono text-xs underline">
                      {claim.claimantPartyId}
                    </Link>
                  }
                />
                <Field
                  label="Contestability"
                  value={claim.requiresContestabilityReview ? 'Requires review' : 'Clear'}
                  note="Re-derived on every read, not a stored column"
                />
              </dl>
            </Panel>

            {canSeeReinsurance && (
              <Panel title="Reinsurance" subtitle="Recoveries this claim's own settlement produced">
                <RecoveriesPanel claimId={claimId} />
              </Panel>
            )}
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

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
