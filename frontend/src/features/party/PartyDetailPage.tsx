import { ArrowLeft, Check, FileCheck, X } from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useDropzone } from 'react-dropzone';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatDate, formatInstant } from '@/lib/dates';
import { partyTypeLabel } from '@/lib/partyType';
import type { ApiError } from '@/lib/apiError';
import type {
  AgentView,
  BeneficiaryOfView,
  ClaimView,
  IdentityDocumentView,
  KycStatus,
  Page,
  PartyDetailView,
  PartyDocumentView,
  PolicyView,
  UnderwritingCaseView,
} from '@/api/types';
import { isInitialLoad, type Resource } from '@/store/createResourceSlice';
import {
  SCHEME_MEMBER_PREVIEW,
  selectClientAgentRecords,
  selectClientBeneficiaryOf,
  selectClientCases,
  selectClientClaims,
  selectClientPolicies,
  isSchemeCategory,
  selectClientSchemeKinds,
  type SchemeCheck,
  selectClientSchemeMembers,
  useClientRecordStore,
} from '@/store/clientRecord';
import {
  selectParty,
  selectPartyDocuments,
  selectSubmittingKyc,
  selectUploadingKycEvidence,
  usePartyStore,
} from '@/store/partyStore';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';

const ACCEPTED_EVIDENCE_TYPES = {
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/png': ['.png'],
  'application/pdf': ['.pdf'],
};

/**
 * One client's whole record: who they are, what they hold, and what is outstanding.
 *
 * Reached from the Clients register, or by drilling in from any partyId already on
 * screen (a policy's policyholderPartyId, a claim's claimantPartyId, an
 * underwriting case's applicantPartyId, an agent's own partyId).
 *
 * SEVEN PANELS, ALL LOADED IN PARALLEL, each keeping its own status. Someone opening
 * a client record is answering "what is going on with this person", which needs the
 * panels populated -- lazy sections behind disclosure triangles would make them click
 * five times to find out. Independent statuses matter more than the parallelism: an
 * underwriting service that is down should cost the reader that one panel, not blank
 * the contact details beside it.
 *
 * The page ACTS on KYC and only on KYC. An underwriting decision or a claim
 * assessment needs the sum assured, the product and the rules-engine output on screen
 * to be made responsibly, so those panels link into the screens that own them rather
 * than offering a Decline button next to a phone number. It also keeps role-gating
 * honest: a KYC reviewer is not an underwriter.
 *
 * `realm` changes only what the policies panel is called. An agents-realm caller's
 * policies and claims are scoped to their hierarchy team's BOOK, while this register
 * is built on who they REGISTERED -- so a client they registered whose policy another
 * agent wrote shows an empty panel. "Your Policies" says that; "Policies" would imply
 * the client has none.
 */
