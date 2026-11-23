import { useState } from 'react';
import { checkResetCode, requestResetCode } from '@/api/passwordReset';
import { startPublicVisit } from '@/api/publicQuote';
import { LanguageSwitch } from '@/components/AppShell';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Turnstile } from '@/components/Turnstile';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useLanguage } from '@/features/customer/language';
import { toApiError, type ApiError } from '@/lib/apiError';

const WORDS = {
  en: {
    title: 'Reset your password',
    lead: "Type the phone number you sign in with. We'll text you a code.",
    phone: 'Your phone number',
    send: 'Send me a code',
    sendAgain: 'Send a new code',
    sent: "If this number has a login, we've texted a code.",
    code: 'Code from the text',
    next: 'Continue',
    checking: 'Getting the page ready…',
    notStarted: "We couldn't confirm you're a person. Reload the page to try again.",
    staff: 'Staff: ask an administrator to reset your password.',
  },
  sw: {
    title: 'Badilisha nenosiri lako',
    lead: 'Andika namba ya simu unayoitumia kuingia. Tutakutumia namba ya kuthibitisha.',
    phone: 'Namba yako ya simu',
    send: 'Nitumie namba',
    sendAgain: 'Nitumie namba mpya',
    sent: 'Kama namba hii ina akaunti, tumeituma namba ya kuthibitisha.',
    code: 'Namba kutoka kwenye ujumbe',
    next: 'Endelea',
    checking: 'Tunaandaa ukurasa…',
    notStarted: 'Hatukuweza kuthibitisha kuwa wewe ni mtu. Pakia ukurasa upya ujaribu tena.',
    staff: 'Wafanyakazi: mwombe msimamizi abadilishe nenosiri lako.',
  },
} as const;

/**
 * "Forgot your password?" for agents and customers (2026-11-19, Authentik cut-over ticket 07). Authentik's sign-in page
 * sends people here: the reset starts on the platform's own page so it can be in English and Kiswahili. The person types
 * their phone number, then the texted code on the same page, and the browser goes to Authentik's one-time link to
 * choose a new password; from there they land on "/" and sign in. The answer to a number is the same whether or not it
 * has a login, and staff numbers are texted nothing: staff can't reset their own password.
 *
 * No login: Cloudflare Turnstile confirms a person and starts a short visit, as on the public quote link.
 */
export function PasswordResetPage() {
  const language = useLanguage();
  const t = WORDS[language];
  const [visit, setVisit] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [phone, setPhone] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  function begin(token: string) {
    startPublicVisit(token).then(
      (v) => { setVisit(v.visitToken); setFailed(false); },
      () => setFailed(true),
    );
  }

  async function send() {
    if (!visit) return;
    setBusy(true);
    setError(null);
    try {
      await requestResetCode(phone, language, visit);
      setSentTo(phone);
      setCode('');
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!visit || !sentTo) return;
    setBusy(true);
    setError(null);
    try {
      const { link } = await checkResetCode(sentTo, code.trim(), language, visit);
      window.location.assign(link);
    } catch (e) {
      setError(toApiError(e));
      setBusy(false);
    }
  }

  return (
    <div className="min-h-full bg-background">
      <header className="flex items-center justify-between gap-3 border-b border-border bg-surface px-4 py-3 sm:px-6">
        <div className="flex items-center gap-3">
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-accent font-display text-sm font-semibold text-accent-foreground"
            aria-hidden>
            LP
          </span>
          <p className="font-display text-sm font-semibold">Life Platform</p>
        </div>
        <div className="w-44"><LanguageSwitch /></div>
      </header>

      <main id="main" className="mx-auto max-w-md px-4 pt-8">
        <Turnstile onToken={begin} onError={() => setFailed(true)} language={language} />
        <h1 className="text-headline">{t.title}</h1>
        {failed && !visit ? (
          <p role="alert" className="mt-4 rounded-xl bg-status-danger-bg px-4 py-3 text-status-danger-fg">{t.notStarted}</p>
        ) : !visit ? (
          <LoadingBlock label={t.checking} />
        ) : (
          <div className="mt-4 space-y-4">
            <p className="text-muted-foreground">{t.lead}</p>
            <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void send(); }}>
              <FormField required label={t.phone}>
                <Input className="h-12 text-base" type="tel" inputMode="tel" autoComplete="tel" value={phone}
                  onChange={(e) => setPhone(e.target.value)} />
              </FormField>
              <Button type="submit" className="h-12 w-full" variant={sentTo ? 'outline' : 'primary'}
                disabled={busy || phone.trim() === ''}>
                {sentTo ? t.sendAgain : t.send}
              </Button>
            </form>
            {sentTo && (
              <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void confirm(); }}>
                <p role="status" className="rounded-xl bg-status-neutral-bg px-4 py-3 text-status-neutral-fg">{t.sent}</p>
                <FormField required label={t.code}>
                  <Input className="h-12 text-base" inputMode="numeric" autoComplete="one-time-code"
                    maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
                </FormField>
                <Button type="submit" className="h-12 w-full" disabled={busy || code.length !== 6}>{t.next}</Button>
              </form>
            )}
            {error && <InlineError error={error} />}
            <p className="text-sm text-muted-foreground">{t.staff}</p>
          </div>
        )}
      </main>
    </div>
  );
}
