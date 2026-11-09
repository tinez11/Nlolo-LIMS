import { PenLine, Upload } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  addSignatory,
  getSignature,
  updateSignatory,
  uploadSignature,
  type DocumentKind,
  type Signatory,
  type SignatoryInput,
} from '@/api/organisation';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { toApiError, type ApiError } from '@/lib/apiError';

const KINDS: { value: DocumentKind; label: string }[] = [
  { value: 'POLICY_SCHEDULE', label: 'Policy schedules' },
  { value: 'RECEIPT', label: 'Receipts' },
  { value: 'PAYOUT_ADVICE', label: 'Payout advices' },
  { value: 'QUOTATION', label: 'Quotations' },
  { value: 'STATEMENT', label: 'Statements and schedules' },
];

const today = () => new Date().toISOString().slice(0, 10);

/**
 * Who signs which documents, from when (2026-11-09). A document carries the signatory in force on the day it was
 * issued, so a reprinted receipt keeps its signature after a signatory changes. Until the real signature arrives, a
 * mock-up drawn from the name stands in (the user: "will send it, build one of mock up").
 */
export function SignatoriesTab({ signatories, onSaved }: { signatories: Signatory[]; onSaved: () => void }) {
  const [adding, setAdding] = useState(signatories.length === 0);
  return (
    <div className="space-y-5 pt-5">
      {signatories.length > 0 && (
        <ul className="grid gap-4 lg:grid-cols-2" aria-label="Signatories">
          {signatories.map((s) => <SignatoryCard key={s.signatoryId} signatory={s} onSaved={onSaved} />)}
        </ul>
      )}
      {adding ? (
        <Panel title="Add a signatory" subtitle="Their signature is uploaded once they are added.">
          <SignatoryForm
            initial={{ name: '', title: '', effectiveFrom: today(), retiredOn: null, signs: [] }}
            submitLabel="Add the signatory"
            onCancel={signatories.length > 0 ? () => setAdding(false) : undefined}
            onSubmit={async (input) => {
              await addSignatory(input);
              setAdding(false);
              onSaved();
            }}
          />
        </Panel>
      ) : (
        <Button variant="secondary" onClick={() => setAdding(true)}>Add a signatory</Button>
      )}
    </div>
  );
}

function SignatoryCard({ signatory: s, onSaved }: { signatory: Signatory; onSaved: () => void }) {
  const [editing, setEditing] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!s.hasSignature) return undefined;
    let live = true;
    let made: string | null = null;
    void getSignature(s.signatoryId).then((blob) => {
      if (!live || !blob) return;
      made = URL.createObjectURL(blob);
      setUrl(made);
    });
    return () => {
      live = false;
      if (made) URL.revokeObjectURL(made);
    };
  }, [s.signatoryId, s.hasSignature, version]);

  async function upload(file: File) {
    setPending(true);
    setError(null);
    try {
      await uploadSignature(s.signatoryId, file);
      setVersion((v) => v + 1);
      onSaved();
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  const retired = s.retiredOn !== null && s.retiredOn !== undefined && s.retiredOn <= today();
  return (
    <li>
      <Panel
        title={s.name}
        subtitle={`${s.title} · signs from ${formatDate(s.effectiveFrom)}${s.retiredOn ? ` until ${formatDate(s.retiredOn)}` : ''}${retired ? ' (no longer signs)' : ''}`}
        actions={!editing ? <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>Change</Button> : undefined}
      >
        {editing ? (
          <SignatoryForm
            initial={{ name: s.name, title: s.title, effectiveFrom: s.effectiveFrom, retiredOn: s.retiredOn ?? null, signs: s.signs }}
            submitLabel="Save"
            onCancel={() => setEditing(false)}
            onSubmit={async (input) => {
              await updateSignatory(s.signatoryId, input);
              setEditing(false);
              onSaved();
            }}
          />
        ) : (
          <div className="space-y-3 px-4 pb-4">
            <div className="grid h-20 place-items-center rounded-lg border border-dashed border-border-strong bg-white">
              {s.hasSignature && url ? (
                <img src={url} alt={`Signature of ${s.name}`} className="max-h-16 max-w-56 object-contain" />
              ) : (
                <span className="text-xs text-muted-foreground">No signature yet: documents show the name and title only.</span>
              )}
            </div>
            <p className="text-sm">
              <span className="text-muted-foreground">Signs: </span>
              {s.signs.length === 0 ? 'nothing yet' : s.signs.map((k) => KINDS.find((x) => x.value === k)?.label ?? k).join(', ')}
            </p>
            <div className="flex flex-wrap gap-2">
              <input ref={input} type="file" accept="image/png,image/jpeg" className="sr-only"
                aria-label={`Choose a signature file for ${s.name}`}
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) void upload(file);
                  e.target.value = '';
                }} />
              <Button size="sm" variant="secondary" pending={pending} onClick={() => input.current?.click()}>
                <Upload className="size-4" aria-hidden />
                {s.hasSignature ? 'Replace the signature' : 'Upload the signature'}
              </Button>
              {!s.hasSignature && (
                <Button size="sm" variant="ghost" disabled={pending}
                  onClick={() => void mockSignature(s.name).then((file) => file && upload(file))}>
                  <PenLine className="size-4" aria-hidden />
                  Use a mock-up signature
                </Button>
              )}
            </div>
            {error && <InlineError lead="The signature was not saved" error={error} />}
          </div>
        )}
      </Panel>
    </li>
  );
}