export function PartyDetailPage({ realm = 'staff' }: { realm?: 'staff' | 'agents' } = {}) {
  const { partyId = '' } = useParams();
  const navigate = useNavigate();
  const detail = usePartyStore(selectParty(partyId));
  const loadParty = usePartyStore((s) => s.loadParty);
  const documents = usePartyStore(selectPartyDocuments(partyId));
  const loadPartyDocuments = usePartyStore((s) => s.loadPartyDocuments);

  const policies = useClientRecordStore(selectClientPolicies(partyId));
  const claims = useClientRecordStore(selectClientClaims(partyId));
  const cases = useClientRecordStore(selectClientCases(partyId));
  const beneficiaryOf = useClientRecordStore(selectClientBeneficiaryOf(partyId));
  const agentRecords = useClientRecordStore(selectClientAgentRecords(partyId));
  const loadPolicies = useClientRecordStore((s) => s.loadPolicies);
  const loadClaims = useClientRecordStore((s) => s.loadClaims);
  const loadCases = useClientRecordStore((s) => s.loadCases);
  const loadBeneficiaryOf = useClientRecordStore((s) => s.loadBeneficiaryOf);
  const loadAgentRecords = useClientRecordStore((s) => s.loadAgentRecords);
  const loadSchemeMembers = useClientRecordStore((s) => s.loadSchemeMembers);

  const isStaff = realm === 'staff';

  useEffect(() => {
    if (!partyId) return;
    // Fired together, not awaited in sequence: seven reads that do not depend on one
    // another, so serialising them would multiply the wait for no gain.
    void loadParty(partyId);
    void loadPartyDocuments(partyId);
    void loadPolicies(partyId);
    void loadClaims(partyId);
    void loadCases(partyId);
    void loadBeneficiaryOf(partyId);
    // `GET /agents` is staff-only, so asking as an agent would be a guaranteed 403.
    if (isStaff) void loadAgentRecords(partyId);
  }, [
    partyId,
    isStaff,
    loadParty,
    loadPartyDocuments,
    loadPolicies,
    loadClaims,
    loadCases,
    loadBeneficiaryOf,
    loadAgentRecords,
  ]);

  const party = detail.data;

  /*
   * Whether this record is a company or a group rather than a person. A corporate with
   * no scheme yet still shows the panel, empty, so a reviewer can see there is none --
   * an absent panel and "no schemes" are different statements.
   */
  const isOrganisation = party?.partyType === 'CORPORATE' || party?.partyType === 'GROUP';

  // CANDIDATES from the policies panel's own page, by category -- group life AND credit life,
  // since a lender's credit-life book is a scheme too and used to be invisible here. Then
  // CONFIRMED one by one: a group product was once proposable as a single life, and a policy
  // issued that way has the category and no schedule, so offering its "member roll" could only
  // fail. `useMemo` keeps identities stable so the effects below do not re-fire every render.
  const candidates = useMemo(
    () => (policies.data?.items ?? []).filter((p) => isSchemeCategory(p.productCategory)),
    [policies.data],
  );
  const candidateKey = candidates.map((p) => p.policyNumber ?? '').join(',');
  const schemeKinds = useClientRecordStore(selectClientSchemeKinds(partyId));
  const loadSchemeKinds = useClientRecordStore((s) => s.loadSchemeKinds);
  useEffect(() => {
    if (partyId && candidateKey) void loadSchemeKinds(partyId, candidateKey.split(','));
  }, [partyId, candidateKey, loadSchemeKinds]);

  const kinds = schemeKinds.data;
  // Until the check lands every candidate counts as a scheme, so the panel does not flicker
  // empty; an UNKNOWN (null) stays a scheme, and only the server's "not a group scheme" moves one.
  const schemes = useMemo(
    () => candidates.filter((p) => kinds?.[p.policyNumber ?? '']?.isScheme !== false),
    [candidates, kinds],
  );
  const notSchemes = useMemo(
    () => candidates.filter((p) => kinds?.[p.policyNumber ?? '']?.isScheme === false),
    [candidates, kinds],
  );
  // An employer's scheme and a lender's are shown differently. An employer's members are its
  // people, and previewing them is the "who does this company cover" answer. A lender's are
  // BORROWERS -- the lender's customers, not ours, deliberately kept out of the client register
  // until a claim (credit-life design §2.2) -- so the lender's record names the scheme and how
  // many lives it covers, and the borrowers stay on the scheme's own page.
  const employerSchemes = useMemo(() => schemes.filter((p) => p.productCategory !== 'CREDIT_LIFE'), [schemes]);
  const creditLifeSchemes = useMemo(() => schemes.filter((p) => p.productCategory === 'CREDIT_LIFE'), [schemes]);
  // Only once confirmed, so the roll is never requested for a policy that cannot have one.
  const soleSchemeNumber =
    kinds && employerSchemes.length === 1 ? employerSchemes[0]?.policyNumber : undefined;
  const lenderOnly = creditLifeSchemes.length > 0 && employerSchemes.length === 0;

  useEffect(() => {
    // Only for the single-scheme case: with two or more there is no one roll to
    // preview, and fetching every roll to show five rows of each would be a request
    // per scheme for something nobody asked to see.
    if (partyId && soleSchemeNumber) void loadSchemeMembers(partyId, soleSchemeNumber);
  }, [partyId, soleSchemeNumber, loadSchemeMembers]);

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading client" />;
  }

  // Only the identity read blocks the page. A 403 here is a real outcome for an
  // agents-realm caller who reached a client they did not register, so the error
  // panel has to be reachable rather than assumed impossible.
  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink onClick={() => navigate(-1)} />
        <ErrorPanel error={detail.error} onRetry={() => void loadParty(partyId)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink onClick={() => navigate(-1)} />
      </div>

      <PageHeader
        title={party?.displayName ?? 'Client'}
        description={party?.partyType ? <span>{partyTypeLabel(party.partyType)}</span> : undefined}
        actions={
          <span className="flex items-center gap-2">
            {party?.kycStatus && <StatusBadge kind="kyc" value={party.kycStatus} />}
            {/*
              Staff only, and absent entirely in the agents realm rather than present and
              refused: an agent who can see the button reasonably concludes the record is
              theirs to change, and finds out otherwise at the point of saving.
            */}
            {realm === 'staff' && party && (
              <Link
                className="rounded-md border border-border px-2.5 py-1 text-xs hover:bg-hover"
                to={`/staff/parties/${partyId}/edit`}
              >
                Correct details
              </Link>
            )}
          </span>
        }
      />

      {/*
        KYC leads, because this page acts on KYC and only on KYC -- and it used to be
        the LAST of five panels, under four read-only registers. A reviewer whose
        whole task is deciding an identity had to scroll past the client's policies,
        claims, underwriting cases and beneficiary interests to reach the one control
        they came for.

        The rail is the identity itself: who this person is, the underwriting facts a
        premium is rated on, and where they live. Pinned, so the name and date of
        birth stay beside the evidence being judged against them.
      */}
      <DetailLayout
        record={
          <>
            <Panel title="Identity">
              {party && (
                <dl className="px-4 pb-2">
                  <Field label="Type" value={partyTypeLabel(party.partyType)} />
                  <Field label="Date of birth" value={formatDate(party.dateOfBirth) || '—'} />
                  <Field label="Registration no." value={party.registrationNumber ?? '—'} />
                  <Field label="Phone" value={party.phoneNumber ?? '—'} />
                  <Field label="Email" value={party.email ?? '—'} />
                  {party.identityDocument?.type && (
                    <Field
                      label={ID_TYPE_LABELS[party.identityDocument.type]}
                      value={
                        <span className="font-mono text-xs select-all">
                          {party.identityDocument.number ?? '—'}
                        </span>
                      }
                    />
                  )}
                  <Field label="Nationality" value={party.nationality ?? '—'} />
                  <Field
                    label="KYC decided"
                    value={party.kycVerifiedAt ? formatInstant(party.kycVerifiedAt) : '—'}
                    {...(party.kycVerifiedAt ? {} : { note: 'No decision recorded yet.' })}
                  />
                  <Field label="Registered" value={formatInstant(party.createdAt) || '—'} />
                  {/*
                    ONE ROW, NEVER A LOGIN ID. `createdBy` is a Keycloak subject -- a uuid
                    nothing on this platform can name -- and it used to be printed raw here,
                    beside a second "Introduced by" row that named the same agent. An agent
                    registration links to the agent, who is also who commission accrues to;
                    anyone else is named from the token at registration (party V5), and an
                    older record that could not be named exactly says so rather than guessing.
                  */}
                  {party.registeredByPartyId ? (
                    <Field
                      label="Registered by"
                      value={
                        <span>
                          <Link
                            className="hover:underline"
                            to={`/staff/parties/${party.registeredByPartyId}`}
                          >
                            {party.createdByName ?? <PartyName partyId={party.registeredByPartyId} />}
                          </Link>
                          <span className="text-muted-foreground"> · agent</span>
                        </span>
                      }
                      note="The agent who brought this client in. Commission on their policies accrues to this agent."
                    />
                  ) : (
                    <Field
                      label="Registered by"
                      value={party.createdByName ?? 'Name not recorded'}
                      note={
                        party.createdByName
                          ? 'Who entered this record. No agent introduced this client, so their business is direct.'
                          : 'Registered before names were kept. No agent introduced this client.'
                      }
                    />
                  )}
                  <Field label="Client id" value={<span className="font-mono text-xs">{partyId}</span>} />
                </dl>
              )}
            </Panel>

            {/* Rendered only for individuals, and only when something is on record.
                A corporate has none of this, and an empty panel of eight em dashes
                would read as data the platform lost rather than never asked for. */}
            {party?.partyType === 'INDIVIDUAL' && hasPersonRecord(party) && (
              <Panel
                title="Person"
                subtitle="Underwriting facts. Sex, smoker status and date of birth are what a premium is rated on."
              >
                <dl className="px-4 pb-2">
                  <Field label="Sex" value={party.sex ? SEX_LABELS[party.sex] : '—'} />
                  <Field
                    label="Smoker status"
                    value={party.smokerStatus ? SMOKER_LABELS[party.smokerStatus] : '—'}
                    // The distinction the column exists to preserve: UNKNOWN is a
                    // recorded answer a product may price; absent means nobody asked.
                    {...(party.smokerStatus ? {} : { note: 'Never asked — not the same as declined.' })}
                  />
                  <Field label="Occupation" value={party.occupation ?? '—'} />
                  <Field
                    label="Occupation class"
                    value={party.occupationClass ?? '—'}
                    {...(party.occupationClass
                      ? {}
                      : { note: 'Unclassified — a quote cannot apply an occupation loading.' })}
                  />
                  <Field label="Employer" value={party.employerName ?? '—'} />
                </dl>
              </Panel>
            )}

            {party?.partyType === 'INDIVIDUAL' && hasAddress(party) && (
              <Panel title="Address">
                <dl className="px-4 pb-2">
                  <Field label="Street or plot" value={party.address?.line ?? '—'} />
                  <Field label="Ward" value={party.address?.ward ?? '—'} />
                  <Field label="District" value={party.address?.district ?? '—'} />
                  <Field label="Region" value={party.address?.region ?? '—'} />
                  <Field label="Postal code" value={party.address?.postalCode ?? '—'} />
                </dl>
              </Panel>
            )}
          </>
        }
      >
        {isStaff && (
          <Panel
            emphasis
            title="KYC verification"
            subtitle="Upload evidence, then record a decision against it."
          >
            {party && <KycPanel partyId={partyId} currentStatus={party.kycStatus} />}
          </Panel>
        )}

        <Panel
          title={isStaff ? 'Policies' : 'Your Policies'}
          subtitle={
            isStaff
              ? 'Policies this client holds.'
              : 'Policies in your book. A policy written by another agent will not appear here.'
          }
        >
          <PolicyList
            resource={policies}
            onRetry={() => void loadPolicies(partyId)}
            partyId={partyId}
          />
        </Panel>

        {/*
          The company-to-its-members hop, which is what the client register's second
          area exists to serve.

          On this platform a member belongs to a CONTRACT, not to a company: a group
          scheme's lives are its insured schedule, and a company with three schemes has
          three rolls. So this panel lists the client's schemes and, where there is
          exactly one, previews that roll inline -- the common case, and the one where
          "show me this company's members" has a single unambiguous answer.

          Each row links STRAIGHT to the schedule, not to the policy record that owns
          it. Reaching a member list used to mean Clients -> the company -> Policies ->
          the GRP policy -> the policy page -> Member schedule; that is four hops of
          knowing that "members" live under a contract, and this is the shortcut for
          somebody who already knows they want the roll.

          Derived from the policies panel's own already-loaded page -- no extra request.
          `productCategory` has been on `PolicyView` since M3, so a scheme is
          identifiable from a list row without reading each policy back.
        */}
        {/*
          Shown for an organisation ALWAYS, and for anyone who actually holds a scheme.
          Not gated on party type alone: nothing server-side requires a scheme's employer
          to be a corporate, and the seeded schemes in the dev tenant are in fact held by
          an individual -- so a type-only gate would have hidden real cover from the
          record of the person who holds it.
        */}
        {(isOrganisation || candidates.length > 0) && (
          <Panel
            title={lenderOnly ? 'Credit-life schemes' : 'Group schemes'}
            subtitle={
              lenderOnly
                ? "Schemes this lender holds. Its borrowers are listed on each scheme's own page."
                : employerSchemes.length === 1
                  ? 'The scheme this client holds, and who is covered under it.'
                  : 'Master policies this client holds. Open one for its member schedule.'
            }
          >
            <SchemeList
              resource={policies}
              schemes={employerSchemes}
              creditLifeSchemes={creditLifeSchemes}
              lives={kinds}
              notSchemes={notSchemes}
              partyId={partyId}
              onRetry={() => void loadPolicies(partyId)}
            />
          </Panel>
        )}

        <Panel title="Claims" subtitle="Claims this client has made.">
          <ClaimList resource={claims} onRetry={() => void loadClaims(partyId)} />
        </Panel>

        <Panel
          title="Underwriting"
          subtitle="Applications assessed for this client. Open one to decide it."
        >
          <CaseList resource={cases} onRetry={() => void loadCases(partyId)} />
        </Panel>

        <Panel
          title="Named as beneficiary"
          subtitle="Policies that would pay out to this client — not policies they own."
        >
          <BeneficiaryOfList
            resource={beneficiaryOf}
            onRetry={() => void loadBeneficiaryOf(partyId)}
          />
        </Panel>

        {/* Both moved out of the rail, which now holds only the identity itself.
            These two are registers of things filed against the client, which is what
            every panel in this column is -- and a filename truncated inside 320px was
            losing the part that distinguishes one scan from another. */}
        <Panel
          title="Documents"
          subtitle="Filed against this client. Claim evidence lives on the claim."
        >
          <DocumentList
            resource={documents}
            onRetry={() => void loadPartyDocuments(partyId)}
          />
        </Panel>

        {isStaff && (
          <Panel title="Also an agent" subtitle="Whether this client sells for us as well.">
            <AgentRecordList
              resource={agentRecords}
              onRetry={() => void loadAgentRecords(partyId)}
            />
          </Panel>
        )}
      </DetailLayout>
    </>
  );
}

