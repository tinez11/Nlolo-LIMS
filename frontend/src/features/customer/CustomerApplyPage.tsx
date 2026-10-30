import { CircleCheckBig, Plus, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { applyForProduct, getMe, type CustomerMe } from '@/api/portal';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { ProgressLine, StepBar } from './CustomerUi';
import { money } from './customerText';
import { useLanguage } from './language';
import { beneficiaryProblem, needsDetail, useApplyDraft, type Answer, type Person } from './selfService';
import { useWords } from './words';

/**
 * Applying for the cover just priced (2026-10-29): the customer's own details to check, who receives the money, the
 * health questions the product asks, then one look at it all and Send. It goes straight to underwriting, which prices it
 * again -- nothing typed here sets a price.
 */
export function CustomerApplyPage() {
  const { productId = '' } = useParams();
  const words = useWords();
  const w = words.apply;
  const language = useLanguage();
  const draft = useApplyDraft((s) => s.draft);
  const clear = useApplyDraft((s) => s.clear);
  const [me, setMe] = useState<CustomerMe | null>(null);
  const [at, setAt] = useState(0);
  const [people, setPeople] = useState<Person[]>([{ name: '', relationship: '', phone: '', share: '100' }]);
  const [answers, setAnswers] = useState<Record<string, Answer>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [sent, setSent] = useState(false);

  useEffect(() => {
    let live = true;
    getMe().then((m) => { if (live) setMe(m); }, () => undefined);
    return () => { live = false; };
  }, []);

  if (sent) {
    return (
      <div className="mx-auto w-full max-w-xl space-y-6 px-4 pt-10 pb-10 text-base sm:px-6">
        <div className="space-y-2 text-center">
          <CircleCheckBig className="mx-auto size-14 text-status-success-fg" aria-hidden />
          <h1 className="text-headline">{w.sentTitle}</h1>
          <p className="text-muted-foreground">{w.sentLead}</p>
        </div>
        <div className="rounded-2xl border border-border bg-surface p-4 shadow-card"><ProgressLine at={1} tone="go" /></div>
        <Button asChild variant="primary" className="h-12 w-full text-base"><Link to="/customers/applications">{w.seeApplications}</Link></Button>
      </div>
    );
  }

  if (!draft || draft.product.productId !== productId) {
    return (
      <div className="mx-auto max-w-xl space-y-3 px-4 pt-10 text-base">
        <h1 className="text-headline">{w.lost}</h1>
        <p className="text-muted-foreground">{w.lostLead}</p>
        <Button asChild variant="primary"><Link to={`/customers/products/${encodeURIComponent(productId)}`}>{words.needs.see}</Link></Button>
      </div>
    );
  }

  const { product, request, quote } = draft;
  const questions = product.questions;
  const steps = [w.steps[0]!, w.steps[1]!, ...(questions.length > 0 ? [w.steps[2]!] : []), w.steps[3]!];
  const step = steps[at]!;
  const shares = beneficiaryProblem(people);
  const unanswered = questions.some((q) => {
    const a = answers[q.code];
    return !a?.answer || (needsDetail(q.code, a.answer) && a.notes.trim() === '');
  });
  const canGo = step === w.steps[1] ? shares == null : step === w.steps[2] ? !unanswered : true;
  const named = people.filter((p) => p.name.trim() !== '');

  async function send() {
    setBusy(true);
    setError(null);
    try {
      await applyForProduct({
        productId: product.productId, ...request,
        beneficiaries: named.map((p) => ({ name: p.name.trim(), relationship: p.relationship.trim() || null,
          phone: p.phone.trim() || null, sharePercent: Number(p.share) })),
        answers: questions.map((q) => ({ code: q.code, answer: answers[q.code]!.answer!, notes: answers[q.code]!.notes.trim() || null })),
        language: language === 'sw' ? 'SW' : 'EN',
      });
      clear();
      setSent(true);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title={w.title} description={product.productName}
        breadcrumb={[{ label: words.nav.products, to: '/customers/products' }]} />
      <div className="w-full max-w-xl space-y-6 px-4 pb-10 text-base sm:px-6">
        <StepBar at={at} of={steps.length} label={`${words.quote.step(at + 1, steps.length)} · ${step}`} />
        <h2 className="text-headline">{step}</h2>

        {step === w.steps[0] && (
          <div className="space-y-3">
            <p className="text-muted-foreground">{w.detailsLead}</p>
            <dl className="divide-y divide-border rounded-2xl border border-border bg-surface shadow-card">
              {[[w.name, me?.displayName], [w.phone, me?.phoneNumber], [w.email, me?.email]].map(([label, value]) => (
                <div key={label} className="flex justify-between gap-4 px-4 py-3">
                  <dt className="text-muted-foreground">{label}</dt>
                  <dd className="text-right font-medium">{value || w.none}</dd>
                </div>
              ))}
            </dl>
          </div>
        )}

        {step === w.steps[1] && (
          <div className="space-y-3">
            <p className="text-muted-foreground">{w.beneficiariesLead}</p>
            {people.map((p, i) => {
              const edit = (patch: Partial<Person>) => setPeople(people.map((x, j) => (j === i ? { ...x, ...patch } : x)));
              return (
                <fieldset key={i} className="space-y-3 rounded-2xl border border-border bg-surface p-4">
                  <legend className="sr-only">{`${w.beneficiaryName} ${i + 1}`}</legend>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <FormField label={w.beneficiaryName}><Input className="h-12 text-base" value={p.name} onChange={(e) => edit({ name: e.target.value })} /></FormField>
                    <FormField label={w.relationship}><Input className="h-12 text-base" value={p.relationship} onChange={(e) => edit({ relationship: e.target.value })} /></FormField>
                    <FormField label={w.phone2}><Input className="h-12 text-base" inputMode="tel" value={p.phone} onChange={(e) => edit({ phone: e.target.value })} /></FormField>
                    <FormField label={w.share}><Input className="h-12 text-base" inputMode="decimal" value={p.share} onChange={(e) => edit({ share: e.target.value })} /></FormField>
                  </div>
                  {people.length > 1 && (
                    <Button variant="ghost" size="sm" onClick={() => setPeople(people.filter((_, j) => j !== i))}><Trash2 aria-hidden />{words.quote.remove}</Button>
                  )}
                </fieldset>
              );
            })}
            <Button variant="outline" className="h-12 w-full text-base" onClick={() => setPeople([...people, { name: '', relationship: '', phone: '', share: '' }])}>
              <Plus aria-hidden />{w.addBeneficiary}
            </Button>
            {shares && <p role="alert" className="text-status-warning-fg">{w.sharesTotal(String(shares.total))}</p>}
            <p className="text-sm text-muted-foreground">{w.skipBeneficiaries}</p>
          </div>
        )}

        {step === w.steps[2] && (
          <div className="space-y-4">
            <p className="text-muted-foreground">{w.healthLead}</p>
            {questions.map((q, i) => {
              const a = answers[q.code] ?? { answer: null, notes: '' };
              const setA = (patch: Partial<Answer>) => setAnswers({ ...answers, [q.code]: { ...a, ...patch } });
              return (
                <fieldset key={q.code} className="space-y-3 rounded-2xl border border-border bg-surface p-4">
                  <legend className="float-left mb-3 w-full font-semibold">{`${i + 1}. ${language === 'sw' ? q.sw : q.en}`}</legend>
                  <div role="radiogroup" aria-label={language === 'sw' ? q.sw : q.en} className="clear-both grid grid-cols-2 gap-2.5">
                    {(['YES', 'NO'] as const).map((v) => (
                      <button key={v} type="button" role="radio" aria-checked={a.answer === v} onClick={() => setA({ answer: v })}
                        className={cn('h-12 rounded-xl border text-base font-semibold',
                          a.answer === v ? 'border-2 border-accent bg-selected' : 'border-border-strong bg-surface hover:bg-hover')}>
                        {v === 'YES' ? w.yes : w.no}
                      </button>
                    ))}
                  </div>
                  {needsDetail(q.code, a.answer) && (
                    <FormField label={w.tellUsMore} hint={w.tellUsMoreHelp}>
                      <Textarea className="text-base" rows={3} value={a.notes} onChange={(e) => setA({ notes: e.target.value })} />
                    </FormField>
                  )}
                </fieldset>
              );
            })}
          </div>
        )}

        {step === w.steps[3] && (
          <dl className="divide-y divide-border rounded-2xl border border-border bg-surface shadow-card">
            <Row label={w.cover} value={`${product.productName} · ${money(quote?.sumAssured ?? request.sumAssured ?? request.amount, product.currency)}`} />
            <Row label={w.price} value={quote ? `${money(quote.instalment, quote.currency)} ${words.quote.perFrequency[quote.frequency] ?? ''}` : w.adviserPrices} />
            <Row label={w.people} value={named.length === 0 ? w.none : named.map((p) => `${p.name.trim()} (${p.share}%)`).join(', ')} />
            {questions.length > 0 && <Row label={w.healthDone} value={w.answered(questions.length)} />}
          </dl>
        )}

        {error && <InlineError error={error} />}
        <div className="flex gap-2">
          {at > 0 && <Button variant="outline" className="h-12 text-base" onClick={() => setAt(at - 1)}>{words.quote.back}</Button>}
          {at < steps.length - 1 ? (
            <Button variant="primary" className="h-12 flex-1 text-base" disabled={!canGo} onClick={() => setAt(at + 1)}>{words.quote.next}</Button>
          ) : (
            <Button variant="primary" className="h-12 flex-1 text-base" pending={busy} onClick={() => void send()}>{w.send}</Button>
          )}
        </div>
      </div>
    </>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4 px-4 py-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right font-medium">{value}</dd>
    </div>
  );
}
