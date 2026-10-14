import { useEffect, useState } from 'react';
import { Download, Maximize2 } from 'lucide-react';
import { downloadStoredDocument } from '@/api/documents';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { saveBlob } from '@/lib/download';

/**
 * A stored document, shown where the decision about it is taken.
 *
 * <b>Why inline, when `EvidencePanel` deliberately opens a new tab instead.</b> That panel's
 * reasoning is sound for a claim: the closed upload allowlist is exactly what a browser
 * renders natively, so a bespoke viewer duplicates the browser for nothing. A KYC decision is
 * the case it does not cover. The reviewer is comparing the scan against the name, the date of
 * birth and the ID number typed on the same screen, and then pressing Verify on that screen --
 * a new tab puts the evidence and the decision in two places and asks a person to carry the
 * details between them from memory. Here the evidence and the act are one view (the user's
 * decision, 2026-10-14). Claims keeps the new tab; this is not a precedent to roll out.
 *
 * Full size is still one click away, because an ID number at 400px tall is not always legible
 * and zooming is the browser's job, not this component's.
 *
 * STAFF ONLY, inherited from the endpoint rather than from anything here -- see
 * `downloadStoredDocument`. An agents-realm screen must not mount this; it would 403 on a file
 * that agent uploaded themselves.
 */
export function DocumentViewer({
  documentRef,
  contentType,
  fileName,
}: {
  documentRef: string;
  // `| undefined` spelled out, not just optional: `exactOptionalPropertyTypes` is on, and every
  // field of the generated document view is optional, so a caller passing one through would
  // otherwise have to strip the undefined itself.
  contentType?: string | null | undefined;
  fileName?: string | null | undefined;
}) {
  /*
   * Both outcomes carry the ref they belong to, and the render below reads them only when that
   * ref is the one asked for now.
   *
   * The obvious shape -- one `state` set to `{status:'loading'}` at the top of the effect --
   * is a setState in an effect body, which this project lints as an error (cascading renders).
   * Tagging the result instead means switching documents needs no reset at all: the previous
   * document's blob simply stops matching, and the frame reads as loading again.
   */
  const [loaded, setLoaded] = useState<{ ref: string; url: string; blob: Blob } | null>(null);
  const [failed, setFailed] = useState<{ ref: string; error: ApiError } | null>(null);

  useEffect(() => {
    // `cancelled` rather than an AbortController: the fetch helper takes no signal, and the
    // damage a late response does here is holding a blob for a document the reader has already
    // navigated away from -- which this stops. The object URL is revoked either way.
    let cancelled = false;
    let created: string | null = null;

    void downloadStoredDocument(documentRef)
      .then((blob) => {
        if (cancelled) return;
        created = URL.createObjectURL(blob);
        setLoaded({ ref: documentRef, url: created, blob });
      })
      .catch((cause: unknown) => {
        if (!cancelled) setFailed({ ref: documentRef, error: toApiError(cause) });
      });

    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [documentRef]);

  const name = fileName?.trim() || documentRef;
  const ready = loaded?.ref === documentRef ? loaded : null;
  const error = failed?.ref === documentRef ? failed.error : null;

  // Failure first, so a refused read is never shown as a load that is still going.
  if (error) {
    return (
      <InlineError
        error={error}
        lead={`Could not open ${name}`}
      >
        <p className="text-xs">
          The file is still on record — this is a problem reading it back, not a missing
          document. Try again, or download it.
        </p>
      </InlineError>
    );
  }

  if (!ready) {
    return (
      <div className="flex h-96 items-center justify-center rounded-md border border-border bg-surface">
        {/* Visible and announced: this is the only thing reporting a fetch that can take a
            while on an office connection, and a blank frame reads as a broken image. */}
        <p className="text-xs text-muted-foreground" aria-live="polite">
          Loading {name}…
        </p>
      </div>
    );
  }

  const kind = renderableKind(contentType, name);

  return (
    <div className="space-y-2">
      <div className="flex h-96 items-center justify-center overflow-hidden rounded-md border border-border bg-band lg:h-[30rem]">
        {kind === 'image' && (
          // `alt` is the file name, which is the only true description available: nothing on
          // this platform knows what a scan depicts. A written-out "ID scan" would be a guess
          // announced as a fact.
          <img src={ready.url} alt={name} className="max-h-full max-w-full object-contain" />
        )}
        {kind === 'pdf' && (
          <object data={ready.url} type="application/pdf" className="h-full w-full" aria-label={name}>
            <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
              <p className="text-sm">This browser will not display the PDF here.</p>
              <p className="text-xs text-muted-foreground">Open it full size or download it to read it.</p>
            </div>
          </object>
        )}
        {kind === 'other' && (
          <div className="flex flex-col items-center gap-2 px-6 text-center">
            <p className="text-sm">{name}</p>
            {/* Named rather than hidden: a file the viewer cannot draw is a fact about the
                file, and "nothing appeared" is the one reading that would be wrong. */}
            <p className="text-xs text-muted-foreground">
              {contentType ? `Stored as ${contentType}` : 'Stored without a content type'} — it
              cannot be shown here. Download it to read it.
            </p>
          </div>
        )}
      </div>

      <div className="flex items-center gap-1.5">
        <Button
          size="sm"
          variant="ghost"
          onClick={() => window.open(ready.url, '_blank', 'noopener')}
        >
          <Maximize2 />
          Open full size
        </Button>
        <Button size="sm" variant="ghost" onClick={() => saveBlob(ready.blob, name)}>
          <Download />
          Download
        </Button>
      </div>
    </div>
  );
}

/**
 * What this viewer can actually draw.
 *
 * Keyed on the stored content type, falling back to the file extension for the one case the
 * upload allowlist genuinely produces: `application/octet-stream`, which a browser sends for a
 * PDF or a photo it did not recognise. Treating that as unrenderable would hide a readable
 * document behind a download button on the strength of a header the uploader's browser got wrong.
 */
function renderableKind(contentType: string | null | undefined, fileName: string): 'image' | 'pdf' | 'other' {
  const type = (contentType ?? '').toLowerCase();
  if (type.startsWith('image/')) return 'image';
  if (type === 'application/pdf') return 'pdf';

  if (type === '' || type === 'application/octet-stream') {
    const ext = fileName.toLowerCase().split('.').pop() ?? '';
    if (['jpg', 'jpeg', 'png'].includes(ext)) return 'image';
    if (ext === 'pdf') return 'pdf';
  }
  return 'other';
}
