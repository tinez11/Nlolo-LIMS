'use client';

import { useRef, useState, type ChangeEvent } from 'react';
import { Label } from '@/components/ui/label';
import { useSubmitGuard } from '@/hooks/use-submit-guard';

/**
 * UI-level shape, deliberately NOT the wire `ClaimEvidenceView` (which carries no original
 * filename -- only an optional free-text `description`). Callers (the claim detail page) map the
 * real API response down to this: `fileName` is the label shown next to the download link,
 * typically the evidence's `description` when present, falling back to the opaque `documentRef`.
 */
export type EvidenceItem = {
  documentRef: string;
  fileName: string;
};

/**
 * Mirrors M11's server-side upload allowlist (`ClaimEvidenceController.ALLOWED_EVIDENCE_CONTENT_TYPES`)
 * MINUS `application/octet-stream`: that entry exists server-side as the honest "content type
 * unknown" fallback for a part with no Content-Type header at all, not as a type a customer would
 * ever deliberately pick from a file dialog. Rejecting anything else here is a UX nicety only --
 * the server enforces its own allowlist regardless of what this check lets through.
 */
const ALLOWED_CONTENT_TYPES = ['image/jpeg', 'image/png', 'application/pdf'];

export function EvidencePanel({
  claimId, evidence, onUpload,
}: {
  claimId: string;
  evidence: EvidenceItem[];
  onUpload: (file: File) => Promise<void>;
}) {
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const { submit, isSubmitting } = useSubmitGuard(async (file: File) => {
    await onUpload(file);
  });

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;

    if (!ALLOWED_CONTENT_TYPES.includes(file.type)) {
      setError('That file type is not supported. Please attach a JPEG, PNG or PDF file.');
      if (inputRef.current) inputRef.current.value = '';
      return;
    }

    setError(null);
    await submit(file);
    if (inputRef.current) inputRef.current.value = '';
  }

  return (
    <div className="space-y-3">
      {evidence.length === 0 ? (
        <p className="text-sm text-muted-foreground">No evidence attached yet.</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {evidence.map((item) => (
            <li key={item.documentRef}>
              {/*
                A plain <a>, not a fetch-and-blob download: the Route Handler at this path is the
                true binary passthrough (callBackendRaw) for M11's dual-ownership-checked download
                -- letting the browser navigate/download it directly means the file is never
                buffered or re-exposed by anything the portal itself controls.
              */}
              <a
                href={`/api/claims/${encodeURIComponent(claimId)}/evidence/${encodeURIComponent(item.documentRef)}`}
                className="underline underline-offset-2"
              >
                {item.fileName}
              </a>
            </li>
          ))}
        </ul>
      )}

      <div className="space-y-1">
        <Label htmlFor="evidence-file">Attach a file</Label>
        <input
          ref={inputRef}
          id="evidence-file"
          type="file"
          // Deliberately NO `accept` attribute: browsers (and @testing-library/user-event) filter
          // non-matching files out of the picker silently, with no change event at all -- which
          // would swallow the JS-level check below along with its user-visible alert message. The
          // allowlist is enforced entirely in `handleFileChange` so a disallowed pick always gets
          // real feedback instead of just vanishing from the dialog.
          disabled={isSubmitting}
          onChange={(event) => { void handleFileChange(event); }}
          className="block text-sm text-muted-foreground file:mr-2 file:rounded-md file:border-0 file:bg-secondary file:px-2.5 file:py-1 file:text-sm file:font-medium"
        />
      </div>

      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