/**
 * Every panel below renders the same four states -- loading, failed, empty, populated
 * -- because each read can be in a different one at the same time. `PanelState`
 * carries the first three so no panel has to reinvent them, and so an empty panel
 * always SAYS it is empty rather than rendering nothing and looking broken.
 */
function PanelState({
  resource,
  onRetry,
  empty,
  children,
}: {
  resource: { status: string; error?: ApiError | null; data: unknown };
  onRetry: () => void;
  empty: string;
  children: () => ReactNode;
}) {
  if (resource.status === 'idle' || (resource.status === 'loading' && resource.data === null)) {
    return <p className="px-4 pb-4 pt-1 text-xs text-muted-foreground">Loading…</p>;
  }
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={onRetry} />;
  }
  const rendered = children();
  if (rendered === null) {
    return <p className="px-4 pb-4 pt-1 text-xs text-muted-foreground">{empty}</p>;
  }
  return <>{rendered}</>;
}

/**
 * How many rows a panel shows before it stops and says how many it is hiding.
 *
 * Not a hypothetical limit: a seeded client in the dev tenant already holds fifty
 * policies, and an uncapped panel pushed Claims, Underwriting and everything below it
 * clean off the screen -- the register stopped being an overview of the client and
 * became a policy list with some panels rumoured to exist underneath. Six is enough to
 * see the shape of someone's holdings; the full list is one click away on the screen
 * that owns it.
 */
