import { ArrowLeft, Check, FileCheck, X } from 'lucide-react';
import { useEffect, type ReactNode } from 'react';
import { useDropzone } from 'react-dropzone';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatDate, formatInstant } from '@/lib/dates';
import type { ApiError } from '@/lib/apiError';
import type {
  AgentView,
  BeneficiaryOfView,
  ClaimView,
  KycStatus,
  Page,
  PartyDocumentView,
  PolicyView,
  UnderwritingCaseView,
} from '@/api/types';
import { isInitialLoad, type Resource } from '@/store/createResourceSlice';
import {
  selectClientAgentRecords,
  selectClientBeneficiaryOf,
  selectClientCases,
  selectClientClaims,
  selectClientPolicies,
  useClientRecordStore,
} from '@/store/clientRecord';
import {
  selectParty,
  selectPartyDocuments,
  selectSubmittingKyc,
  selectUploadingKycEvidence,
  usePartyStore,
} from '@/store/partyStore';

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
        description={party?.partyType ? <span>{party.partyType}</span> : undefined}
        actions={party?.kycStatus && <StatusBadge kind="kyc" value={party.kycStatus} />}
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
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

          {isStaff && (
            <Panel
              title="KYC verification"
              subtitle="Upload evidence, then record a decision against it."
            >
              {party && <KycPanel partyId={partyId} currentStatus={party.kycStatus} />}
            </Panel>
          )}
        </div>

        <div className="space-y-5">
          <Panel title="Identity">
            {party && (
              <dl className="px-4 pb-2">
                <Field label="Type" value={party.partyType ?? '—'} />
                <Field label="Date of birth" value={formatDate(party.dateOfBirth) || '—'} />
                <Field label="Registration no." value={party.registrationNumber ?? '—'} />
                <Field label="Phone" value={party.phoneNumber ?? '—'} />
                <Field label="Email" value={party.email ?? '—'} />
                <Field
                  label="KYC decided"
                  value={party.kycVerifiedAt ? formatInstant(party.kycVerifiedAt) : '—'}
                  {...(party.kycVerifiedAt ? {} : { note: 'No decision recorded yet.' })}
                />
                <Field label="Registered" value={formatInstant(party.createdAt) || '—'} />
                <Field
                  label="Registered by"
                  value={<span className="font-mono text-xs">{party.createdBy ?? '—'}</span>}
                  note="The account that created this record — who the client relationship belongs to."
                />
                <Field label="Client id" value={<span className="font-mono text-xs">{partyId}</span>} />
              </dl>
            )}
          </Panel>

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
        </div>
      </div>
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

  async function decide(status: KycStatus) {
    if (!documentRef) return;
    await submitKyc(partyId, status, documentRef);
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
          <div className="flex items-center gap-1.5">
            <Button
              size="sm"
              variant="primary"
              disabled={deciding.status === 'loading' || currentStatus === 'VERIFIED'}
              onClick={() => void decide('VERIFIED')}
            >
              <Check />
              Verify
            </Button>
            <Button
              size="sm"
              variant="danger"
              disabled={deciding.status === 'loading' || currentStatus === 'REJECTED'}
              onClick={() => void decide('REJECTED')}
            >
              <X />
              Reject
            </Button>
          </div>
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
