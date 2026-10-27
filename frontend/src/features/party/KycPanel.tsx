import { can } from '@/auth/access';
import { useEffect, useState } from 'react';
import { useDropzone } from 'react-dropzone';
import { Check, ChevronDown, FileCheck, X } from 'lucide-react';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DocumentViewer } from '@/components/DocumentViewer';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import type { ClientDocumentType } from '@/api/party';
import type { KycStatus, PartyDetailView, PartyDocumentView } from '@/api/types';
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
 * KYC and the client's own documents: filing them, and deciding on the identity evidence among them.
 *
 * <b>One place for what the client filed (2026-10-22, the user).</b> A client's record holds what was filed on the
 * client -- identity evidence and signed forms. Policy schedules, underwriting evidence and claim evidence stay on
 * their own records. There used to be a separate Documents tab listing the same files again, saying nothing of where
 * they came from; it is folded in here, each document saying who filed it.
 *
 * <b>The decision rests on a document someone read.</b> It used to be gated on the upload THIS BROWSER SESSION made,
 * so a reviewer could only decide by uploading a file themselves. The evidence on record is the subject; the upload is
 * one way of adding to it. Only identity evidence can carry a decision -- a signed form proves no identity.
 *
 * <b>Two realms, two jobs.</b> {@link KycDecisionPanel} is staff: read, then decide. {@link KycFilingPanel} is the
 * agent: send what was collected, and see what was sent. The agent cannot open files -- `GET /documents/{ref}` is
 * staff-only -- and the screen says so rather than looking broken.
 */

const ACCEPTED_TYPES = {
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/png': ['.png'],
  'application/pdf': ['.pdf'],
};

const TYPE_LABEL: Record<ClientDocumentType, string> = {
  KYC_EVIDENCE: 'Identity evidence',
  SIGNED_FORM: 'Signed form',
};

/**
 * A filed document this screen can act on. Every field of the generated view is optional, `documentRef` included; a
 * row without one is a gap in a response, not a document, so it is dropped here once.
 */
type FiledDocument = PartyDocumentView & { documentRef: string };

function filedOf(documents: PartyDocumentView[], type: ClientDocumentType): FiledDocument[] {
  return documents.filter((d): d is FiledDocument => d.documentType === type && !!d.documentRef);
}

/** Who registered the client, for saying where a document came from. */
type Registrar = Pick<PartyDetailView, 'createdBy' | 'createdByName' | 'registeredByPartyId'>;

/**
 * Where a document came from. The uploader is stored as a Keycloak subject nothing can name, so it is compared, not
 * shown: the registering agent's own uploads are theirs by name; anything else came from the office.
 */
function provenance(d: PartyDocumentView, registrar: Registrar | null): string {
  if (registrar?.createdBy && d.uploadedBy === registrar.createdBy) {
    const who = registrar.createdByName?.trim();
    return registrar.registeredByPartyId
      ? `Sent by ${who || 'the registering agent'}${who ? ', the registering agent' : ''}`
      : `Filed by ${who || 'whoever registered this client'}`;
  }
  return 'Filed by the office';
}