/**
 * Backend literals rendered as English. Kept as explicit maps rather than a generic
 * humaniser so a literal added server-side fails the typecheck here instead of
 * silently rendering as `DRIVING_LICENCE`.
 */
const ID_TYPE_LABELS: Record<NonNullable<IdentityDocumentView['type']>, string> = {
  NATIONAL_ID: 'National ID',
  PASSPORT: 'Passport',
  DRIVING_LICENCE: 'Driving licence',
  VOTER_ID: 'Voter ID',
};

const SEX_LABELS: Record<NonNullable<PartyDetailView['sex']>, string> = {
  FEMALE: 'Female',
  MALE: 'Male',
};

const SMOKER_LABELS: Record<NonNullable<PartyDetailView['smokerStatus']>, string> = {
  SMOKER: 'Smoker',
  NON_SMOKER: 'Non-smoker',
  // Not "Unknown": the value means the question was put and the applicant declined,
  // which is a recorded answer a product may price. An absent value is the unknown.
  UNKNOWN: 'Declined to say',
};

/** Whether any V2 person fact is on record, so the panel is not eight em dashes. */
function hasPersonRecord(party: PartyDetailView): boolean {
  return Boolean(
    party.sex ?? party.smokerStatus ?? party.occupation ?? party.occupationClass ?? party.employerName,
  );
}

