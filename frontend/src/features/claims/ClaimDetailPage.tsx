import { Fragment, useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canSeeFinance, readIdentity, staffRoles } from '@/auth/claims';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { SectionNav } from '@/components/SectionNav';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { humanizeStatus } from '@/lib/status';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaimDetail, useClaimStore } from '@/store/claimStore';
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
 * ONE SCROLL, WITH A SECTION BAR -- not tabs, which is what the policy record got. The
 * difference is the job: a policy's registers are separate work done by separate people, so
 * hiding four of them costs nothing, while an assessor reads the evidence WHILE writing the
 * findings that cite it. Tabs would put the death certificate one click away from the form
 * quoting it. The bar only makes the fifth section a jump instead of a drag.
 */
export function ClaimDetailPage({ realm = 'staff' }: { realm?: 'staff' | 'agents' } = {}) {
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
      <>
        <PageHeader breadcrumb={[{ label: 'Claims', to: `/${realm}/claims` }]} title="Claim" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadDetail(claimId)} />
        </div>
      </>
    );
  }

  const sections = claim
    ? claimSections({ claim, claimId, roles, canAssess, canDecide, canReopen, canSeeReinsurance })
    : [];

  return (
    <>
      <PageHeader
        // The breadcrumb replaces the back link that used to sit in a strip ABOVE this bar:
        // a ghost button with an arrow, doing navigation's job while pushing the sticky bar
        // 44px down the screen on every claim. Realm-aware, because this page is mounted in
        // the agents console too and an absolute /staff crumb would walk an agent into a 403.
        breadcrumb={[{ label: 'Claims', to: `/${realm}/claims` }]}
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

      {/* One link is not a bar -- it jumps you where you already are. A staff.underwriter
          sees only Evidence, so they get no furniture for it. */}
      {sections.length > 1 && (
        <SectionNav
          label="Claim sections"
          sections={sections.map(({ id, label }) => ({ id, label }))}
        />
      )}

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
          {sections.map((section) => (
            <Fragment key={section.id}>{section.content}</Fragment>
          ))}
        </DetailLayout>
      )}
    </>
  );
}