export function KycDecisionPanel({
  partyId,
  currentStatus,
  registrar,
  documents,
  onRetryDocuments,
}: {
  partyId: string;
  currentStatus: KycStatus | undefined;
  registrar: Registrar | null;
  documents: Resource<PartyDocumentView[]>;
  onRetryDocuments: () => void;
}) {
  const submitKyc = usePartyStore((s) => s.submitKyc);
  const resetSubmitKyc = usePartyStore((s) => s.resetSubmitKyc);
  const deciding = usePartyStore(selectSubmittingKyc(partyId));

  /*
   * Which document is open, and therefore the one a decision is recorded against. Deliberately NOT preselected: a
   * document chosen for someone by a default is a document nobody read. Both pieces of state carry the party they
   * belong to and are read only when that party is on screen -- resetting them in an effect would be a setState in
   * an effect body, which this project lints as an error.
   */
  const [selection, setSelection] = useState<{ partyId: string; ref: string } | null>(null);
  const [armed, setArmed] = useState<{ partyId: string; status: KycStatus } | null>(null);
  const selected = selection?.partyId === partyId ? selection.ref : null;
  const pending = armed?.partyId === partyId ? armed.status : null;
  const open = (ref: string) => {
    setSelection({ partyId, ref });
    // Arming a decision and then changing the document under it would confirm one file and commit another.
    setArmed(null);
  };
  const setPending = (status: KycStatus | null) => setArmed(status === null ? null : { partyId, status });

  useEffect(() => {
    resetSubmitKyc(partyId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [partyId]);

  const all = documents.data ?? [];
  const evidence = filedOf(all, 'KYC_EVIDENCE');
  const forms = filedOf(all, 'SIGNED_FORM');
  const chosen = [...evidence, ...forms].find((d) => d.documentRef === selected) ?? null;
  const chosenEvidence = chosen?.documentType === 'KYC_EVIDENCE' ? chosen : null;

  async function decide(status: KycStatus) {
    if (!chosenEvidence) return;
    await submitKyc(partyId, status, chosenEvidence.documentRef);
    if (usePartyStore.getState().submittingKyc[partyId]?.status === 'success') {
      setPending(null);
    }
  }

  if (documents.status === 'error' && documents.error && documents.data === null) {
    return <ErrorPanel error={documents.error} onRetry={onRetryDocuments} />;
  }

  return (
    // On a phone: the lists, then the open document and its decision, then the upload -- so a tapped document opens
    // right under the finger. From lg: lists and upload on the left, the document beside them.
    <div className="grid gap-6 px-4 pt-3 pb-4 lg:grid-cols-[20rem_minmax(0,1fr)] lg:grid-rows-[auto_1fr]">
      <div className="space-y-5 lg:col-start-1 lg:row-start-1">
        <DocumentGroup
          title="Identity evidence"
          empty="No ID or proof of address yet."
          documents={evidence}
          registrar={registrar}
          loading={isInitialLoad(documents)}
          selected={selected}
          onSelect={open}
        />
        <DocumentGroup
          title="Signed forms"
          empty="None filed."
          documents={forms}
          registrar={registrar}
          loading={isInitialLoad(documents)}
          selected={selected}
          onSelect={open}
        />
      </div>

      <div className="order-last lg:order-none lg:col-start-1 lg:row-start-2 lg:self-start">
        {/* Filing evidence is clients.edit and deciding it clients.kyc (2026-10-26): what a person cannot do is not drawn. */}
        {can('clients.edit') && <DocumentUpload partyId={partyId} onUploaded={open} />}
      </div>

      <div className="space-y-4 lg:col-start-2 lg:row-span-2 lg:row-start-1">
        {chosen ? (
          <DocumentViewer documentRef={chosen.documentRef} contentType={chosen.contentType} fileName={chosen.fileName} />
        ) : (
          <div className="flex h-80 flex-col items-center justify-center gap-1.5 rounded-lg bg-control px-6 text-center lg:h-[28rem]">
            <FileCheck className="size-6 text-muted-foreground" aria-hidden />
            <p className="text-sm font-medium">
              {evidence.length === 0 ? 'No identity evidence on file' : 'Open a document to read it'}
            </p>
            <p className="max-w-sm text-sm text-muted-foreground">
              {evidence.length === 0
                ? 'Upload an ID scan or proof of address, or ask the agent who registered this client to send it.'
                : 'The decision is recorded against the identity document you open.'}
            </p>
          </div>
        )}

        {!can('clients.kyc') ? null : chosen && !chosenEvidence ? (
          <p className="text-sm text-muted-foreground">
            A signed form is not proof of identity. Open identity evidence to decide on KYC.
          </p>
        ) : (
          <DecisionControls
            partyId={partyId}
            chosen={chosenEvidence}
            currentStatus={currentStatus}
            pending={pending}
            onArm={setPending}
            onCancel={() => setPending(null)}
            onConfirm={decide}
            busy={deciding.status === 'loading'}
          />
        )}
        {deciding.status === 'error' && deciding.error && <InlineError error={deciding.error} />}

        <DecisionHistory
          partyId={partyId}
          openableRefs={new Set(evidence.map((d) => d.documentRef))}
          onOpen={open}
        />
      </div>
    </div>
  );
}

/**
 * The agent's half: send the papers in, and see what has been sent. No viewer and no decision -- both the server's
 * rules, and both said on screen.
 */
export function KycFilingPanel({
  partyId,
  registrar,
  documents,
  onRetryDocuments,
}: {
  partyId: string;
  registrar: Registrar | null;
  documents: Resource<PartyDocumentView[]>;
  onRetryDocuments: () => void;
}) {
  if (documents.status === 'error' && documents.error && documents.data === null) {
    return <ErrorPanel error={documents.error} onRetry={onRetryDocuments} />;
  }
  const all = documents.data ?? [];
  const evidence = filedOf(all, 'KYC_EVIDENCE');

  return (
    <div className="grid gap-6 px-4 pt-3 pb-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div className="space-y-5">
        <DocumentGroup title="Identity evidence" empty="No ID or proof of address sent yet." documents={evidence}
          registrar={registrar} loading={isInitialLoad(documents)} />
        <DocumentGroup title="Signed forms" empty="None sent." documents={filedOf(all, 'SIGNED_FORM')}
          registrar={registrar} loading={isInitialLoad(documents)} />
        <p className="text-sm text-muted-foreground">
          {evidence.length === 0
            ? 'Verification is the office’s decision. Send the ID scan and they will review it.'
            : 'The office verifies identity. You will see this client’s KYC status change when they do. Filed documents cannot be opened again from here.'}
        </p>
      </div>
      <DocumentUpload partyId={partyId} verb="Send" onUploaded={() => undefined} />
    </div>
  );
}

/**
 * One kind of document on file. Real buttons with `aria-pressed` -- this console's idiom for "this is the one on
 * screen" -- when they can be opened, plain rows when they cannot (an agent).
 */
function DocumentGroup({
  title,
  empty,
  documents,
  registrar,
  loading,
  selected,
  onSelect,
}: {
  title: string;
  empty: string;
  documents: FiledDocument[];
  registrar: Registrar | null;
  loading: boolean;
  selected?: string | null;
  onSelect?: (documentRef: string) => void;
}) {
  return (
    <section aria-label={title}>
      <h3 className="pb-2 text-sm font-semibold">
        {title}
        {documents.length > 0 && <span className="ml-1.5 font-normal text-muted-foreground">{documents.length}</span>}
      </h3>
      {loading ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : documents.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border">
          {documents.map((d) => {
            const label = d.fileName?.trim() || d.documentRef;
            const row = (
              <>
                <span className="block truncate text-sm" title={label}>{label}</span>
                <span className="block text-xs text-muted-foreground">
                  {provenance(d, registrar)} · {formatInstant(d.uploadedAt) || 'date not recorded'}
                </span>
              </>
            );
            return (
              <li key={d.documentRef}>
                {onSelect ? (
                  <button
                    type="button"
                    aria-pressed={selected === d.documentRef}
                    onClick={() => onSelect(d.documentRef)}
                    className={cn(
                      'block w-full px-3 py-2.5 text-left transition-colors active:duration-0',
                      selected === d.documentRef ? 'bg-selected' : 'hover:bg-hover active:bg-selected',
                    )}
                  >
                    {row}
                  </button>
                ) : (
                  <div className="px-3 py-2.5">{row}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/**
 * Every decision taken on this client, newest first, folded away -- the outcome is already stated above it, and the
 * history is what a reviewer opens when they need to know how it got there. These rows are the only record that an
 * overturned decision happened at all.
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
    <details className="group rounded-lg border border-border">
      <summary className="flex cursor-pointer list-none items-center justify-between px-3 py-2.5 text-sm font-medium">
        <span>
          Decision history
          <span className="ml-1.5 font-normal text-muted-foreground">{rows.length}</span>
        </span>
        <ChevronDown className="size-4 text-muted-foreground transition-transform group-open:rotate-180" aria-hidden />
      </summary>
      {rows.length === 0 ? (
        <p className="border-t border-border px-3 py-2.5 text-sm text-muted-foreground">
          No decision yet. This client has never been verified or rejected.
        </p>
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {rows.map((d, index) => (
            <li
              key={`${d.decidedAt}-${d.evidenceDocumentRef}-${index}`}
              className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 px-3 py-2"
            >
              <span className="flex items-center gap-2">
                <StatusBadge kind="kyc" value={d.status} />
                <span className="text-xs text-muted-foreground">{formatInstant(d.decidedAt) || 'Date not recorded'}</span>
              </span>
              <span className="flex items-center gap-2 text-xs">
                {/* Null is "not recorded", never "nobody". The subject is not shown in its place. */}
                <span className="text-muted-foreground">{d.decidedByName?.trim() || 'Name not recorded'}</span>
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
                  <span className="font-mono text-xs text-subtle-foreground">{d.evidenceDocumentRef}</span>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </details>
  );
}

/**
 * Verify / Reject, the second click in front of each -- and nothing at all once the case is decided. A decision is
 * not irreversible ({@code Party.applyKycDecision} has no guard on the old status), so changing it stays possible,
 * behind one deliberate click.
 */
function DecisionControls({
  partyId,
  chosen,
  currentStatus,
  pending,
  onArm,
  onCancel,
  onConfirm,
  busy,
}: {
  partyId: string;
  chosen: FiledDocument | null;
  currentStatus: KycStatus | undefined;
  pending: KycStatus | null;
  onArm: (status: KycStatus) => void;
  onCancel: () => void;
  onConfirm: (status: KycStatus) => void;
  busy: boolean;
}) {
  const name = chosen?.fileName?.trim() || chosen?.documentRef;
  const decided = currentStatus === 'VERIFIED' || currentStatus === 'REJECTED';

  // Tagged with the party and the status it was opened against, so a re-decision puts the controls away by itself.
  const [changing, setChanging] = useState<{ partyId: string; from: KycStatus } | null>(null);
  const reopened = changing?.partyId === partyId && changing.from === currentStatus;

  if (pending && chosen) {
    return (
      <ConfirmAct
        heading={pending === 'VERIFIED' ? 'Verify this identity?' : 'Reject this identity?'}
        tone={pending === 'VERIFIED' ? 'primary' : 'danger'}
        consequence={
          pending === 'VERIFIED' ? (
            <>
              Mark this client KYC-verified against <span className="font-medium">{name}</span>, the document open
              above. They become eligible to hold a policy.
            </>
          ) : (
            <>
              Refuse this client’s identity evidence, recorded against <span className="font-medium">{name}</span>. They
              cannot be taken on as a policyholder while their KYC reads rejected.
            </>
          )
        }
        reversal="A KYC decision can be changed later by re-deciding against fresh evidence — but it is recorded, and it gates whether this client can hold a policy in the meantime."
        confirmLabel={pending === 'VERIFIED' ? 'Verify identity' : 'Reject identity'}
        busy={busy}
        onConfirm={() => onConfirm(pending)}
        onCancel={onCancel}
      />
    );
  }

  if (decided && !reopened) {
    return (
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-control px-4 py-3">
        <p className="text-sm">
          {currentStatus === 'VERIFIED'
            ? 'Identity verified. This client may hold a policy.'
            : 'Identity rejected. This client cannot be taken on as a policyholder.'}
        </p>
        <Button size="sm" variant="outline" onClick={() => currentStatus && setChanging({ partyId, from: currentStatus })}>
          Change this decision
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-1.5">
        <Button size="sm" variant="primary" disabled={!chosen || currentStatus === 'VERIFIED'} onClick={() => onArm('VERIFIED')}>
          <Check />
          Verify
        </Button>
        <Button size="sm" variant="danger" disabled={!chosen || currentStatus === 'REJECTED'} onClick={() => onArm('REJECTED')}>
          <X />
          Reject
        </Button>
        {reopened && (
          <Button size="sm" variant="ghost" onClick={() => setChanging(null)}>
            Keep it as it is
          </Button>
        )}
      </div>
      {/* Why the controls are dead, said once. A disabled pair with no reason reads as a broken console. */}
      {!chosen && (
        <p className="text-xs text-muted-foreground">Open identity evidence first — a decision is recorded against it.</p>
      )}
      {chosen && currentStatus === 'VERIFIED' && (
        <p className="text-xs text-muted-foreground">
          Already verified. Reject to overturn it, or re-verify against fresh evidence by uploading it first.
        </p>
      )}
      {chosen && currentStatus === 'REJECTED' && (
        <p className="text-xs text-muted-foreground">Already rejected. Verify to overturn it.</p>
      )}
    </div>
  );
}

/**
 * Adding a document: say what it is, then drop it. The dropzone's keyboard handling drops react-dropzone's unnamed
 * focusable root (`noKeyboard`) and puts the real file input in the tab order with a name.
 */
function DocumentUpload({
  partyId,
  verb = 'Add',
  onUploaded,
}: {
  partyId: string;
  verb?: 'Add' | 'Send';
  onUploaded: (documentRef: string) => void;
}) {
  const uploadDocument = usePartyStore((s) => s.uploadKycEvidence);
  const resetUpload = usePartyStore((s) => s.resetUploadKycEvidence);
  const loadPartyDocuments = usePartyStore((s) => s.loadPartyDocuments);
  const uploading = usePartyStore(selectUploadingKycEvidence(partyId));
  const [type, setType] = useState<ClientDocumentType>('KYC_EVIDENCE');

  useEffect(() => {
    resetUpload(partyId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [partyId]);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    accept: ACCEPTED_TYPES,
    multiple: false,
    noKeyboard: true,
    onDrop: (accepted) => {
      if (accepted[0]) void submit(accepted[0]);
    },
  });

  async function submit(file: File) {
    await uploadDocument(partyId, file, type);
    const result = usePartyStore.getState().uploadingKycEvidence[partyId];
    if (result?.status === 'success' && result.data?.documentRef) {
      // The list this file now belongs to is a separate read, so it does not know yet.
      await loadPartyDocuments(partyId);
      onUploaded(result.data.documentRef);
    }
  }

  return (
    <section aria-label={`${verb} a document`} className="space-y-2">
      <h3 className="text-sm font-semibold">{verb} a document</h3>
      <div role="radiogroup" aria-label="Document type" className="inline-flex rounded-lg bg-control p-0.5">
        {(Object.keys(TYPE_LABEL) as ClientDocumentType[]).map((t) => (
          <button
            key={t}
            type="button"
            role="radio"
            aria-checked={type === t}
            onClick={() => setType(t)}
            className={cn(
              'rounded-md px-3 py-1 text-sm transition-colors',
              type === t ? 'bg-surface font-medium text-foreground shadow-raise' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {TYPE_LABEL[t]}
          </button>
        ))}
      </div>
      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center gap-1 rounded-lg border border-dashed border-border-strong px-4 py-5 text-center transition-colors focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent',
          isDragActive ? 'border-accent bg-hover' : 'hover:bg-hover',
          uploading.status === 'loading' && 'pointer-events-none opacity-60',
        )}
      >
        <input
          {...getInputProps({ tabIndex: 0, 'aria-label': `${verb} ${TYPE_LABEL[type].toLowerCase()}` })}
          disabled={uploading.status === 'loading'}
        />
        <FileCheck className="size-5 text-muted-foreground" aria-hidden />
        <p className="text-sm text-muted-foreground" aria-live="polite">
          {uploading.status === 'loading'
            ? 'Uploading…'
            : isDragActive
              ? 'Drop the file'
              : type === 'KYC_EVIDENCE'
                ? 'Drop an ID scan or proof of address, or click to browse'
                : 'Drop the signed form, or click to browse'}
        </p>
        <p className="text-xs text-subtle-foreground">JPEG, PNG or PDF</p>
      </div>
      {uploading.status === 'error' && uploading.error && <InlineError error={uploading.error} />}
    </section>
  );
}