function hasAddress(party: PartyDetailView): boolean {
  const address = party.address;
  if (!address) return false;
  return Boolean(address.line ?? address.ward ?? address.district ?? address.region ?? address.postalCode);
}

const PANEL_ROW_CAP = 6;

/**
 * The line under a capped panel. Says the true total and where the rest live, rather
 * than trailing off after six rows and letting the reader assume that is all there is.
 */
function MoreRow({ hidden, to, label }: { hidden: number; to?: string; label?: string }) {
  if (hidden <= 0) return null;
  return (
    <div className="flex items-baseline justify-between gap-3 border-t border-border px-4 py-2 text-xs">
      <span className="text-muted-foreground">
        {hidden} more not shown
      </span>
      {to && (
        <Link to={to} relative="path" className="font-medium underline underline-offset-2">
          {label}
        </Link>
      )}
    </div>
  );
}

/** A row that links somewhere, used by four of the panels. */
function LinkRow({ to, left, right }: { to: string; left: ReactNode; right: ReactNode }) {
  return (
    <Link
      to={to}
      relative="path"
      className="flex items-baseline justify-between gap-3 px-4 py-2 text-xs hover:bg-hover"
    >
      <span className="min-w-0 truncate">{left}</span>
      <span className="shrink-0 text-muted-foreground">{right}</span>
    </Link>
  );
}

function PolicyList({
  resource,
  onRetry,
  partyId,
}: {
  resource: Resource<Page<PolicyView>>;
  onRetry: () => void;
  partyId: string;
}) {
  return (
    <PanelState resource={resource} onRetry={onRetry} empty="No policies.">
      {() => {
        const rows = resource.data?.items ?? [];
        if (rows.length === 0) return null;
        // The page total, not the length of what was fetched: the panel asks for one
        // page, so "50 more" must come from the server's count rather than from how
        // many rows happen to be in hand.
        const total = resource.data?.page.totalElements ?? rows.length;
        return (
          <div className="border-t border-border pb-1">
            {rows.slice(0, PANEL_ROW_CAP).map((p) => (
              <LinkRow
                key={p.policyNumber}
                to={`../../policies/${p.policyNumber}`}
                left={<span className="font-mono">{p.policyNumber}</span>}
                right={p.status ? <StatusBadge kind="policy" value={p.status} /> : '—'}
              />
            ))}
            <MoreRow
              hidden={total - Math.min(rows.length, PANEL_ROW_CAP)}
              to={`../../policies?policyholderPartyId=${encodeURIComponent(partyId)}`}
              label="View all"
            />
          </div>
        );
      }}
    </PanelState>
  );
}

/**
 * The client's group schemes, and -- where there is exactly one -- who is covered under
 * it.
 *
 * Takes the POLICIES resource for its loading/failed/empty states, because that is the
 * read the schemes were derived from: a failure here is a failure to load the policies,
 * and inventing a second error state for the same request would tell the reader there
 * were two.
 */