function SignatoryForm({ initial, submitLabel, onSubmit, onCancel }: {
  initial: SignatoryInput;
  submitLabel: string;
  onSubmit: (input: SignatoryInput) => Promise<void>;
  onCancel?: (() => void) | undefined;
}) {
  const [draft, setDraft] = useState<SignatoryInput>(initial);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);

  async function submit() {
    setPending(true);
    setError(null);
    try {
      await onSubmit({ ...draft, retiredOn: draft.retiredOn || null });
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  const toggle = (kind: DocumentKind) => setDraft((d) => ({
    ...d,
    signs: d.signs.includes(kind) ? d.signs.filter((k) => k !== kind) : [...d.signs, kind],
  }));

  return (
    <div className="space-y-4 px-4 pb-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField required label="Name">
          <Input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        </FormField>
        <FormField required label="Title" hint="Such as Chief Executive Officer.">
          <Input value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
        </FormField>
        <FormField required label="Signs from">
          <Input type="date" value={draft.effectiveFrom} onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })} />
        </FormField>
        <FormField label="Stops signing on" hint="Leave empty while they still sign.">
          <Input type="date" value={draft.retiredOn ?? ''} onChange={(e) => setDraft({ ...draft, retiredOn: e.target.value || null })} />
        </FormField>
      </div>
      <fieldset>
        <legend className="mb-1 text-sm font-medium">Signs</legend>
        <div className="grid gap-x-4 sm:grid-cols-2">
          {KINDS.map((k) => (
            <CheckboxField key={k.value} label={k.label} checked={draft.signs.includes(k.value)} onChange={() => toggle(k.value)} />
          ))}
        </div>
      </fieldset>
      {error && <InlineError lead="The signatory was not saved" error={error} />}
      <div className="flex gap-2">
        <Button variant="primary" pending={pending} onClick={() => void submit()}>{submitLabel}</Button>
        {onCancel && <Button variant="ghost" disabled={pending} onClick={onCancel}>Cancel</Button>}
      </div>
    </div>
  );
}

/**
 * A stand-in signature drawn from the name in a script hand, as a PNG: what documents carry until the real one is
 * uploaded (2026-11-09, the user will send it). Null where the browser cannot draw one.
 */
async function mockSignature(name: string): Promise<File | null> {
  const canvas = document.createElement('canvas');
  canvas.width = 640;
  canvas.height = 180;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.fillStyle = '#1F2A44';
  ctx.strokeStyle = '#1F2A44';
  ctx.font = "italic 64px 'Segoe Script', 'Brush Script MT', 'Lucida Handwriting', cursive";
  ctx.textBaseline = 'middle';
  ctx.fillText(name, 24, 84, 590);
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  ctx.moveTo(30, 140);
  ctx.bezierCurveTo(200, 120, 380, 160, 600, 128);
  ctx.stroke();
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
  return blob ? new File([blob], 'mock-signature.png', { type: 'image/png' }) : null;
}
