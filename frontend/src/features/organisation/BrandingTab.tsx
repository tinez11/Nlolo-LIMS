import { ImageUp, Trash2 } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import {
  getLogo,
  previewDocument,
  removeLogo,
  saveBranding,
  saveProfile,
  uploadLogo,
  type Branding,
  type OrganisationSettings,
  type Profile,
} from '@/api/organisation';
import { BranchSelect } from '@/components/BranchSelect';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { Input, Select, Textarea } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { toApiError, type ApiError } from '@/lib/apiError';

const LAYOUTS: { value: Branding['layout']; label: string; note: string }[] = [
  { value: 'CLASSIC', label: 'Classic', note: 'Logo and name left, contacts right, a rule in your colour.' },
  { value: 'BANDED', label: 'Banded', note: 'A band of your colour across the top.' },
  { value: 'MINIMAL', label: 'Minimal', note: 'A quiet name line; contacts in the footer.' },
];

/** Who the company is and how its documents look, beside a live preview of a receipt in the draft (2026-11-09). */
export function BrandingTab({ settings, onSaved }: { settings: OrganisationSettings; onSaved: () => void }) {
  const [profile, setProfile] = useState<Profile>(settings.profile);
  const [branding, setBranding] = useState<Branding>(settings.branding);
  const [profileError, setProfileError] = useState<ApiError | null>(null);
  const [brandingError, setBrandingError] = useState<ApiError | null>(null);
  const [savingProfile, setSavingProfile] = useState(false);
  const [savingBranding, setSavingBranding] = useState(false);
  const [profileSaved, setProfileSaved] = useState(false);
  const [brandingSaved, setBrandingSaved] = useState(false);

  const setP = (field: keyof Profile) => (value: string) => {
    setProfileSaved(false);
    setProfile((p) => ({ ...p, [field]: value === '' ? null : value }));
  };
  const setB = <K extends keyof Branding>(field: K, value: Branding[K]) => {
    setBrandingSaved(false);
    setBranding((b) => ({ ...b, [field]: value }));
  };

  async function submitProfile() {
    setSavingProfile(true);
    setProfileError(null);
    try {
      setProfile(await saveProfile({ ...profile, legalName: profile.legalName.trim() }));
      setProfileSaved(true);
      onSaved();
    } catch (e) {
      setProfileError(toApiError(e));
    } finally {
      setSavingProfile(false);
    }
  }

  async function submitBranding() {
    setSavingBranding(true);
    setBrandingError(null);
    try {
      setBranding(await saveBranding(branding));
      setBrandingSaved(true);
      onSaved();
    } catch (e) {
      setBrandingError(toApiError(e));
    } finally {
      setSavingBranding(false);
    }
  }

  return (
    <div className="grid gap-5 pt-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <div className="space-y-5">
        <Panel title="Company" subtitle="Printed on every document's letterhead.">
          <div className="grid gap-4 px-4 pb-4 sm:grid-cols-2">
            <FormField required label="Legal name">
              <Input value={profile.legalName} onChange={(e) => setP('legalName')(e.target.value)} />
            </FormField>
            <FormField label="Trading name" hint="Heads the documents when set.">
              <Input value={profile.tradingName ?? ''} onChange={(e) => setP('tradingName')(e.target.value)} />
            </FormField>
            <FormField label="TIRA registration number">
              <Input value={profile.registrationNumber ?? ''} onChange={(e) => setP('registrationNumber')(e.target.value)} />
            </FormField>
            <FormField label="TIN">
              <Input value={profile.tin ?? ''} onChange={(e) => setP('tin')(e.target.value)} />
            </FormField>
            <FormField label="Head-office address" className="sm:col-span-2">
              <Textarea rows={2} value={profile.address ?? ''} onChange={(e) => setP('address')(e.target.value)} />
            </FormField>
            <FormField label="Phone">
              <Input value={profile.phone ?? ''} onChange={(e) => setP('phone')(e.target.value)} />
            </FormField>
            <FormField label="Email">
              <Input type="email" value={profile.email ?? ''} onChange={(e) => setP('email')(e.target.value)} />
            </FormField>
            <FormField label="Website">
              <Input value={profile.website ?? ''} onChange={(e) => setP('website')(e.target.value)} />
            </FormField>
            <FormField label="Head office" hint="The branch a sale takes when nothing else names one.">
              <BranchSelect value={profile.headOfficeBranch ?? ''} onChange={(code) => setP('headOfficeBranch')(code)} />
            </FormField>
            <FormField label="Tagline" hint="A line under the name, such as Protecting families since 1998." className="sm:col-span-2">
              <Input value={profile.tagline ?? ''} onChange={(e) => setP('tagline')(e.target.value)} />
            </FormField>
          </div>
          <SaveRow error={profileError} pending={savingProfile} saved={profileSaved} onSave={() => void submitProfile()}
            label="Save company details" lead="The company details were not saved" />
        </Panel>

        <Panel title="Look" subtitle="Your logo, colours, typeface and layout, on every PDF and Excel export.">
          <div className="space-y-5 px-4 pb-4">
            <LogoField hasLogo={branding.hasLogo} onChanged={(has) => { setB('hasLogo', has); onSaved(); }} />
            <div className="grid gap-4 sm:grid-cols-2">
              <ColourField label="Main colour" hint="Letterhead, headings, table headers."
                value={branding.primaryColour} onChange={(v) => setB('primaryColour', v)} />
              <ColourField label="Accent colour" hint="Bands and accents."
                value={branding.secondaryColour} onChange={(v) => setB('secondaryColour', v)} />
            </div>
            <fieldset>
              <legend className="mb-2 text-sm font-medium">Layout</legend>
              <div className="grid gap-3 sm:grid-cols-3">
                {LAYOUTS.map((l) => (
                  <button key={l.value} type="button" aria-pressed={branding.layout === l.value}
                    onClick={() => setB('layout', l.value)}
                    className={cn('rounded-lg border p-2 text-left transition-colors hover:bg-hover',
                      branding.layout === l.value ? 'border-accent ring-2 ring-accent/40' : 'border-border')}>
                    <LayoutSketch layout={l.value} primary={branding.primaryColour} secondary={branding.secondaryColour} />
                    <span className="mt-2 block text-sm font-medium">{l.label}</span>
                    <span className="block text-xs text-muted-foreground">{l.note}</span>
                  </button>
                ))}
              </div>
            </fieldset>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label="Typeface">
                <Select value={branding.typeface} onChange={(e) => setB('typeface', e.target.value as Branding['typeface'])}>
                  <option value="SANS">Sans (Helvetica)</option>
                  <option value="SERIF">Serif (Times)</option>
                </Select>
              </FormField>
              <FormField label="Paper">
                <Select value={branding.paper} onChange={(e) => setB('paper', e.target.value as Branding['paper'])}>
                  <option value="A4">A4</option>
                  <option value="LETTER">Letter</option>
                </Select>
              </FormField>
              <FormField label="Footer" hint="On customer documents, such as Regulated by TIRA, or your bank details."
                className="sm:col-span-2">
                <Input value={branding.footerText ?? ''} maxLength={300}
                  onChange={(e) => setB('footerText', e.target.value === '' ? null : e.target.value)} />
              </FormField>
              <FormField label="Report confidentiality line" hint="In the footer of every report export." className="sm:col-span-2">
                <Input value={branding.reportConfidentiality ?? ''} maxLength={200}
                  onChange={(e) => setB('reportConfidentiality', e.target.value === '' ? null : e.target.value)} />
              </FormField>
            </div>
          </div>
          <SaveRow error={brandingError} pending={savingBranding} saved={brandingSaved} onSave={() => void submitBranding()}
            label="Save branding" lead="The branding was not saved" />
        </Panel>
      </div>

      <div className="xl:sticky xl:top-4 xl:self-start">
        <Preview profile={profile} branding={branding} />
      </div>
    </div>
  );
}

