import { can } from '@/auth/access';
import { useEffect } from 'react';
import { useBreadcrumb } from '@/lib/cameFrom';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { readIdentity, staffRoles } from '@/auth/claims';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { RecordTabs } from '@/components/RecordTabs';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { humanizeStatus } from '@/lib/status';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAssessments, selectClaimDetail, useClaimStore } from '@/store/claimStore';
import { ArrowRight, Check, Clock } from 'lucide-react';
import { cn } from '@/lib/cn';
import { claimNextStep } from './claimNextStep';
import { AccidentalDeathField } from './AccidentalDeathField';
import { ClaimDetailsFields } from './ClaimDetailsFields';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';
import { claimSections } from './claimSections';

/**
 * The "acts" half of drawer-previews-page-acts.
 *
 * Assessment/settlement-decision/reopen are gated on the current user's OWN
 * token roles (`staffRoles`), the same convenience-only decode `AppShell` uses
 * for nav -- the backend's `@PreAuthorize` remains the real authority, this
 * only avoids showing a staff user a form that would 403. A staff.underwriter
 * session (no CLAIMS_ASSESSOR/CLAIMS_MANAGER role) sees none of the panels
 * below; that is correct, not a missing feature.
 *
 * TABS, as the policy record has (2026-10-29, the user: the section bar repeated the panels stacked beneath it). It
 * used to be one scroll with a jump bar, so an assessor could read the evidence while writing the findings; the
 * claim's facts stay pinned in the record rail beside every tab, and Evidence is one click away. It opens on the
 * section the person came to act on -- assessing, deciding, reopening.
 */
export function ClaimDetailPage({ realm = 'staff' }: { realm?: 'staff' | 'agents' } = {}) {
  const { claimId = '' } = useParams();
  // Back to the client it was opened from, when it was (2026-10-20); otherwise the list it sits in.
  const breadcrumb = useBreadcrumb([{ label: 'Claims', to: `/${realm}/claims` }]);
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  // What the platform lets this person do (2026-10-29), not the roles their token happens to carry: an administrator
  // holds claims.assess and claims.settle on the platform with no claims role in Keycloak, and used to see a claim
  // with no way to act on it -- though the server would have let them.
  const roles = {
    ...staffRoles(identity),
    CLAIMS_ASSESSOR: can('claims.assess'),
    CLAIMS_MANAGER: can('claims.settle'),
  };
  const canSeeReinsurance = can('reinsurance.view');

  const detail = useClaimStore(selectClaimDetail(claimId));
  const loadDetail = useClaimStore((s) => s.loadDetail);

  useEffect(() => {
    if (claimId) void loadDetail(claimId);
  }, [claimId, loadDetail]);

  const claim = detail.data;
  // Whether this person assessed it -- then the decision is someone else's, and the page says so up front.
  const loadAssessments = useClaimStore((s) => s.loadAssessments);
  const assessments = useClaimStore(selectAssessments(claimId));
  const seesAssessments = roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER;
  useEffect(() => {
    if (claimId && seesAssessments) void loadAssessments(claimId);
  }, [claimId, seesAssessments, loadAssessments]);
  const viewerAssessed = (assessments.data ?? []).some((a) => a.assessor === identity?.subject);

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
      <>
        <PageHeader breadcrumb={breadcrumb} title="Claim" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadDetail(claimId)} />
        </div>
      </>
    );
  }

  const sections = claim
    ? claimSections({
        claim, claimId, roles, canAssess, canDecide, canReopen, canSeeReinsurance,
        canAttachEvidence: realm === 'staff',
      })
    : [];

  return (
    <>
      <PageHeader
        // The breadcrumb replaces the back link that used to sit in a strip ABOVE this bar:
        // a ghost button with an arrow, doing navigation's job while pushing the sticky bar
        // 44px down the screen on every claim. Realm-aware, because this page is mounted in
        // the agents console too and an absolute /staff crumb would walk an agent into a 403.
        breadcrumb={breadcrumb}
        title={claim ? humanizeStatus(claim.claimType) : 'Claim'}
        description={
          claim?.policyNumber ? (
            <span className="font-mono text-xs">policy {claim.policyNumber}</span>
          ) : undefined
        }
        // `status`, not `actions`: the badge is a fact about the record, and `actions` is
        // where a control goes. They are not interchangeable slots.
        status={claim?.status && <StatusBadge kind="claim" value={claim.status} />}
      />

      {claim && realm === 'staff' && <NextStep step={claimNextStep({
        claim, canAssess: roles.CLAIMS_ASSESSOR, canDecide: roles.CLAIMS_MANAGER, viewerAssessed,
      })} />}

      {claim && (
        /*
          The record rail is facts only, and pinned -- the claimant's name and the
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
                  {/* A funeral plan's claim names the life who died; whether it was an accident decides the waiting period. */}
                  {claim.coveredLifeId && (
                    <AccidentalDeathField claimId={claimId} accidental={claim.accidental === true}
                      canChange={(roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER)
                        && (claim.status === 'REGISTERED' || claim.status === 'UNDER_ASSESSMENT' || claim.status === 'REOPENED')}
                      onChanged={() => void loadDetail(claimId)} />
                  )}
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
          {/* One section is not a choice: a staff.underwriter sees only Evidence, so it gets no tab strip. */}
          {sections.length > 1 ? (
            <RecordTabs
              label="Claim sections"
              tabs={sections.map(({ id, label, content }) => ({ value: id, label, content }))}
            />
          ) : (
            sections[0]?.content
          )}
        </DetailLayout>
      )}
    </>
  );
}

/**
 * Who acts next, and whether that is you (2026-10-29): a claim that read only "Registered" left the person who
 * registered it not knowing an assessor and then a manager had to act. Tone, not colour alone: an arrow when it is
 * yours to do, a clock when it waits on someone else, a tick when it is finished.
 */
function NextStep({ step }: { step: { tone: 'act' | 'wait' | 'done'; text: string } }) {
  if (!step.text) return null;
  const Icon = step.tone === 'act' ? ArrowRight : step.tone === 'done' ? Check : Clock;
  return (
    <div className="px-6 pt-4">
      <p
        role="status"
        className={cn(
          'flex items-start gap-2 rounded-lg border px-4 py-2.5 text-sm',
          step.tone === 'act' ? 'border-border-strong bg-surface font-medium' : 'border-border bg-band text-muted-foreground',
        )}
      >
        <Icon aria-hidden className="mt-0.5 size-4 shrink-0" />
        {step.text}
      </p>
    </div>
  );
}
