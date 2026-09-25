import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canSeeFinance, readIdentity, staffRoles } from '@/auth/claims';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { RecoveriesPanel } from '@/features/reinsurance/RecoveriesPanel';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaimDetail, useClaimStore } from '@/store/claimStore';
import { AssessmentHistoryPanel } from './AssessmentHistoryPanel';
import { ClaimAssessmentPanel } from './ClaimAssessmentPanel';
import { ClaimPaymentPanel } from './ClaimPaymentPanel';
import { ClaimDetailsFields } from './ClaimDetailsFields';
import { ClaimReopenPanel } from './ClaimReopenPanel';
import { ClaimSettlementPanel } from './ClaimSettlementPanel';
import { EvidencePanel } from './EvidencePanel';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';

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
        /*
          Ordered by what the person who opened this page came to do, which is the one
          thing the previous layout did not express: an assessor arriving from the
          queue scrolled past Event details and Evidence to reach the form that is
          their entire job, and every panel heading was the same 14px, so nothing on
          screen said which box was the point.

          The record rail is now facts only, and pinned -- the claimant's name and the
          contestability flag stay on screen for the whole time the findings are being
          written. "Cannot tell whose death she is assessing without a second tab" was
          a real review finding, and a sticky column is the fix for it rather than
          another link.
        */
        <DetailLayout
          record={
            <>
              <Panel title="Claim">
                <dl className="px-4 pb-2">
                  {claim.approvedAmount && (
                    <Field label="Approved amount" value={formatMoney(claim.approvedAmount)} emphasis />
                  )}
                  <Field
                    label="Claimant"
                    value={
                      <Link to={`/staff/parties/${claim.claimantPartyId}`} className="underline">
                        <PartyName partyId={claim.claimantPartyId} />
                      </Link>
                    }
                  />
                  {/*
                    The note used to read "Re-derived on every read, not a stored
                    column", which is true and is a fact about the database, not
                    about this claim. A claims manager deciding a death claim needs
                    to know what the flag MEANS and how much weight to put on it.

                    And the weight is genuinely different for a group scheme: no
                    scheme carries an underwriting case id -- issueGroupScheme never
                    records one, not even for a scheme issued from a decided group
                    case -- so contestability cannot be measured at all there and
                    the check fails closed to "requires review". Every group claim
                    is therefore flagged, permanently. Saying so is the difference
                    between a flag somebody weighs and a flag everybody clicks past.
                  */}
                  <Field
                    label="Contestability"
                    value={claim.requiresContestabilityReview ? 'Requires review' : 'Clear'}
                    note={
                      claim.requiresContestabilityReview
                        ? claim.policyMemberId
                          ? 'Always flagged on a scheme — cover here is not individually underwritten, so the window cannot be measured'
                          : 'Inside the non-disclosure window, or the window could not be established'
                        : 'Outside the non-disclosure window on the date of event'
                    }
                  />
                </dl>
              </Panel>

              <Panel title="Event details">
                <dl className="px-4 pb-2">
                  <Field label="Date of event" value={formatDate(claim.dateOfEvent)} />
                  <ClaimDetailsFields details={claim.details} />
                </dl>
              </Panel>
            </>
          }
        >
          {/* Status-gated, so in practice one of the three renders at a time --
              REGISTERED/UNDER_ASSESSMENT for the first two, REJECTED/SETTLED for the
              last. Where an assessor-and-manager sees two, the subtitles say which is
              which; both genuinely are the point of the page. */}
          {canAssess && (
            <Panel
              emphasis
              title="Submit an assessment"
              subtitle="Claims may carry more than one before a decision is made."
            >
              <ClaimAssessmentPanel claimId={claimId} />
            </Panel>
          )}

          {canDecide && (
            <Panel
              emphasis
              title="Decide settlement"
              subtitle="Approve or reject -- distinct from assessing."
            >
              <ClaimSettlementPanel
                claimId={claimId}
                policyNumber={claim.policyNumber}
                onScheme={!!claim.policyMemberId}
              />
            </Panel>
          )}

          {canReopen && (
            <Panel emphasis title="Reopen">
              <ClaimReopenPanel claimId={claimId} wasSettled={claim.status === 'SETTLED'} />
            </Panel>
          )}

          {/* Above Evidence, and shown to assessors and managers alike. The manager
              deciding this claim did not assess it -- the platform forbids it -- so
              this is the only place their colleague's reasoning appears. It sits
              next to the decision form for that reason, not at the bottom with the
              attachments. */}
          {(roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER) && (
            <Panel
              title="Assessments"
              subtitle="What the assessors found, and what they recommended paying"
            >
              <AssessmentHistoryPanel claimId={claimId} />
            </Panel>
          )}

          {/* Once approved, where the money is. Shown to the claims staff who decided it and to
              finance who pay it; payee and rail are internal, so not to anybody else. */}
          {(roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER || roles.FINANCE_OFFICER) &&
            (claim.status === 'APPROVED' ||
              claim.status === 'SETTLEMENT_REQUESTED' ||
              claim.status === 'SETTLED' ||
              claim.status === 'REOPENED') && (
              <Panel title="Payment" subtitle="Where the settlement is, and who it is waiting on">
                <ClaimPaymentPanel claimId={claimId} claimStatus={claim.status} />
              </Panel>
            )}

          <Panel title="Evidence" subtitle="Photos, certificates, and reports attached to this claim">
            <EvidencePanel claimId={claimId} canAttach={claim.status !== 'SETTLED'} />
          </Panel>

          {/* Moved out of the 320px rail: a recoveries table needs the width, and
              nothing in it is a fact about the claim itself. */}
          {canSeeReinsurance && (
            <Panel title="Reinsurance" subtitle="Recoveries this claim's own settlement produced">
              <RecoveriesPanel claimId={claimId} />
            </Panel>
          )}
        </DetailLayout>
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