function SchemeList({
  resource,
  schemes,
  creditLifeSchemes,
  lives,
  notSchemes,
  partyId,
  onRetry,
}: {
  resource: Resource<Page<PolicyView>>;
  /** Employer schemes: the one-scheme case previews its members. */
  schemes: PolicyView[];
  /** A lender's schemes: named and counted, never listed borrower by borrower. */
  creditLifeSchemes: PolicyView[];
  /** The scheme check per policy number, for the credit-life lives-on-cover count. */
  lives: Record<string, SchemeCheck> | null | undefined;
  /** Group/credit-life policies the server says carry no scheme -- issued as a single life. */
  notSchemes: PolicyView[];
  partyId: string;
  onRetry: () => void;
}) {
  const members = useClientRecordStore(selectClientSchemeMembers(partyId));

  return (
    <PanelState resource={resource} onRetry={onRetry} empty="No group scheme on this client.">
      {() => {
        if (schemes.length === 0 && creditLifeSchemes.length === 0 && notSchemes.length === 0) {
          return <p className="px-4 py-3 text-xs text-muted-foreground">No group scheme on this client.</p>;
        }
        if (schemes.length === 0) {
          return (
            <>
              <CreditLifeRows schemes={creditLifeSchemes} lives={lives} />
              <NotSchemeRows policies={notSchemes} />
            </>
          );
        }

        const sole = schemes.length === 1 ? schemes[0] : undefined;
        const soleNumber = sole?.policyNumber;
        const total = members.data?.page.totalElements ?? null;
        const rows = members.data?.items ?? [];

        return (
          <div className="border-t border-border pb-1">
            {schemes.map((scheme) => (
              <LinkRow
                key={scheme.policyNumber}
                // Straight to the schedule, not to the policy record that owns it.
                to={`../../group-schemes/${encodeURIComponent(scheme.policyNumber ?? '')}`}
                left={<span className="font-mono">{scheme.policyNumber}</span>}
                right={
                  <>
                    {scheme.policyNumber === soleNumber && total !== null && (
                      <span className="mr-2 tabular-nums">
                        {total.toLocaleString()} {total === 1 ? 'member' : 'members'}
                      </span>
                    )}
                    {scheme.status ? <StatusBadge kind="policy" value={scheme.status} /> : '—'}
                  </>
                }
              />
            ))}

            {/*
              The lives themselves, for the single-scheme case. Read-only: joining and
              exiting a member changes what a contract covers, so it stays on the
              schedule's own screen rather than becoming an action on somebody's
              client record.
            */}
            {sole && (
              <div className="border-t border-border">
                <p className="px-4 pt-2 text-[11px] font-medium tracking-wide text-subtle-foreground uppercase">
                  Group members
                </p>
                {members.status === 'error' && members.error && members.data === null ? (
                  // Deliberately not an ErrorPanel: the schemes above loaded fine, and a
                  // full error block here would read as the whole panel having failed.
                  <p className="px-4 pt-1 pb-3 text-xs text-muted-foreground">
                    Could not load the member schedule.
                    {members.error.traceId && (
                      <span className="ml-1 font-mono select-all">({members.error.traceId})</span>
                    )}
                  </p>
                ) : rows.length === 0 ? (
                  <p className="px-4 pt-1 pb-3 text-xs text-muted-foreground">
                    {isInitialLoad(members) ? 'Loading…' : 'This scheme has no members on record.'}
                  </p>
                ) : (
                  <>
                    <ul className="px-4 pt-1 pb-1">
                      {rows.map((m) => (
                        <li
                          key={m.policyMemberId}
                          className="flex items-baseline justify-between gap-3 py-1 text-xs"
                        >
                          <span className="min-w-0 truncate">
                            {/* A FREEFORM member has a name and no client record; resolving
                                a party id it does not have rendered an empty row. The scheme
                                page already does this (GroupSchemePage). */}
                            {m.memberPartyId ? (
                              <PartyName partyId={m.memberPartyId} />
                            ) : (
                              (m.memberName ?? '—')
                            )}
                          </span>
                          <span className="shrink-0 text-muted-foreground">
                            {m.gradeCode && <span className="mr-2">{m.gradeCode}</span>}
                            {m.status ? <StatusBadge kind="member" value={m.status} /> : '—'}
                          </span>
                        </li>
                      ))}
                    </ul>
                    {/*
                      The true remainder from the server's own count, not
                      `rows.length` arithmetic -- this panel asked for five, so "N more"
                      has to come from the total the response carried.
                    */}
                    <MoreRow
                      hidden={(total ?? rows.length) - Math.min(rows.length, SCHEME_MEMBER_PREVIEW)}
                      to={`../../group-schemes/${encodeURIComponent(soleNumber ?? '')}`}
                      label="View the schedule"
                    />
                  </>
                )}
              </div>
            )}
            <CreditLifeRows schemes={creditLifeSchemes} lives={lives} />
            <NotSchemeRows policies={notSchemes} />
          </div>
        );
      }}
    </PanelState>
  );
}

/**
 * A lender's credit-life schemes: one row each, lives on cover and status, linking to the
 * scheme's own page. No borrower is named here. They are the lender's customers, held as
 * FREEFORM members precisely so they never enter our client register until a claim, and
 * listing them on the lender's record would put them in it by the back door.
 */
function CreditLifeRows({
  schemes,
  lives,
}: {
  schemes: PolicyView[];
  lives: Record<string, SchemeCheck> | null | undefined;
}) {
  if (schemes.length === 0) return null;
  return (
    <div className="border-t border-border pb-1">
      {schemes.map((scheme) => {
        const onCover = lives?.[scheme.policyNumber ?? '']?.activeMemberCount ?? null;
        return (
          <LinkRow
            key={scheme.policyNumber}
            to={`../../credit-life-schemes/${encodeURIComponent(scheme.policyNumber ?? '')}`}
            left={<span className="font-mono">{scheme.policyNumber}</span>}
            right={
              <>
                {onCover !== null && (
                  <span className="mr-2 tabular-nums">{onCover.toLocaleString()} on cover</span>
                )}
                {scheme.status ? <StatusBadge kind="policy" value={scheme.status} /> : '—'}
              </>
            }
          />
        );
      })}
    </div>
  );
}

/**
 * A group or credit-life policy that is not a scheme: issued down the single-life path, so it
 * has the product and no member schedule, and covers nobody. Said plainly rather than hidden
 * -- it is a real contract on the client's record, and the fix is someone's to make -- and
 * linked to the POLICY, since there is no schedule to link to.
 */
function NotSchemeRows({ policies }: { policies: PolicyView[] }) {
  if (policies.length === 0) return null;
  return (
    <div className="border-t border-border">
      {policies.map((p) => (
        <LinkRow
          key={p.policyNumber}
          to={`../../policies/${encodeURIComponent(p.policyNumber ?? '')}`}
          left={<span className="font-mono">{p.policyNumber}</span>}
          right={
            <span className="text-status-warning-fg">
              Group product issued as a single-life policy — covers no members
            </span>
          }
        />
      ))}
    </div>
  );
}

