import { Controller, useForm } from 'react-hook-form';
import { useMemo, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { getPublicProducts, quotePublicProduct, startPublicVisit, type PublicPerson } from '@/api/publicQuote';
import { LanguageSwitch } from '@/components/AppShell';
import { DatePicker } from '@/components/DatePicker';
import { FormField, RequiredNote } from '@/components/FormField';
import { Turnstile } from '@/components/Turnstile';
import { LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CircleCheckBig, Smartphone } from 'lucide-react';
import { Input, Select } from '@/components/ui/input';
import { useLanguage } from '@/features/customer/language';
import { QuoteSourceProvider, type QuoteSource } from '@/features/customer/quoteSource';
import { useWords } from '@/features/customer/words';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/**
 * The public quote link (2026-11-02, the user): anybody, with no account, sees what the products sold online would
 * cost them -- the customer portal's own Get-cover and price screens, priced on what they tell us about themselves.
 *
 * Three things happen before a price: Cloudflare Turnstile confirms a person (checked on the server), which starts a
 * short visit; the visitor says who they are, with the quotation form's own fields; then they choose and are priced.
 * Nothing is saved while they look. Applying (2026-11-08) confirms their phone by a texted code first -- see
 * CustomerApplyPage, which the portal and this link share.
 */
export function PublicQuoteApp() {
  const words = useWords();
  const w = words.publicQuote;
  const language = useLanguage();
  const [visit, setVisit] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [person, setPerson] = useState<AboutYouValues | null>(null);
  const [started, setStarted] = useState(false);

  function begin(token: string) {
    startPublicVisit(token).then(
      (v) => { setVisit(v.visitToken); setFailed(false); },
      () => setFailed(true),
    );
  }

  const source = useMemo<QuoteSource | null>(() => {
    if (!visit || !person) return null;
    const facts: PublicPerson = {
      fullName: person.fullName.trim() || null,
      dateOfBirth: person.dateOfBirth,
      sex: person.sex || null,
      smokerStatus: person.smokerStatus || null,
    };
    return {
      base: '/quote',
      listProducts: getPublicProducts,
      quote: (productId, request) => quotePublicProduct(productId, facts, request, visit),
      canApply: true,
      publicApply: {
        visitToken: visit,
        person: {
          fullName: person.fullName.trim(),
          dateOfBirth: person.dateOfBirth,
          sex: person.sex || null,
          smokerStatus: person.smokerStatus || null,
          phoneNumber: person.phoneNumber.trim(),
        },
      },
      showsAdviserProducts: false,
      header: <YouSummary person={person} onChange={() => setPerson(null)} />,
    };
  }, [visit, person]);

  return (
    <div className="min-h-full bg-background">
      <header className="flex items-center justify-between gap-3 border-b border-border bg-surface px-4 py-3 sm:px-6">
        <div className="flex items-center gap-3">
          {/* The platform's monogram, never an insurer's: tenancy stays invisible here as everywhere. */}
          <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-accent font-display text-sm font-semibold text-accent-foreground"
            aria-hidden>
            LP
          </span>
          <p className="font-display text-sm font-semibold">{w.title}</p>
        </div>
        <div className="w-44"><LanguageSwitch /></div>
      </header>

      <main id="main" className="pt-4">
        <Turnstile onToken={begin} onError={() => setFailed(true)} language={language} />
        {/* The opening screen first (2026-11-09); the person check runs behind it meanwhile. */}
        {!started ? (
          <Welcome onStart={() => setStarted(true)} />
        ) : failed && !visit ? (
          <div className="mx-auto max-w-xl px-4 pt-8 text-base">
            <p role="alert" className="rounded-xl bg-status-danger-bg px-4 py-3 text-status-danger-fg">{w.notStarted}</p>
          </div>
        ) : !visit ? (
          <LoadingBlock label={w.checking} />
        ) : !person || !source ? (
          <AboutYou initial={person} onDone={setPerson} />
        ) : (
          <QuoteSourceProvider value={source}>
            {/* Centred: the portal's screens sit beside a sidebar there, and here they have the page to themselves. */}
            <div className="mx-auto w-full max-w-3xl">
              <Outlet />
            </div>
          </QuoteSourceProvider>
        )}
      </main>
    </div>
  );
}

interface AboutYouValues {
  fullName: string;
  dateOfBirth: string;
  phoneNumber: string;
  sex: '' | 'FEMALE' | 'MALE';
  smokerStatus: '' | 'NON_SMOKER' | 'SMOKER' | 'UNKNOWN';
}

/** The quotation form's own facts about the person; only the date of birth is required to be priced. */
function AboutYou({ initial, onDone }: { initial: AboutYouValues | null; onDone: (values: AboutYouValues) => void }) {
  const w = useWords().publicQuote;
  const { register, control, handleSubmit, watch } = useForm<AboutYouValues>({
    ...VALIDATE_ON_TOUCH,
    defaultValues: initial ?? { fullName: '', dateOfBirth: '', phoneNumber: '', sex: '', smokerStatus: '' },
  });
  // eslint-disable-next-line react-hooks/incompatible-library -- as the other forms here
  const dateOfBirth = watch('dateOfBirth');

  return (
    <form className="mx-auto w-full max-w-xl space-y-5 px-4 pb-10 text-base sm:px-6" onSubmit={(e) => void handleSubmit(onDone)(e)}>
      <div className="space-y-1">
        <h1 className="text-display">{w.aboutYou}</h1>
        <p className="text-muted-foreground">{w.aboutYouHelp}</p>
      </div>
      <RequiredNote label={w.required} />
      <FormField label={w.fullName}>
        <Input className="h-12 text-base" autoComplete="name" {...register('fullName')} />
      </FormField>
      <FormField required label={w.dateOfBirth}>
        <Controller control={control} name="dateOfBirth"
          render={({ field }) => (
            <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} disabled={{ after: new Date() }} />
          )} />
      </FormField>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={w.sex}>
          <Select className="h-12 text-base" {...register('sex')}>
            <option value="">—</option>
            <option value="FEMALE">{w.female}</option>
            <option value="MALE">{w.male}</option>
          </Select>
        </FormField>
        <FormField label={w.smoker}>
          <Select className="h-12 text-base" {...register('smokerStatus')}>
            <option value="">—</option>
            <option value="NON_SMOKER">{w.no}</option>
            <option value="SMOKER">{w.yes}</option>
            <option value="UNKNOWN">{w.preferNot}</option>
          </Select>
        </FormField>
      </div>
      <FormField label={w.phone}>
        <Input className="h-12 text-base" inputMode="tel" autoComplete="tel" placeholder="0712 345 678" {...register('phoneNumber')} />
      </FormField>
      <Button type="submit" variant="primary" className="h-12 w-full text-base" disabled={!dateOfBirth}>
        {w.toProducts}
      </Button>
    </form>
  );
}