function SaveRow({ error, pending, saved, onSave, label, lead }: {
  error: ApiError | null;
  pending: boolean;
  saved: boolean;
  onSave: () => void;
  label: string;
  lead: string;
}) {
  return (
    <div className="space-y-2 border-t border-border px-4 py-3">
      {error && <InlineError lead={lead} error={error} />}
      <div className="flex items-center gap-3">
        <Button variant="primary" pending={pending} onClick={onSave}>{label}</Button>
        {saved && <span role="status" className="text-sm text-status-success-fg">Saved. New documents use it now.</span>}
      </div>
    </div>
  );
}

function ColourField({ label, hint, value, onChange }: { label: string; hint: string; value: string; onChange: (v: string) => void }) {
  const valid = /^#[0-9A-Fa-f]{6}$/.test(value);
  return (
    <FormField label={label} hint={hint} error={valid ? undefined : 'Write it as #RRGGBB, for example #17324D.'}>
      <div className="flex items-center gap-2">
        <input type="color" aria-label={`${label} picker`} value={valid ? value : '#000000'}
          onChange={(e) => onChange(e.target.value.toUpperCase())}
          className="h-9 w-12 shrink-0 cursor-pointer rounded-md border border-border-strong bg-surface p-0.5" />
        <Input value={value} onChange={(e) => onChange(e.target.value.trim())} maxLength={7} className="font-mono uppercase" />
      </div>
    </FormField>
  );
}

