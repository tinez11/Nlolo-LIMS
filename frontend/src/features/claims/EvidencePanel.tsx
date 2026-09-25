import { useEffect, useState } from 'react';
import { useDropzone } from 'react-dropzone';
import { Paperclip, UploadCloud } from 'lucide-react';
import { downloadClaimEvidence } from '@/api/claims';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAttachingEvidence, selectEvidence, useClaimStore } from '@/store/claimStore';
import { Input } from '@/components/ui/input';

/**
 * `POST /claims/{claimId}/evidence` + `GET .../evidence` + `GET .../evidence/{ref}`
 * -- built since M6/M11 (real photos, certificates, reports, stored in MinIO) but
 * with zero staff UI until this staff-portal CRUD audit found the gap: an
 * adjudicator could not see the evidence they were deciding a claim against.
 *
 * Upload is allowlisted server-side to exactly four content types (image/jpeg,
 * image/png, application/pdf, application/octet-stream) -- `accept` below
 * mirrors that so a doomed upload never leaves the browser, but the real 422 is
 * still the authority (ClaimEvidenceController.allowedContentTypeOrThrow), not
 * this client-side hint.
 *
 * "View" fetches the real file as a blob and opens it in a new tab rather than
 * an inline viewer: the closed content-type set is exactly what every browser
 * already renders natively (image, PDF), so a bespoke viewer would duplicate
 * that for no benefit. The blob URL is revoked only on tab close (the browser's
 * own job), not here -- revoking it immediately would race the new tab's own load.
 */
export function EvidencePanel({ claimId, canAttach }: { claimId: string; canAttach: boolean }) {
  const evidence = useClaimStore(selectEvidence(claimId));
  const loadEvidence = useClaimStore((s) => s.loadEvidence);

  useEffect(() => {
    void loadEvidence(claimId);
  }, [claimId, loadEvidence]);

  return (
    <div className="space-y-3">
      {canAttach ? (
        <UploadForm claimId={claimId} />
      ) : (
        <p className="px-4 pt-3 text-xs text-muted-foreground">
          This claim is SETTLED -- reopen it before attaching new evidence.
        </p>
      )}
      {renderList()}
    </div>
  );

  function renderList() {
    if (isInitialLoad(evidence)) return <LoadingBlock />;
    if (evidence.status === 'error' && evidence.error && evidence.data === null) {
      return <ErrorPanel error={evidence.error} onRetry={() => void loadEvidence(claimId)} />;
    }
    const rows = evidence.data ?? [];
    if (rows.length === 0) {
      return <EmptyState title="No evidence" description="Nothing has been attached to this claim yet." />;
    }
    return (
      <ul className="divide-y divide-border border-t border-border">
        {rows.map((item) => (
          <li key={item.claimEvidenceId} className="flex items-center justify-between gap-3 px-4 py-2.5">
            <div className="min-w-0">
              <p className="truncate text-sm">{item.description || 'No description'}</p>
              <p className="text-xs text-muted-foreground">
                {/* The name captured from the uploader's token -- never `uploadedBy`, which is
                    their Keycloak subject. Evidence attached before the name was captured has
                    none to recover, and says so. */}
                {item.uploadedByName?.trim() || 'Name not recorded'} · {formatInstant(item.uploadedAt)}
              </p>
            </div>
            <Button
              size="sm"
              variant="ghost"
              className="shrink-0"
              onClick={() => void viewEvidence(claimId, item.documentRef)}
            >
              View
            </Button>
          </li>
        ))}
      </ul>
    );
  }
}

async function viewEvidence(claimId: string, documentRef: string): Promise<void> {
  const blob = await downloadClaimEvidence(claimId, documentRef);
  window.open(URL.createObjectURL(blob), '_blank', 'noopener');
}

const ACCEPTED_EVIDENCE_TYPES = {
  'image/jpeg': ['.jpg', '.jpeg'],
  'image/png': ['.png'],
  'application/pdf': ['.pdf'],
};

function UploadForm({ claimId }: { claimId: string }) {
  const attachEvidence = useClaimStore((s) => s.attachEvidence);
  const resetAttachEvidence = useClaimStore((s) => s.resetAttachEvidence);
  const attaching = useClaimStore(selectAttachingEvidence(claimId));
  const [description, setDescription] = useState('');

  useEffect(() => {
    resetAttachEvidence(claimId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claimId]);

  const { getRootProps, getInputProps, isDragActive, acceptedFiles } = useDropzone({
    accept: ACCEPTED_EVIDENCE_TYPES,
    multiple: false,
    onDrop: (accepted) => {
      if (accepted[0]) void submit(accepted[0]);
    },
  });

  async function submit(file: File) {
    await attachEvidence(claimId, file, description.trim() || undefined);
    if (useClaimStore.getState().attachingEvidence[claimId]?.status === 'success') {
      setDescription('');
    }
  }

  return (
    <div className="space-y-2 px-4 pt-3">
      <FormField label="Description (optional)">
        <Input
          inputSize="sm"
          placeholder="Death certificate, page 1"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </FormField>

      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center gap-1.5 rounded-md border border-dashed border-border px-4 py-6 text-center transition-colors',
          isDragActive ? 'border-accent bg-hover' : 'hover:bg-hover',
          attaching.status === 'loading' && 'pointer-events-none opacity-60',
        )}
      >
        <input {...getInputProps()} disabled={attaching.status === 'loading'} />
        <UploadCloud className="size-5 text-muted-foreground" />
        <p className="text-xs text-muted-foreground">
          {attaching.status === 'loading'
            ? 'Uploading…'
            : isDragActive
              ? 'Drop the file'
              : 'Drag a file here, or click to browse'}
        </p>
        <p className="text-xs text-subtle-foreground">JPEG, PNG, or PDF</p>
      </div>

      {acceptedFiles[0] && attaching.status !== 'loading' && (
        <p className="flex items-center gap-1 text-xs text-muted-foreground">
          <Paperclip className="size-3" />
          {acceptedFiles[0].name}
        </p>
      )}

      {attaching.status === 'error' && attaching.error && (
        <p role="alert" className="text-xs text-status-danger-fg">
          {attaching.error.detail ?? attaching.error.title}
        </p>
      )}
    </div>
  );
}
