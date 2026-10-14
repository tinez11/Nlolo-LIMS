import { useEffect, useState } from 'react';
import { useDropzone } from 'react-dropzone';
import { Check, FileCheck, X } from 'lucide-react';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DocumentViewer } from '@/components/DocumentViewer';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import type { KycStatus, PartyDocumentView } from '@/api/types';
import { isInitialLoad, type Resource } from '@/store/createResourceSlice';
import { ErrorPanel } from '@/components/states';
import { StatusBadge } from '@/components/StatusBadge';
import {
  selectKycDecisions,
  selectSubmittingKyc,
  selectUploadingKycEvidence,
  usePartyStore,
} from '@/store/partyStore';

/**
 * KYC: filing the evidence, and deciding on it.
 *
 * <b>The defect this file exists to close.</b> The decision used to be gated on
 * `uploading.status === 'success'` -- the upload THIS BROWSER SESSION made. A reviewer opening a
 * client whose evidence was already on record saw a dropzone and nothing else: no Verify, no
 * Reject, no sight of the document. The only way to decide was to upload a file yourself, which
 * is precisely the thing the server-side split was built to prevent. `PartyController`'s own
 * comment states the intent -- "the agent who took the documents supplies them and a staff member
 * decides whether they are good" -- and `POST /parties/{id}/kyc-evidence` has accepted an agents
 * token since it was written. Nothing in the console ever called it as an agent, and nothing ever
 * decided against a document it had not just produced. Recorded in `PLAN.md` §14.3.
 *
 * So the evidence on record is the subject of this screen, and the upload is one way of adding to
 * it rather than the only way in.
 *
 * <b>Two panels, because two realms do two different jobs.</b> {@link KycDecisionPanel} is staff:
 * read the evidence, then decide against the one you read. {@link KycFilingPanel} is the agent:
 * send what you collected, and see what you have sent. The agent panel does not merely hide the
 * buttons -- it cannot show the documents either, because `GET /documents/{ref}` is staff-only and
 * would 403 on a file that agent uploaded themselves. That is a real gap and it is named on
 * screen rather than left to look like a loading failure.
 */

const ACCEPTED_EVIDENCE_TYPES = {
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/png': ['.png'],
  'application/pdf': ['.pdf'],
};

/**
 * A filed document this screen can actually act on.
 *
 * Every field on the generated `PartyDocumentView` is optional, `documentRef` included. A row
 * without one cannot be opened or decided against — it is not a document, it is a gap in a
 * response — so it is filtered out here rather than defended against at four call sites.
 */
type FiledEvidence = PartyDocumentView & { documentRef: string };

/** The KYC half of everything filed against this party. A policy document is not evidence of identity. */
function kycEvidenceOf(documents: PartyDocumentView[]): FiledEvidence[] {
  return documents.filter(
    (d): d is FiledEvidence => d.documentType === 'KYC_EVIDENCE' && !!d.documentRef,
  );
}