/** A thumbnail of each layout in the draft colours: what the top of a page looks like. */
function LayoutSketch({ layout, primary, secondary }: { layout: Branding['layout']; primary: string; secondary: string }) {
  return (
    <span aria-hidden className="block h-20 overflow-hidden rounded-md border border-border bg-white p-2">
      {layout === 'BANDED' ? (
        <>
          <span className="-mx-2 -mt-2 mb-2 flex h-6 items-center gap-1 px-2" style={{ background: primary }}>
            <span className="h-3 w-3 rounded-sm bg-white" />
            <span className="h-1.5 w-10 rounded bg-white/80" />
          </span>
          <span className="-mx-2 -mt-2 mb-2 block h-0.5" style={{ background: secondary }} />
        </>
      ) : layout === 'MINIMAL' ? (
        <span className="mb-2 flex items-center justify-between">
          <span className="h-1.5 w-10 rounded" style={{ background: primary }} />
          <span className="h-3 w-3 rounded-sm bg-border-strong" />
        </span>
      ) : (
        <>
          <span className="flex items-center gap-1">
            <span className="h-4 w-4 rounded-sm bg-border-strong" />
            <span className="h-1.5 w-10 rounded" style={{ background: primary }} />
            <span className="ml-auto h-1 w-8 rounded bg-border-strong" />
          </span>
          <span className="mt-1.5 mb-2 block h-0.5" style={{ background: primary }} />
        </>
      )}
      <span className="block h-2 w-16 rounded bg-heading" />
      <span className="mt-2 block h-2 rounded" style={{ background: primary, opacity: 0.25 }} />
      <span className="mt-1 block h-1.5 rounded bg-border" />
      <span className="mt-1 block h-1.5 rounded bg-surface-muted" />
    </span>
  );
}

function LogoField({ hasLogo, onChanged }: { hasLogo: boolean; onChanged: (has: boolean) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState(false);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (!hasLogo) return undefined;
    let live = true;
    let made: string | null = null;
    void getLogo().then((blob) => {
      if (!live || !blob) return;
      made = URL.createObjectURL(blob);
      setUrl(made);
    });
    return () => {
      live = false;
      if (made) URL.revokeObjectURL(made);
    };
  }, [hasLogo, version]);

  async function upload(file: File) {
    setPending(true);
    setError(null);
    try {
      await uploadLogo(file);
      setVersion((v) => v + 1);
      onChanged(true);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  async function remove() {
    setPending(true);
    setError(null);
    try {
      await removeLogo();
      setUrl(null);
      onChanged(false);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium">Logo</p>
      <div className="flex flex-wrap items-center gap-4">
        <span className="grid h-16 w-40 place-items-center overflow-hidden rounded-lg border border-dashed border-border-strong bg-surface-muted">
          {hasLogo && url ? (
            <img src={url} alt="The company logo" className="max-h-14 max-w-36 object-contain" />
          ) : (
            <span className="text-xs text-muted-foreground">No logo yet</span>
          )}
        </span>
        <div className="flex flex-wrap gap-2">
          <input ref={input} type="file" accept="image/png,image/jpeg" className="sr-only" aria-label="Choose a logo file"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void upload(file);
              e.target.value = '';
            }} />
          <Button variant="secondary" pending={pending} onClick={() => input.current?.click()}>
            <ImageUp className="size-4" aria-hidden />
            {hasLogo ? 'Replace the logo' : 'Upload a logo'}
          </Button>
          {hasLogo && (
            <Button variant="ghost" disabled={pending} onClick={() => void remove()}>
              <Trash2 className="size-4" aria-hidden />
              Remove
            </Button>
          )}
        </div>
      </div>
      <p className="text-xs text-muted-foreground">PNG or JPEG, up to 1 MB. A wide logo on a clear background prints best.</p>
      {error && <InlineError lead="The logo was not saved" error={error} />}
    </div>
  );
}

/** A real receipt drawn by the server in the draft, redrawn a moment after each change. */
function Preview({ profile, branding }: { profile: Profile; branding: Branding }) {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const draft = JSON.stringify({ profile, branding });

  useEffect(() => {
    if (!/^#[0-9A-Fa-f]{6}$/.test(branding.primaryColour) || !/^#[0-9A-Fa-f]{6}$/.test(branding.secondaryColour)) {
      return undefined;
    }
    let live = true;
    let made: string | null = null;
    const timer = window.setTimeout(() => {
      previewDocument(profile, branding).then(
        (blob) => {
          if (!live) return;
          made = URL.createObjectURL(blob);
          setUrl(made);
          setError(null);
        },
        (e: unknown) => { if (live) setError(toApiError(e)); },
      );
    }, 600);
    return () => {
      live = false;
      window.clearTimeout(timer);
      if (made) URL.revokeObjectURL(made);
    };
    // The draft as a string: an object would redraw on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft]);

  return (
    <Panel title="Preview" subtitle="A sample receipt in your draft, before you save.">
      <div className="px-4 pb-4">
        {error && <InlineError lead="The preview could not be drawn" error={error} />}
        {url ? (
          <iframe aria-label="Preview of a receipt in your branding" src={`${url}#toolbar=0&view=FitH`}
            className="aspect-[1/1.414] w-full rounded-md border border-border bg-white" />
        ) : (
          <p className="py-10 text-center text-sm text-muted-foreground">Drawing the preview…</p>
        )}
      </div>
    </Panel>
  );
}
