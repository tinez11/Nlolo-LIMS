import { CalendarClock, Check, Plus, Trash2, Users, Wallet } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { getCustomerProducts, quoteCustomerProduct, type CustomerProduct, type CustomerQuote } from '@/api/portal';
import { ShareBars } from '@/components/charts/ShareBars';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { Choice, Question, StepBar } from './CustomerUi';
import { money } from './customerText';
import { NEED_ICON } from './needIcons';
import {
  amountChoices, answered, isNeed, quoteRequest, stepsFor, useApplyDraft, yearChoices,
  type Answers, type FamilyMember, type Step,
} from './selfService';
import { useWords, type Words } from './words';

const AMOUNT = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });
const FREQUENCIES =['MONTHLY', 'QUARTERLY', 'ANNUALLY'] as const;
const FAMILY_ROLES = ['SPOUSE', 'CHILD', 'PARENT', 'EXTENDED'] as const;

/**
 * One product priced one question at a time (2026-10-29, the user's choice): how much, for how long, how often -- or a
 * deposit's amount and term, or a funeral plan and the family -- then the price on the answer band, a picture of what
 * is paid against what comes back, and Apply. The price is the server's, on the customer's own details.
 */
export function CustomerQuotePage() {
  const { productId = '' } = useParams();
  const words = useWords();
  const w = words.quote;
  const navigate = useNavigate();
  const keep = useApplyDraft((s) => s.keep);
  const [product, setProduct] = useState<CustomerProduct | null | undefined>(undefined);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [at, setAt] = useState(0);
  const [answers, setAnswers] = useState<Answers>({ frequency: 'MONTHLY', family: [] });
  const [quote, setQuote] = useState<CustomerQuote | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let live = true;
    getCustomerProducts().then(
      (all) => { if (live) setProduct(all.find((p) => p.productId === productId && p.online) ?? null); },
      (e: unknown) => { if (live) setLoadError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [productId]);

  if (loadError) return <div className="px-4 pt-6 sm:px-6"><ErrorPanel error={loadError} /></div>;
  if (product === undefined) return <LoadingBlock label="Loading" />;
  if (product === null) {
    return (
      <div className="mx-auto max-w-xl space-y-3 px-4 pt-8 text-base">
        <p>{words.apply.lostLead}</p>
        <Button asChild variant="primary"><Link to="/customers/products">{words.nav.products}</Link></Button>
      </div>
    );
  }

  const steps = stepsFor(product);
  const step = steps[at]!;
  const set = (patch: Partial<Answers>) => { setAnswers((a) => ({ ...a, ...patch })); setError(null); };

  async function price(p: CustomerProduct) {
    setBusy(true);
    setError(null);
    try {
      setQuote(await quoteCustomerProduct(p.productId, quoteRequest(p, answers)));
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  function next(p: CustomerProduct) {
    if (at < steps.length - 1) setAt(at + 1);
    else void price(p);
  }

  const title = isNeed(product.kind) ? words.needs[product.kind][0] : product.productName;
  const Icon = isNeed(product.kind) ? NEED_ICON[product.kind] : Wallet;

  if (quote) {
    return (
      <>
        <PageHeader title={w.yourPrice} description={product.productName}
          breadcrumb={[{ label: words.nav.products, to: '/customers/products' }]} />
        <div className="w-full max-w-xl space-y-5 px-4 pb-10 text-base sm:px-6">
          <Answer quote={quote} w={w} />
          <div className="flex flex-col gap-2 sm:flex-row-reverse sm:justify-start">
            <Button variant="primary" className="h-12 px-6 text-base" disabled={quote.deposit?.refusal != null}
              onClick={() => { keep({ product, request: quoteRequest(product, answers), quote }); navigate(`/customers/products/${encodeURIComponent(product.productId)}/apply`); }}>
              {w.apply}
            </Button>
            <Button variant="outline" className="h-12 text-base" onClick={() => { setQuote(null); setAt(0); }}>{w.change}</Button>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader title={title} description={product.productName}
        breadcrumb={[{ label: words.nav.products, to: '/customers/products' }]} />
      <div className="w-full max-w-xl space-y-6 px-4 pb-10 text-base sm:px-6">
        <StepBar at={at} of={steps.length} label={w.step(at + 1, steps.length)} />
        <StepScreen step={step} product={product} answers={answers} set={set} w={w} icon={<Icon className="size-7" />} />
        {error && <InlineError error={error} lead={w.notPriced} />}
        {/* Not priced here is not the end: an adviser can still price it, on the same answers. */}
        {error?.status === 422 && at === steps.length - 1 && (
          <Button variant="outline" className="h-12 w-full text-base"
            onClick={() => { keep({ product, request: quoteRequest(product, answers), quote: null }); navigate(`/customers/products/${encodeURIComponent(product.productId)}/apply`); }}>
            {w.adviserPrice}
          </Button>
        )}
        <div className="flex gap-2">
          {at > 0 && <Button variant="outline" className="h-12 text-base" onClick={() => setAt(at - 1)}>{w.back}</Button>}
          <Button variant="primary" className="h-12 flex-1 text-base" disabled={!answered(step, answers)} pending={busy}
            onClick={() => next(product)}>
            {at < steps.length - 1 ? w.next : w.yourPrice}
          </Button>
        </div>
      </div>
    </>
  );
}

function StepScreen({ step, product, answers, set, w, icon }: {
  step: Step;
  product: CustomerProduct;
  answers: Answers;
  set: (patch: Partial<Answers>) => void;
  w: Words['quote'];
  icon: React.ReactNode;
}) {
  const currency = product.currency;
  switch (step) {
    case 'amount': {
      const deposit = product.kind === 'DEPOSIT';
      return (
        <Question icon={icon}
          title={deposit ? w.amountDeposit : product.kind === 'CASH_VALUE' ? w.amountSave : w.amountProtect}
          help={deposit ? w.amountDepositHelp(money(product.terms.minAmount, currency)) : product.kind === 'CASH_VALUE' ? w.amountSaveHelp : w.amountProtectHelp}>
          <AmountAnswer choices={amountChoices(product)} currency={currency} value={answers.amount}
            onChange={(amount) => set({ amount })} other={w.otherAmount} />
        </Question>
      );
    }
    case 'years':
      return (
        <Question icon={<CalendarClock className="size-7" />} title={w.years} help={w.yearsHelp}>
          <div role="radiogroup" aria-label={w.years} className="grid gap-2.5">
            {yearChoices(product).map((y) => (
              <Choice key={y} label={w.yearsLabel(y)} selected={answers.years === y} onSelect={() => set({ years: y })} />
            ))}
          </div>
        </Question>
      );
    case 'frequency':
      return (
        <Question icon={<CalendarClock className="size-7" />} title={w.frequency} help={w.frequencyHelp}>
          <div role="radiogroup" aria-label={w.frequency} className="grid gap-2.5">
            {FREQUENCIES.map((f) => (
              <Choice key={f} label={w[f]} selected={answers.frequency === f} onSelect={() => set({ frequency: f })} />
            ))}
          </div>
        </Question>
      );
    case 'term':
      return (
        <Question icon={<CalendarClock className="size-7" />} title={w.depositTerm} help={w.depositTermHelp}>
          <div role="radiogroup" aria-label={w.depositTerm} className="grid gap-2.5">
            {product.terms.depositTermMonths.map((m) => (
              <Choice key={m} label={w.months(m)} selected={answers.termMonths === m} onSelect={() => set({ termMonths: m })} />
            ))}
          </div>
        </Question>
      );
    case 'plan':
      return (
        <Question icon={icon} title={w.plan} help={w.planHelp}>
          <div role="radiogroup" aria-label={w.plan} className="grid gap-2.5">
            {product.terms.funeralPlans.map((p) => (
              <Choice key={p.planCode} label={p.name}
                note={p.mainMemberBenefit != null ? w.planPays(money(p.mainMemberBenefit, currency)) : undefined}
                selected={answers.planCode === p.planCode} onSelect={() => set({ planCode: p.planCode })} />
            ))}
          </div>
        </Question>
      );
    case 'family':
      return (
        <Question icon={<Users className="size-7" />} title={w.family} help={w.familyHelp}>
          <FamilyAnswer product={product} family={answers.family ?? []} onChange={(family) => set({ family })} w={w} />
        </Question>
      );
  }
}

/** A few round amounts, or one typed. */
function AmountAnswer({ choices, currency, value, onChange, other }: {
  choices: number[]; currency: string; value: number | undefined; onChange: (v: number | undefined) => void; other: string;
}) {
  const [typed, setTyped] = useState(() => (value != null && !choices.includes(value) ? String(value) : ''));
  return (
    <div className="space-y-3">
      <div role="radiogroup" aria-label={other} className="grid grid-cols-2 gap-2.5">
        {choices.map((c) => (
          <Choice key={c} label={AMOUNT.format(c)} note={currency} selected={value === c && typed === ''}
            onSelect={() => { setTyped(''); onChange(c); }} />
        ))}
      </div>
      <FormField label={`${other} (${currency})`}>
        <Input inputMode="numeric" className="h-12 text-base" value={typed} placeholder="e.g. 15,000,000"
          onChange={(e) => {
            setTyped(e.target.value);
            const n = Number(e.target.value.replace(/[,\s]/g, ''));
            onChange(e.target.value.trim() !== '' && Number.isFinite(n) && n > 0 ? n : undefined);
          }} />
      </FormField>
    </div>
  );
}

function FamilyAnswer({ product, family, onChange, w }: {
  product: CustomerProduct; family: FamilyMember[]; onChange: (f: FamilyMember[]) => void; w: Words['quote'];
}) {
  const offered = FAMILY_ROLES.filter((r) => product.terms.funeralRoles.some((x) => x.role === r));
  const edit = (i: number, patch: Partial<FamilyMember>) => onChange(family.map((m, j) => (j === i ? { ...m, ...patch } : m)));
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3 rounded-xl border border-border bg-control px-4 py-3">
        <Check className="size-5 text-accent" aria-hidden />
        <span className="font-semibold">{w.MAIN_MEMBER}</span>
      </div>
      {family.map((m, i) => (
        <fieldset key={i} className="space-y-3 rounded-xl border border-border bg-surface p-4">
          <legend className="sr-only">{w[m.role]}</legend>
          <div className="grid gap-3 sm:grid-cols-2">
            <FormField label={w.role}>
              <Select className="h-12 text-base" value={m.role} onChange={(e) => edit(i, { role: e.target.value as FamilyMember['role'] })}>
                {offered.map((r) => <option key={r} value={r}>{w[r]}</option>)}
              </Select>
            </FormField>
            <FormField label={w.name}>
              <Input className="h-12 text-base" value={m.name} onChange={(e) => edit(i, { name: e.target.value })} />
            </FormField>
            <FormField label={w.dateOfBirth}>
              <DatePicker value={m.dateOfBirth || null} onChange={(d) => edit(i, { dateOfBirth: d ?? '' })}
                disabled={{ after: new Date() }} />
            </FormField>
            {m.role === 'CHILD' && (
              <CheckboxField className="self-end" label={w.student} checked={m.student}
                onChange={(e) => edit(i, { student: e.target.checked })} />
            )}
          </div>
          <Button variant="ghost" size="sm" onClick={() => onChange(family.filter((_, j) => j !== i))}>
            <Trash2 aria-hidden />{w.remove}
          </Button>
        </fieldset>
      ))}
      {offered.length > 0 && (
        <Button variant="outline" className="h-12 w-full text-base"
          onClick={() => onChange([...family, { role: offered.includes('CHILD') ? 'CHILD' : offered[0]!, name: '', dateOfBirth: '', student: false }])}>
          <Plus aria-hidden />{w.addPerson}
        </Button>
      )}
    </div>
  );
}

/** The price on the answer band, the picture of it, and what to know -- every figure as the server sent it. */
function Answer({ quote, w }: { quote: CustomerQuote; w: Words['quote'] }) {
  const c = quote.currency;
  const often = w.perFrequency[quote.frequency] ?? '';
  const d = quote.deposit;
  return (
    <div className="space-y-5">
      {d?.refusal ? (
        <p role="alert" className="rounded-xl bg-status-warning-bg px-4 py-3 text-status-warning-fg">
          <span className="block font-semibold">{w.notPriced}</span>{d.refusal}
        </p>
      ) : (
        <dl className="hero-scope grid grid-cols-1 gap-4 rounded-2xl bg-hero p-5 sm:grid-cols-2" role="status">
          {quote.kind === 'DEPOSIT' ? (
            <>
              <Figure label={w.youDeposit} value={money(quote.sumAssured, c)} note={often} />
              <Figure label={w.youGetBack} value={money(d?.maturityValue, c)} note={d?.maturityDate ? w.onDate(formatDate(d.maturityDate)) : ''} />
            </>
          ) : (
            <>
              <Figure label={w.youPay} value={money(quote.instalment, c)}
                note={[often, quote.termYears ? w.forYears(quote.termYears) : null].filter(Boolean).join(', ')} />
              <Figure label={quote.kind === 'CASH_VALUE' ? w.planPaysOut : w.theyGet} value={money(quote.sumAssured, c)}
                note={quote.kind === 'CASH_VALUE' ? w.atTheEnd : quote.kind === 'FUNERAL' ? '' : w.ifYouDie} />
            </>
          )}
        </dl>
      )}

      <Picture quote={quote} w={w} />

      <ul className="space-y-2.5">
        {(w.points[quote.kind] ?? []).map((p) => (
          <li key={p} className="flex gap-3"><Check className="mt-0.5 size-5 shrink-0 text-status-success-fg" aria-hidden />{p}</li>
        ))}
      </ul>
      <p className="text-sm text-muted-foreground">{w.guide}</p>
    </div>
  );
}

function Figure({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div>
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-headline tabular-nums">{value}</dd>
      {note && <dd className="text-sm text-muted-foreground">{note}</dd>}
    </div>
  );
}

function Picture({ quote, w }: { quote: CustomerQuote; w: Words['quote'] }) {
  const c = quote.currency;
  let bars: Parameters<typeof ShareBars>[0]['bars'] = [];
  if (quote.kind === 'DEPOSIT' && quote.deposit?.maturityValue != null) {
    bars = [
      { key: 'deposit', label: w.deposit, value: Number(quote.sumAssured), display: money(quote.sumAssured, c), color: 'chart-2' },
      { key: 'interest', label: w.interest, value: Number(quote.deposit.interest ?? 0), display: money(quote.deposit.interest, c), color: 'chart-3' },
      { key: 'back', label: w.paidBack, value: Number(quote.deposit.maturityValue), display: money(quote.deposit.maturityValue, c), color: 'chart-1' },
    ];
  } else if (quote.kind === 'FUNERAL') {
    bars = quote.funeral.map((l, i) => ({
      key: String(i), label: l.name || roleWord(w, l.role), note: `${roleWord(w, l.role)} · ${l.age}`,
      value: l.benefit, display: money(l.benefit, c), color: 'chart-1' as const,
    }));
  } else if (quote.totalOverTerm != null) {
    bars = [
      { key: 'paid', label: w.allYouPay, value: Number(quote.totalOverTerm), display: money(quote.totalOverTerm, c), color: 'chart-3' },
      { key: 'gets', label: quote.kind === 'CASH_VALUE' ? w.planPaysOut : w.familyGets, value: Number(quote.sumAssured), display: money(quote.sumAssured, c), color: 'chart-1' },
    ];
  }
  if (bars.length === 0) return null;
  return (
    <section aria-labelledby="picture-title" className="space-y-3 rounded-2xl border border-border bg-surface p-4 shadow-card">
      <h2 id="picture-title" className="text-title">{quote.kind === 'FUNERAL' ? w.perPerson : w.picture}</h2>
      <ShareBars label={quote.kind === 'FUNERAL' ? w.perPerson : w.picture} bars={bars} />
    </section>
  );
}

function roleWord(w: Words['quote'], role: string): string {
  return role === 'MAIN_MEMBER' || (FAMILY_ROLES as readonly string[]).includes(role)
    ? w[role as 'MAIN_MEMBER' | (typeof FAMILY_ROLES)[number]]
    : role;
}