export function KycDecisionPanel({
  partyId,
  currentStatus,
  documents,
  onRetryDocuments,
}: {
  partyId: string;
  currentStatus: KycStatus | undefined;
  documents: Resource<PartyDocumentView[]>;
  onRetryDocuments: () => void;
}) {
  const submitKyc = usePartyStore((s) => s.submitKyc);
  const resetSubmitKyc = usePartyStore((s) => s.resetSubmitKyc);
  const deciding = usePartyStore(selectSubmittingKyc(partyId));

  /*
   * Which document the reviewer is reading, and therefore the one any decision is recorded
   * against. Deliberately NOT preselected: the server stores the ref as the evidence the
   * decision rests on, and a document chosen for someone by a default is a document nobody
   * read. Choosing it is one click and it is the click that matters.
   *
   * Both pieces of state carry the party they belong to, and are read only when that party is
   * the one on screen. Resetting them in an effect instead would be a setState in an effect
   * body, which this project lints as an error -- and would leave a window of one render in
   * which the previous client's chosen document is still armed under this client's name.
   */
  const [selection, setSelection] = useState<{ partyId: string; ref: string } | null>(null);
  const [armed, setArmed] = useState<{ partyId: string; status: KycStatus } | null>(null);
  const selected = selection?.partyId === partyId ? selection.ref : null;
  const pending = armed?.partyId === partyId ? armed.status : null;
  const setSelected = (ref: string) => setSelection({ partyId, ref });
  const setPending = (status: KycStatus | null) =>
    setArmed(status === null ? null : { partyId, status });

  useEffect(() => {
    resetSubmitKyc(partyId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [partyId]);

  const evidence = kycEvidenceOf(documents.data ?? []);
  const chosen = evidence.find((d) => d.documentRef === selected) ?? null;

  async function decide(status: KycStatus) {
    if (!chosen) return;
    await submitKyc(partyId, status, chosen.documentRef);
    if (usePartyStore.getState().submittingKyc[partyId]?.status === 'success') {
      setPending(null);
    }
  }

  if (documents.status === 'error' && documents.error && documents.data === null) {
    return <ErrorPanel error={documents.error} onRetry={onRetryDocuments} />;
  }

  return (
    <div className="grid gap-5 px-4 pt-3 pb-4 lg:grid-cols-[17rem_minmax(0,1fr)]">
      <div className="space-y-3">
        <EvidenceList
          evidence={evidence}
          loading={isInitialLoad(documents)}
          selected={selected}
          onSelect={(ref) => {
            setSelected(ref);
            // Arming a decision and then changing the document under it would leave the
            // confirmation describing one file and committing another.
            setPending(null);
          }}
        />
        <EvidenceDropzone
          partyId={partyId}
          label="Add evidence"
          onUploaded={(ref) => {
            setSelected(ref);
            setPending(null);
          }}
        />
      </div>

      <div className="space-y-3">
        {chosen ? (
          <DocumentViewer
            documentRef={chosen.documentRef}
            contentType={chosen.contentType}
            fileName={chosen.fileName}
          />
        ) : (
          <div className="flex h-96 flex-col items-center justify-center gap-2 rounded-md border border-dashed border-border px-6 text-center lg:h-[30rem]">
            <p className="text-sm">
              {evidence.length === 0 ? 'No identity evidence on file' : 'Nothing open'}
            </p>
            <p className="text-xs text-muted-foreground">
              {evidence.length === 0
                ? 'Upload an ID scan or proof of address, or ask the agent who registered this client to send it.'
                : 'Choose a document on the left to read it here, then decide against it.'}
            </p>
          </div>
        )}

        <DecisionControls
          chosen={chosen}
          currentStatus={currentStatus}
          pending={pending}
          onArm={setPending}
          onCancel={() => setPending(null)}
          onConfirm={decide}
          busy={deciding.status === 'loading'}
        />
        {deciding.status === 'error' && deciding.error && <InlineError error={deciding.error} />}

        <DecisionHistory
          partyId={partyId}
          openableRefs={new Set(evidence.map((d) => d.documentRef))}
          onOpen={setSelected}
        />
      </div>
    </div>
  );
}

/**
 * The agent's half: send the papers in, and see what has been sent.
 *
 * No viewer and no decision. Both absences are the server's rules rather than a UI choice, and
 * both are written on the screen -- an agent who can see a control reasonably concludes the act
 * is theirs, and finds out otherwise at the point of saving.
 */
export function KycFilingPanel({
  partyId,
  documents,
  onRetryDocuments,
}: {
  partyId: string;
  documents: Resource<PartyDocumentView[]>;
  onRetryDocuments: () => void;
}) {
  if (documents.status === 'error' && documents.error && documents.data === null) {
    return <ErrorPanel error={documents.error} onRetry={onRetryDocuments} />;
  }
  const evidence = kycEvidenceOf(documents.data ?? []);

  return (
    <div className="space-y-3 px-4 pt-3 pb-4">
      <EvidenceDropzone partyId={partyId} label="Send evidence" onUploaded={() => undefined} />

      <EvidenceList evidence={evidence} loading={isInitialLoad(documents)} readOnly />

      <p className="text-xs text-subtle-foreground">
        {evidence.length === 0
          ? 'Verification is the office’s decision. Send the ID scan and they will review it.'
          : 'Sent. A staff member verifies it — you will see this client’s KYC status change when they do. Filed documents cannot be opened again from here.'}
      </p>
    </div>
  );
}

/**
 * What is on record, and which of it is open.
 *
 * Real `<button>`s with `aria-pressed`, which is this console's own idiom for "this is the one
 * on screen" (`StatCards`, and the table row the drawer is previewing). A radio group would be
 * the other defensible shape and would be the first radio input in the console -- a system
 * decision, not a local one.
 */
function EvidenceList({
  evidence,
  loading,
  selected,
  onSelect,
  readOnly = false,
}: {
  evidence: FiledEvidence[];
  loading: boolean;
  selected?: string | null;
  onSelect?: (documentRef: string) => void;
  readOnly?: boolean;
}) {
  if (loading) {
    return <p className="text-xs text-muted-foreground">Loading evidence…</p>;
  }
  if (evidence.length === 0) {
    return <p className="text-xs text-muted-foreground">No identity evidence on file.</p>;
  }

  return (
    <div>
      <p className="pb-1.5 text-xs font-medium text-muted-foreground">
        Identity evidence on file
      </p>
      <ul className="overflow-hidden rounded-md border border-border">
        {evidence.map((d, index) => {
          const label = d.fileName?.trim() || d.documentRef;
          const meta = formatInstant(d.uploadedAt) || 'Date not recorded';
          const row = (
            <>
              <span className="block truncate text-sm" title={label}>
                {label}
              </span>
              {/* The uploader is stored as a Keycloak subject and nothing on this platform can
                  turn it into a name, so the date is all that is said. A raw subject printed
                  under a file name would read as an answer to "who filed this". */}
              <span className="block text-xs text-muted-foreground">Filed {meta}</span>
            </>
          );
          return (
            <li key={d.documentRef} className={cn(index > 0 && 'border-t border-border')}>
              {readOnly ? (
                <div className="px-3 py-2">{row}</div>
              ) : (
                <button
                  type="button"
                  aria-pressed={selected === d.documentRef}
                  onClick={() => onSelect?.(d.documentRef)}
                  className={cn(
                    'block w-full px-3 py-2 text-left transition-colors active:duration-0',
                    selected === d.documentRef
                      ? 'bg-selected'
                      : 'hover:bg-hover active:bg-selected',
                  )}
                >
                  {row}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/**
 * Every decision taken on this client, newest first.
 *
 * <b>Why this is worth a panel.</b> `Party.applyKycDecision` assigns the new status with no guard
 * on the old one, so a KYC decision can be overturned — and the party row keeps only the last
 * one. A client who was rejected in March and verified in April reads, everywhere else in this
 * console, as a client who has simply always been verified. These rows are the only record that
 * March happened, and they had been written since the platform's first decision without a single
 * way to read them: no getters on the entity, no query on the repository, no endpoint.
 *
 * The evidence ref on a row is a real link back into the viewer where the document is still on
 * file. Where it is not — an older decision whose document has since been removed, or one taken
 * against something filed outside this list — it stays text, because a control that opens
 * nothing is worse than a fact.
 */
function DecisionHistory({
  partyId,
  openableRefs,
  onOpen,
}: {
  partyId: string;
  openableRefs: Set<string>;
  onOpen: (documentRef: string) => void;
}) {
  const decisions = usePartyStore(selectKycDecisions(partyId));
  const loadKycDecisions = usePartyStore((s) => s.loadKycDecisions);

  useEffect(() => {
    void loadKycDecisions(partyId);
  }, [partyId, loadKycDecisions]);

  if (isInitialLoad(decisions)) {
    return <p className="text-xs text-muted-foreground">Loading decision history…</p>;
  }
  if (decisions.status === 'error' && decisions.error && decisions.data === null) {
    return <ErrorPanel error={decisions.error} onRetry={() => void loadKycDecisions(partyId)} />;
  }

  const rows = decisions.data ?? [];

  return (
    <div>
      <p className="pb-1.5 text-xs font-medium text-muted-foreground">Decision history</p>
      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          No decision recorded yet. This client has never been verified or rejected.
        </p>
      ) : (
        <ul className="divide-y divide-border rounded-md border border-border">
          {rows.map((d, index) => (
            <li
              key={`${d.decidedAt}-${d.evidenceDocumentRef}-${index}`}
              className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 px-3 py-2"
            >
              <span className="flex items-center gap-2">
                <StatusBadge kind="kyc" value={d.status} />
                <span className="text-xs text-muted-foreground">
                  {formatInstant(d.decidedAt) || 'Date not recorded'}
                </span>
              </span>
              <span className="flex items-center gap-2 text-xs">
                {/* Null is "not recorded" and never "nobody": every decision taken before the
                    name was captured looks like this, and so does one taken without a token.
                    The subject is deliberately not shown in its place — it names the person to
                    Keycloak and to nobody else. */}
                <span className="text-muted-foreground">
                  {d.decidedByName?.trim() || 'Name not recorded'}
                </span>
                {openableRefs.has(d.evidenceDocumentRef) ? (
                  <Button
                    size="sm"
                    variant="ghost"
                    aria-label={`Open the evidence this ${d.status.toLowerCase()} decision was recorded against`}
                    onClick={() => onOpen(d.evidenceDocumentRef)}
                  >
                    Evidence
                  </Button>
                ) : (
                  <span className="font-mono text-xs text-subtle-foreground">
                    {d.evidenceDocumentRef}
                  </span>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Verify / Reject, and the second click in front of each. */
function DecisionControls({
  chosen,
  currentStatus,
  pending,
  onArm,
  onCancel,
  onConfirm,
  busy,
}: {
  chosen: FiledEvidence | null;
  currentStatus: KycStatus | undefined;
  pending: KycStatus | null;
  onArm: (status: KycStatus) => void;
  onCancel: () => void;
  onConfirm: (status: KycStatus) => void;
  busy: boolean;
}) {
  const name = chosen?.fileName?.trim() || chosen?.documentRef;

  if (pending && chosen) {
    return (
      <ConfirmAct
        heading={pending === 'VERIFIED' ? 'Verify this identity?' : 'Reject this identity?'}
        tone={pending === 'VERIFIED' ? 'primary' : 'danger'}
        consequence={
          pending === 'VERIFIED' ? (
            <>
              Mark this client KYC-verified against <span className="font-medium">{name}</span>,
              the document open beside this. They become eligible to hold a policy.
            </>
          ) : (
            <>
              Refuse this client’s identity evidence, recorded against{' '}
              <span className="font-medium">{name}</span>. They cannot be taken on as a
              policyholder while their KYC reads rejected.
            </>
          )
        }
        /*
          Deliberately NOT phrased as irreversible, because it is not: Party.updateKycStatus
          assigns the new status with no guard on the old one, so a rejected identity can be
          verified later and the reverse. The critique listed this among the irreversible six;
          it was checked rather than taken, and a confirmation that overstates is how every
          confirmation on the screen stops being read.
        */
        reversal="A KYC decision can be changed later by re-deciding against fresh evidence — but it is recorded, and it gates whether this client can hold a policy in the meantime."
        confirmLabel={pending === 'VERIFIED' ? 'Verify identity' : 'Reject identity'}
        busy={busy}
        onConfirm={() => onConfirm(pending)}
        onCancel={onCancel}
      />
    );
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-1.5">
        <Button
          size="sm"
          variant="primary"
          disabled={!chosen || currentStatus === 'VERIFIED'}
          onClick={() => onArm('VERIFIED')}
        >
          <Check />
          Verify
        </Button>
        <Button
          size="sm"
          variant="danger"
          disabled={!chosen || currentStatus === 'REJECTED'}
          onClick={() => onArm('REJECTED')}
        >
          <X />
          Reject
        </Button>
      </div>
      {/* Why the controls are dead, said once and in the open. A disabled pair with no
          explanation is the state a reviewer reads as "this console is broken". */}
      {!chosen && (
        <p className="text-xs text-muted-foreground">
          Open a document first — a decision is recorded against the evidence it was taken on.
        </p>
      )}
      {chosen && currentStatus === 'VERIFIED' && (
        <p className="text-xs text-muted-foreground">
          Already verified. Reject to overturn it, or re-verify against fresh evidence by
          uploading it first.
        </p>
      )}
      {chosen && currentStatus === 'REJECTED' && (
        <p className="text-xs text-muted-foreground">
          Already rejected. Verify to overturn it.
        </p>
      )}
    </div>
  );
}

/**
 * Adding a file to the record.
 *
 * The dropzone's keyboard and ARIA handling is `EvidencePanel`'s, which the earlier KYC
 * dropzone did not have: `noKeyboard` drops react-dropzone's unnamed focusable root (a
 * `role="presentation"` div with `tabIndex=0`, which also nests the file input inside it and
 * trips axe's `nested-interactive`), and the input itself is put in the tab order with a real
 * name instead.
 */
function EvidenceDropzone({
  partyId,
  label,
  onUploaded,
}: {
  partyId: string;
  label: string;
  onUploaded: (documentRef: string) => void;
}) {
  const uploadKycEvidence = usePartyStore((s) => s.uploadKycEvidence);
  const resetUploadKycEvidence = usePartyStore((s) => s.resetUploadKycEvidence);
  const loadPartyDocuments = usePartyStore((s) => s.loadPartyDocuments);
  const uploading = usePartyStore(selectUploadingKycEvidence(partyId));

  useEffect(() => {
    resetUploadKycEvidence(partyId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [partyId]);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    accept: ACCEPTED_EVIDENCE_TYPES,
    multiple: false,
    noKeyboard: true,
    onDrop: (accepted) => {
      if (accepted[0]) void submit(accepted[0]);
    },
  });

  async function submit(file: File) {
    await uploadKycEvidence(partyId, file);
    const result = usePartyStore.getState().uploadingKycEvidence[partyId];
    if (result?.status === 'success' && result.data?.documentRef) {
      // The list this file now belongs to is a separate read, so it does not know yet.
      await loadPartyDocuments(partyId);
      onUploaded(result.data.documentRef);
    }
  }

  return (
    <div className="space-y-2">
      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center gap-1.5 rounded-md border border-dashed border-border px-4 py-5 text-center transition-colors focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent',
          isDragActive ? 'border-accent bg-hover' : 'hover:bg-hover',
          uploading.status === 'loading' && 'pointer-events-none opacity-60',
        )}
      >
        <input
          {...getInputProps({ tabIndex: 0, 'aria-label': label })}
          disabled={uploading.status === 'loading'}
        />
        <FileCheck className="size-5 text-muted-foreground" aria-hidden />
        <p className="text-xs text-muted-foreground" aria-live="polite">
          {uploading.status === 'loading'
            ? 'Uploading…'
            : isDragActive
              ? 'Drop the file'
              : 'Drag an ID scan or proof of address here, or click to browse'}
        </p>
        <p className="text-xs text-subtle-foreground">JPEG, PNG, or PDF</p>
      </div>

      {uploading.status === 'error' && uploading.error && <InlineError error={uploading.error} />}
    </div>
  );
}
