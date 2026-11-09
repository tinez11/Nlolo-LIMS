import { CircleCheckBig } from 'lucide-react';
import { useEffect, useState } from 'react';
import { checkPublicCode, getPublicConsent, sendPublicCode, type PublicConsentWording } from '@/api/publicQuote';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { useLanguage } from '@/features/customer/language';
import { useWords } from '@/features/customer/words';
import { toApiError, type ApiError } from '@/lib/apiError';

/** Who is applying through the public link, once their phone is confirmed (2026-11-08). */
export interface PublicApplicant {
  fullName: string;
  email: string;
  /** Null until a texted code confirms it; then the phone as recorded and the proof the application carries. */
  confirmed: { phoneNumber: string; phoneToken: string } | null;
}

/**
 * The first step of applying through the public link (2026-11-02, the user: the phone is required to apply, confirmed
 * by SMS): their name, an email if they have one, and their mobile number -- a six-digit code is texted to it and typed
 * back here. Changing the number after confirming it means confirming the new one.
 */
export function PublicApplicantStep({ visitToken, initialPhone, value, onChange }: {
  visitToken: string;
  /** The number typed before pricing, if any -- offered, still to be confirmed. */
  initialPhone?: string;
  value: PublicApplicant;
  onChange: (next: PublicApplicant) => void;
}) {
  const w = useWords().publicApply;
  const language = useLanguage() === 'sw' ? 'SW' : 'EN';
  const [phone, setPhone] = useState(initialPhone ?? '');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function send() {
    setBusy(true);
    setError(null);
    try {
      await sendPublicCode(phone, language, visitToken);
      setSentTo(phone.trim());
      setCode('');
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!sentTo) return;
    setBusy(true);
    setError(null);
    try {
      const confirmed = await checkPublicCode(sentTo, code.trim(), language, visitToken);
      onChange({ ...value, confirmed });
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-muted-foreground">{w.lead}</p>
      <FormField required label={w.fullName}>
        <Input className="h-12 text-base" autoComplete="name" value={value.fullName}
          onChange={(e) => onChange({ ...value, fullName: e.target.value })} />
      </FormField>
      <FormField label={w.email}>
        <Input className="h-12 text-base" type="email" autoComplete="email" value={value.email}
          onChange={(e) => onChange({ ...value, email: e.target.value })} />
      </FormField>

      {value.confirmed ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-status-success-bg px-4 py-3 text-status-success-fg">
          <p role="status" className="flex items-center gap-2 font-medium">
            <CircleCheckBig className="size-5" aria-hidden />{w.confirmed(value.confirmed.phoneNumber)}
          </p>
          <Button variant="ghost" size="sm" className="text-foreground"
            onClick={() => { onChange({ ...value, confirmed: null }); setSentTo(null); setCode(''); }}>
            {w.otherNumber}
          </Button>
        </div>
      ) : (
        <div className="space-y-3 rounded-2xl border border-border bg-surface p-4">
          <FormField required label={w.phone}>
            <Input className="h-12 text-base" inputMode="tel" autoComplete="tel" placeholder="0712 345 678" value={phone}
              onChange={(e) => { setPhone(e.target.value); setSentTo(null); }} />
          </FormField>
          {sentTo == null ? (
            <Button variant="primary" className="h-12 w-full text-base" pending={busy} disabled={phone.trim().length < 9}
              onClick={() => void send()}>
              {w.sendCode}
            </Button>
          ) : (
            <>
              <p role="status" className="text-sm">{w.codeSent(sentTo)}</p>
              <FormField required label={w.code}>
                <Input className="h-12 text-base" inputMode="numeric" autoComplete="one-time-code"
                  maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
              </FormField>
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" className="h-12 flex-1 text-base" pending={busy} disabled={code.length !== 6}
                  onClick={() => void confirm()}>
                  {w.confirm}
                </Button>
                <Button variant="outline" className="h-12 text-base" disabled={busy} onClick={() => void send()}>
                  {w.resend}
                </Button>
              </div>
            </>
          )}
          {error && <InlineError error={error} />}
        </div>
      )}
      {!value.confirmed && <p className="text-sm text-muted-foreground">{w.needPhone}</p>}
    </div>
  );
}

/**
 * The declaration, word for word as the application will record it, in the language being read, and the box that
 * agrees to it (2026-11-02, the user: consent recorded with the wording's version).
 */
export function PublicDeclaration({ agreed, onAgree, onWording }: {
  agreed: boolean;
  onAgree: (agreed: boolean) => void;
  onWording: (wording: PublicConsentWording) => void;
}) {
  const w = useWords().publicApply;
  const language = useLanguage();
  const [wording, setWording] = useState<PublicConsentWording | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    getPublicConsent().then(
      (c) => { if (live) { setWording(c); onWording(c); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [onWording]);

  if (error) return <InlineError error={error} />;
  if (!wording) return null;
  const text = language === 'sw' ? wording.sw : wording.en;
  const [heading, ...points] = text.split('\n');
  return (
    <section aria-labelledby="declaration" className="space-y-3 rounded-2xl border border-border bg-surface p-4">
      <h3 id="declaration" className="font-semibold">{w.declaration}</h3>
      <p>{heading}</p>
      {/* The wording numbers its own points. */}
      <ul className="list-none space-y-2 text-sm">
        {points.map((p) => <li key={p}>{p}</li>)}
      </ul>
      <label className="flex items-start gap-3 rounded-xl border border-border-strong p-3 font-medium">
        <Checkbox className="mt-1" checked={agreed} onChange={(e) => onAgree(e.target.checked)} />
        <span>{w.agree}</span>
      </label>
    </section>
  );
}