function ClaimList({
  resource,
  onRetry,
}: {
  resource: Resource<Page<ClaimView>>;
  onRetry: () => void;
}) {
  return (
    <PanelState resource={resource} onRetry={onRetry} empty="No claims.">
      {() => {
        const rows = resource.data?.items ?? [];
        if (rows.length === 0) return null;
        const total = resource.data?.page.totalElements ?? rows.length;
        return (
          <div className="border-t border-border pb-1">
            {rows.slice(0, PANEL_ROW_CAP).map((c) => (
              <LinkRow
                key={c.claimId}
                to={`../../claims/${c.claimId}`}
                left={
                  <>
                    <span className="capitalize">{c.claimType.replace(/_/g, ' ').toLowerCase()}</span>
                    <span className="ml-2 font-mono text-muted-foreground">{c.policyNumber}</span>
                  </>
                }
                right={<StatusBadge kind="claim" value={c.status} />}
              />
            ))}
            {/* No "view all" link: the Claims screen filters by status and free text,
                not by claimant, so a link there would not show what it promised. */}
            <MoreRow hidden={total - Math.min(rows.length, PANEL_ROW_CAP)} />
          </div>
        );
      }}
    </PanelState>
  );
}

function CaseList({
  resource,
  onRetry,
}: {
  resource: Resource<Page<UnderwritingCaseView>>;
  onRetry: () => void;
}) {
  return (
    <PanelState resource={resource} onRetry={onRetry} empty="No underwriting cases.">
      {() => {
        const rows = resource.data?.items ?? [];
        if (rows.length === 0) return null;
        const total = resource.data?.page.totalElements ?? rows.length;
        return (
          <div className="border-t border-border pb-1">
            {rows.slice(0, PANEL_ROW_CAP).map((c) => (
              <LinkRow
                key={c.caseId}
                to={`../../underwriting/${c.caseId}`}
                left={<span className="font-mono">{c.caseId?.slice(0, 8)}</span>}
                right={
                  <>
                    {c.decisionOutcome && (
                      <span className="mr-2 capitalize">
                        {c.decisionOutcome.replace(/_/g, ' ').toLowerCase()}
                      </span>
                    )}
                    {c.status ? <StatusBadge kind="underwritingCase" value={c.status} /> : '—'}
                  </>
                }
              />
            ))}
            <MoreRow hidden={total - Math.min(rows.length, PANEL_ROW_CAP)} />
          </div>
        );
      }}
    </PanelState>
  );
}

function BeneficiaryOfList({
  resource,
  onRetry,
}: {
  resource: Resource<BeneficiaryOfView[]>;
  onRetry: () => void;
}) {
  return (
    <PanelState
      resource={resource}
      onRetry={onRetry}
      empty="Not named as a beneficiary on any policy."
    >
      {() => {
        const rows = resource.data ?? [];
        if (rows.length === 0) return null;
        return (
          <div className="border-t border-border pb-1">
            {rows.slice(0, PANEL_ROW_CAP).map((b) => (
              <LinkRow
                key={b.policyNumber}
                to={`../../policies/${b.policyNumber}`}
                left={<span className="font-mono">{b.policyNumber}</span>}
                right={
                  <>
                    <span className="tabular-nums">{b.sharePercent}%</span>
                    {/* An irrevocable share cannot be reassigned without this person's
                        consent, which is the whole reason to show the flag at all. */}
                    {b.revocable === false && <span className="ml-2">irrevocable</span>}
                    {b.policyStatus && <span className="ml-2">{b.policyStatus.toLowerCase()}</span>}
                  </>
                }
              />
            ))}
            {/* This one is a plain array, not a page, so the total is the length. */}
            <MoreRow hidden={rows.length - PANEL_ROW_CAP} />
          </div>
        );
      }}
    </PanelState>
  );
}

function DocumentList({
  resource,
  onRetry,
}: {
  resource: Resource<PartyDocumentView[]>;
  onRetry: () => void;
}) {
  return (
    <PanelState resource={resource} onRetry={onRetry} empty="No documents filed.">
      {() => {
        const rows = resource.data ?? [];
        if (rows.length === 0) return null;
        return (
          <ul className="border-t border-border px-4 py-2">
            {rows.slice(0, PANEL_ROW_CAP).map((d) => (
              <li key={d.documentRef} className="py-1 text-xs">
                <p className="truncate" title={d.fileName ?? d.documentRef}>
                  {d.fileName ?? <span className="font-mono">{d.documentRef}</span>}
                </p>
                <p className="text-[11px] text-muted-foreground">
                  {(d.documentType ?? '').replace(/_/g, ' ').toLowerCase()} ·{' '}
                  {formatInstant(d.uploadedAt)}
                </p>
              </li>
            ))}
            {rows.length > PANEL_ROW_CAP && (
              <li className="pt-1 text-[11px] text-muted-foreground">
                {rows.length - PANEL_ROW_CAP} more not shown
              </li>
            )}
          </ul>
        );
      }}
    </PanelState>
  );
}

function AgentRecordList({
  resource,
  onRetry,
}: {
  resource: Resource<Page<AgentView>>;
  onRetry: () => void;
}) {
  return (
    <PanelState resource={resource} onRetry={onRetry} empty="Not an agent.">
      {() => {
        const rows = resource.data?.items ?? [];
        if (rows.length === 0) return null;
        return (
          <div className="border-t border-border pb-1">
            {rows.map((a) => (
              <LinkRow
                key={a.agentId}
                to={`../../agents/${a.agentId}`}
                left={<span className="font-mono">{a.licenseNumber}</span>}
                right={a.licenseStatus ? <StatusBadge kind="agentLicense" value={a.licenseStatus} /> : '—'}
              />
            ))}
          </div>
        );
      }}
    </PanelState>
  );
}