/** Who is being priced, above the products, with a way back to change it. */
function YouSummary({ person, onChange }: { person: AboutYouValues; onChange: () => void }) {
  const w = useWords().publicQuote;
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-border bg-surface px-4 py-3">
      <p className="min-w-0 truncate">{w.you(person.fullName.trim() || '—', ageOn(person.dateOfBirth))}</p>
      <Button variant="ghost" size="sm" onClick={onChange}>{w.changeDetails}</Button>
    </div>
  );
}

/** Whole years since a date of birth, for the summary line only -- the server prices on the date itself. */
function ageOn(iso: string): number {
  const born = new Date(iso);
  const now = new Date();
  let age = now.getFullYear() - born.getFullYear();
  if (now.getMonth() < born.getMonth() || (now.getMonth() === born.getMonth() && now.getDate() < born.getDate())) age -= 1;
  return age;
}

/**
 * The opening screen (2026-11-09, the user: "start with an opening statement, as other quotes online do"): what this
 * is, the three steps, what you will need, and that nothing is owed or kept -- then one button.
 */
function Welcome({ onStart }: { onStart: () => void }) {
  const w = useWords().publicQuote;
  return (
    <section className="mx-auto w-full max-w-xl space-y-6 px-4 pb-10 pt-4 text-base sm:px-6">
      <div className="space-y-2">
        <h1 className="text-display">{w.welcomeTitle}</h1>
        <p className="text-muted-foreground">{w.welcomeLead}</p>
      </div>
      <ol className="space-y-3">
        {w.welcomeSteps.map(([step, note], i) => (
          <li key={step} className="flex gap-3 rounded-2xl border border-border bg-surface p-4">
            <span className="grid size-8 shrink-0 place-items-center rounded-full bg-accent font-semibold text-accent-foreground"
              aria-hidden>
              {i + 1}
            </span>
            <span>
              <span className="block font-semibold">{step}</span>
              <span className="block text-sm text-muted-foreground">{note}</span>
            </span>
          </li>
        ))}
      </ol>
      <div className="space-y-2 text-sm">
        <p className="flex gap-2"><Smartphone className="mt-0.5 size-4 shrink-0" aria-hidden />{w.welcomeNeed}</p>
        <p className="flex gap-2"><CircleCheckBig className="mt-0.5 size-4 shrink-0" aria-hidden />{w.welcomeNoObligation}</p>
      </div>
      <Button variant="primary" className="h-12 w-full text-base" onClick={onStart}>{w.start}</Button>
    </section>
  );
}