function KycPanel({
  partyId,
  currentStatus,
}: {
  partyId: string;
  currentStatus: KycStatus | undefined;
}) {
  const uploadKycEvidence = usePartyStore((s) => s.uploadKycEvidence);
  const resetUploadKycEvidence = usePartyStore((s) => s.resetUploadKycEvidence);
  const uploading = usePartyStore(selectUploadingKycEvidence(partyId));

  const submitKyc = usePartyStore((s) => s.submitKyc);
  const resetSubmitKyc = usePartyStore((s) => s.resetSubmitKyc);
  const deciding = usePartyStore(selectSubmittingKyc(partyId));

  useEffect(() => {
    resetUploadKycEvidence(partyId);
    resetSubmitKyc(partyId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [partyId]);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    accept: ACCEPTED_EVIDENCE_TYPES,
    multiple: false,
    onDrop: (accepted) => {
      if (accepted[0]) void uploadKycEvidence(partyId, accepted[0]);
    },
  });

  const documentRef = uploading.status === 'success' ? uploading.data?.documentRef : undefined;

  // Which decision is awaiting its second click. There is no form here, so the
  // first click arms rather than submits.
  const [pending, setPending] = useState<KycStatus | null>(null);

  async function decide(status: KycStatus) {
    if (!documentRef) return;
    await submitKyc(partyId, status, documentRef);
    if (usePartyStore.getState().submittingKyc[partyId]?.status === 'success') {
      setPending(null);
    }
  }

  return (
    <div className="space-y-3 px-4 pt-3 pb-4">
      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center gap-1.5 rounded-md border border-dashed border-border px-4 py-6 text-center transition-colors',
          isDragActive ? 'border-accent bg-hover' : 'hover:bg-hover',
          uploading.status === 'loading' && 'pointer-events-none opacity-60',
        )}
      >
        <input {...getInputProps()} disabled={uploading.status === 'loading'} />
        <FileCheck className="size-5 text-muted-foreground" />
        <p className="text-xs text-muted-foreground">
          {uploading.status === 'loading'
            ? 'Uploading…'
            : isDragActive
              ? 'Drop the file'
              : 'Drag an ID scan or proof of address here, or click to browse'}
        </p>
        <p className="text-[11px] text-subtle-foreground">JPEG, PNG, or PDF</p>
      </div>

      {uploading.status === 'error' && uploading.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {uploading.error.detail ?? uploading.error.title}
        </p>
      )}

      {documentRef && (
        <div className="space-y-2 rounded-md border border-border p-2.5">
          <p className="text-[11px] text-muted-foreground">
            Evidence uploaded: <span className="font-mono">{documentRef}</span>
          </p>
          {pending ? (
            <ConfirmAct
              heading={pending === 'VERIFIED' ? 'Verify this identity?' : 'Reject this identity?'}
              tone={pending === 'VERIFIED' ? 'primary' : 'danger'}
              consequence={
                pending === 'VERIFIED' ? (
                  <>
                    Mark this client KYC-verified against the evidence{' '}
                    <span className="font-mono">{documentRef}</span>. They become eligible to
                    hold a policy.
                  </>
                ) : (
                  <>
                    Refuse this client&rsquo;s identity evidence. They cannot be taken on as a
                    policyholder while their KYC reads rejected.
                  </>
                )
              }
              /*
                Deliberately NOT phrased as irreversible, because it is not:
                Party.updateKycStatus assigns the new status with no guard on the
                old one, so a rejected identity can be verified later and the
                reverse. The critique listed this among the irreversible six; it
                was checked rather than taken, and a confirmation that overstates
                is how every confirmation on the screen stops being read.
              */
              reversal="A KYC decision can be changed later by re-deciding against fresh evidence — but it is recorded, and it gates whether this client can hold a policy in the meantime."
              confirmLabel={pending === 'VERIFIED' ? 'Verify identity' : 'Reject identity'}
              busy={deciding.status === 'loading'}
              onConfirm={() => void decide(pending)}
              onCancel={() => setPending(null)}
            />
          ) : (
            <div className="flex items-center gap-1.5">
              <Button
                size="sm"
                variant="primary"
                disabled={currentStatus === 'VERIFIED'}
                onClick={() => setPending('VERIFIED')}
              >
                <Check />
                Verify
              </Button>
              <Button
                size="sm"
                variant="danger"
                disabled={currentStatus === 'REJECTED'}
                onClick={() => setPending('REJECTED')}
              >
                <X />
                Reject
              </Button>
            </div>
          )}
          {deciding.status === 'error' && deciding.error && (
            <p role="alert" className="text-[11px] text-status-danger-fg">
              {deciding.error.detail ?? deciding.error.title}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Goes back to wherever the caller actually came from -- unlike every other
 * detail page's BackLink, a party is reached from four different contexts
 * (policy/claim/underwriting/agent), so there is no single "all X" list this
 * could point at.
 */
function BackLink({ onClick }: { onClick: () => void }) {
  return (
    <Button variant="ghost" size="sm" className="-ml-2" onClick={onClick}>
      <ArrowLeft />
      Back
    </Button>
  );
}

