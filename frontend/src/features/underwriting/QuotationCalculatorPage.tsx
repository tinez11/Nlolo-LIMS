import { zodResolver } from '@hookform/resolvers/zod';
import { useCallback, useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { acceptQuotation } from '@/api/agentQuotations';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PartyPicker } from '@/components/PartyPicker';
import { Input, Select } from '@/components/ui/input';
import { isSingleLifeProduct, PREMIUM_FREQUENCIES, PREMIUM_FREQUENCY_LABELS } from '@/api/types';
import type { FuneralTermsView } from '@/api/types';
import { getFuneralTerms } from '@/api/funeral';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { selectParty, usePartyStore } from '@/store/partyStore';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { formatMonths } from '@/lib/dates';
import { normalisePhone, PHONE_MESSAGE } from '@/lib/patterns';
import { TypeTab } from '@/features/party/OnboardCustomerPage';
import {
  blankRegisterIndividualForm,
  type RegisterIndividualFormValues,
} from '@/features/party/registerIndividualForm';
import { FuneralLivesFields } from './FuneralLivesFields';
import {
  blankOpenCaseForm,
  openCaseFormSchema,
  type OpenCaseFormInput,
  type OpenCaseFormValues,
} from './openCaseForm';
import { QuotationPreview } from './QuotationPreview';
import { useTermAccountTerms } from './useTermAccountTerms';
import { pricingBasis, quotesHere, type ProspectInputs, type QuoteState } from './pricingBasis';
import { isAmount, isWholeMonths, premiumQuoteRequest } from './useQuotation';
import { quotationRequest } from './quotationRequest';
import { getAnnuityTerms, quoteAnnuity } from '@/api/annuity';
import { quoteFuneral } from '@/api/funeral';
import { quotePremium } from '@/api/products';
import { quoteProductSavings } from '@/api/accumulation';
import type { AnnuityQuoteView, AnnuityTermsView } from '@/api/types';
import { formatMoney } from '@/lib/money';
import { ChartColumn } from 'lucide-react';
import { QuotationInsights } from './QuotationInsights';
import type { QuoteResult } from './quoteResult';
import type { Alternative } from './useAlternatives';
import { funeralQuoteRequest } from './useFuneralQuotation';

type Who = 'PROSPECT' | 'CLIENT';

/**
 * The agents' quotation calculator (2026-10-20): what a product would cost a person and what it would pay them,
 * before anybody is registered.
 *
 * <p><b>Nothing is stored.</b> Every figure on this page is a read: the same quote endpoints the staff open-case
 * screen prices with, and the same quotation PDF, which takes the prospect's details as INPUTS. Changing a field,
 * reading the quote and downloading the paper write nothing to the database.
 *
 * <p>The plan's fields are the open-case form's own schema, so the funeral family fields and the quotation panel are
 * the components staff already use rather than a second copy; the person's fields carry registration's labels, so the
 * same control means the same thing on both screens.
 */
export function QuotationCalculatorPage() {
  const [who, setWho] = useState<Who>('PROSPECT');

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  useEffect(() => {
    void loadProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The plan: the open-case form's schema, never submitted here.
  const { register, watch, setValue, getValues, control, formState: { errors } } = useForm<
    OpenCaseFormInput,
    unknown,
    OpenCaseFormValues
  >({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(openCaseFormSchema), defaultValues: blankOpenCaseForm() });

  // The person, when they are not registered yet: registration's own fields, so onboarding can take them over.
  const person = useForm<RegisterIndividualFormValues>({ ...VALIDATE_ON_TOUCH, defaultValues: blankRegisterIndividualForm() });

  // eslint-disable-next-line react-hooks/incompatible-library -- see IssuePolicyPage
  const productId = watch('productId');
  const productVersionId = watch('productVersionId');
  const applicantPartyId = watch('applicantPartyId');
  const snapshot = useProductStore(selectProductSnapshot(productId));

  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  // Immediate annuities too (2026-10-23): the income a purchase price buys, for a client or a prospect.
  const offered = (products.data ?? []).filter(
    (p) => isSingleLifeProduct(p) && (quotesHere(p.portfolioCode) || p.portfolioCode === 'IANN'),
  );
  const selectedProduct = offered.find((p) => p.productId === productId);
  const isAnnuity = selectedProduct?.portfolioCode === 'IANN';
  const [annuityTerms, setAnnuityTerms] = useState<{ versionId: string; terms: AnnuityTermsView | null } | null>(null);
  useEffect(() => {
    if (!isAnnuity || !productId || !productVersionId) return undefined;
    let live = true;
    getAnnuityTerms(productId, productVersionId).then(
      (terms) => { if (live) setAnnuityTerms({ versionId: productVersionId, terms }); },
      () => { if (live) setAnnuityTerms({ versionId: productVersionId, terms: null }); },
    );
    return () => { live = false; };
  }, [isAnnuity, productId, productVersionId]);
  const annuityRead = annuityTerms?.versionId === productVersionId ? annuityTerms.terms : null;

  // The server's answer, kept here to be drawn over time below the form and priced against its alternatives.
  const [result, setResult] = useState<QuoteResult | null>(null);
  // A fixed-term deposit is paid once -- the portfolio UnderwritingApiImpl.refuseAnUnissuableDeposit refuses any
  // other frequency on (2026-10-21). It used to be refused only when the proposal reached the underwriter, after the
  // agent had already onboarded the customer; filtering the choice here means MONTHLY can never be picked at all.
  const isDeposit = selectedProduct?.portfolioCode === 'DEP';
  // A term account (2026-10-21): the customer is quoted at a rate inside the version's band, and may name what they
  // want at maturity instead of a contribution.
  const termAccountTerms = useTermAccountTerms(productId, productVersionId, selectedProduct?.portfolioCode);
  const isTermAccount = termAccountTerms !== null;
  // Either way round (the user, 2026-10-22): type what is wanted at maturity and the contribution is worked out, or the
  // contribution and what it pays at maturity is. Whichever was typed last leads; the other is filled from the quote.
  const [termAccountTarget, setTermAccountTarget] = useState('');
  const [leads, setLeads] = useState<'target' | 'contribution'>('target');
  // The answers, written in as they arrive; stable, so each runs once per answer.
  const fillSolvedContribution = useCallback((amount: string) => {
    if (getValues('sumAssuredAmount') !== amount) setValue('sumAssuredAmount', amount, { shouldDirty: true });
  }, [getValues, setValue]);
  const fillProjectedMaturity = useCallback((amount: string) => setTermAccountTarget(amount), []);

  useEffect(() => {
    if (snapshot.data?.productVersionId) setValue('productVersionId', snapshot.data.productVersionId);
    if (selectedProduct?.defaultCurrency) setValue('sumAssuredCurrency', selectedProduct.defaultCurrency);
  }, [snapshot.data, selectedProduct, setValue]);

  useEffect(() => {
    if (isDeposit && getValues('premiumFrequency') !== 'SINGLE') setValue('premiumFrequency', 'SINGLE');
  }, [isDeposit, setValue, getValues]);

  // An existing client is read from their record, exactly as the staff screen reads an applicant.
  const loadParty = usePartyStore((s) => s.loadParty);
  const client = usePartyStore(selectParty(who === 'CLIENT' ? applicantPartyId : ''));
  useEffect(() => {
    if (who === 'CLIENT' && applicantPartyId) void loadParty(applicantPartyId);
  }, [who, applicantPartyId, loadParty]);

  const p = person.watch();
  const prospect: ProspectInputs = {
    fullName: p.fullName,
    phoneNumber: p.phoneNumber,
    dateOfBirth: p.dateOfBirth,
    sex: p.sex,
    smokerStatus: p.smokerStatus,
    occupationClass: p.occupationClass,
  };
  const rating =
    who === 'CLIENT'
      ? {
          name: client.data?.displayName ?? '',
          dateOfBirth: client.data?.dateOfBirth,
          sex: client.data?.sex,
          smokerStatus: client.data?.smokerStatus,
          occupationClass: client.data?.occupationClass,
        }
      : {
          name: prospect.fullName,
          dateOfBirth: prospect.dateOfBirth || null,
          sex: prospect.sex || null,
          smokerStatus: prospect.smokerStatus || null,
          occupationClass: prospect.occupationClass || null,
        };

  // A funeral plan: the person quoted is the main member.
  const isFuneral = selectedProduct?.category === 'FUNERAL';
  // Keyed by the version they were read for, so a failure is shown for the product it belongs to and an answer for a
  // product since changed is never used. A plan that cannot be read used to leave no plan field and no word why.
  const [funeralTerms, setFuneralTerms] = useState<{
    versionId: string;
    terms?: FuneralTermsView | null;
    error?: ApiError;
  } | null>(null);
  useEffect(() => {
    if (!isFuneral || !productId || !productVersionId) return undefined;
    let live = true;
    getFuneralTerms(productId, productVersionId).then(
      (t) => { if (live) setFuneralTerms({ versionId: productVersionId, terms: t }); },
      (e: unknown) => { if (live) setFuneralTerms({ versionId: productVersionId, error: toApiError(e) }); },
    );
    return () => { live = false; };
  }, [isFuneral, productId, productVersionId]);
  const funeralRead = funeralTerms?.versionId === productVersionId ? funeralTerms : null;

  // Where the server's quotation stands: Proceed is offered only on a priced one.
  const [quoteState, setQuoteState] = useState<QuoteState>('incomplete');
  useEffect(() => {
    setValue('isFuneral', isFuneral);
  }, [isFuneral, setValue]);
  const mainMember = rating.name ? { name: rating.name, dateOfBirth: rating.dateOfBirth ?? null } : null;

  const singleRefusedByTerm = Number(watch('requestedTermMonths')) > 12;

  // Said beside the field, as it is typed: a malformed number is never sent, so without these the quotation would
  // just never appear and nobody would know why.
  const amountText = watch('sumAssuredAmount');
  const termText = watch('requestedTermMonths');
  const pptText = watch('premiumPayingTermMonths');
  const phoneText = person.watch('phoneNumber');
  const phoneError = who === 'PROSPECT' && phoneText.trim() && !normalisePhone(phoneText) ? PHONE_MESSAGE : undefined;
  const amountError = amountText && !isAmount(amountText)
    ? 'Digits only, e.g. 1500000 — no commas, spaces or currency' : undefined;
  const termError = termText && !isWholeMonths(termText) ? 'A whole number of months, e.g. 120' : undefined;
  const pptError = pptText && !isWholeMonths(pptText)
    ? 'A whole number of months, e.g. 120'
    : pptText && termText && isWholeMonths(termText) && Number(pptText) > Number(termText)
      ? 'Premiums cannot be paid for longer than the term'
      : undefined;

  const funeral = isFuneral
    ? {
        planCode: watch('funeralPlanCode'),
        mainMember,
        dependants: (watch('funeralDependants') ?? []).map((d) => ({
          role: d.role,
          fullName: d.fullName,
          dateOfBirth: d.dateOfBirth,
          student: d.student,
        })),
      }
    : undefined;
  const inputs = {
    productId,
    sumAssuredAmount: watch('sumAssuredAmount'),
    sumAssuredCurrency: watch('sumAssuredCurrency'),
    frequency: watch('premiumFrequency'),
    policyTermMonths: watch('requestedTermMonths'),
    dateOfBirth: rating.dateOfBirth,
    sex: rating.sex,
    smokerStatus: rating.smokerStatus,
    occupationClass: rating.occupationClass,
  };
  // Exactly what the PDF is asked for with: the inputs, never the figures on screen. An annuity is quoted here but its
  // proposal is still opened by the office, so there is nothing to save or hand over as a paper yet.
  const request = selectedProduct && !isAnnuity
    ? quotationRequest(pricingBasis(selectedProduct.portfolioCode), {
        inputs,
        premiumPayingTermMonths: watch('premiumPayingTermMonths'),
        commencementDate: watch('proposedCommencementDate'),
        productVersionId,
        applicantPartyId: who === 'CLIENT' ? applicantPartyId : '',
        prospect: who === 'PROSPECT' ? prospect : undefined,
        funeral,
        ratePercent: isTermAccount ? watch('proposedRatePercent') : undefined,
      })
    : null;

  const canProceed = request !== null && quoteState === 'quoted';
  const proceedHint = canProceed
    ? 'When the customer agrees to this quotation.'
    : isAnnuity
      ? 'An annuity proposal is opened by the office. Share these figures with the customer; the office takes it from there.'
    : !request
      ? who === 'PROSPECT' && !prospect.fullName.trim()
        ? "Enter the person's name, the product and the plan to proceed."
        : who === 'CLIENT' && !applicantPartyId
          ? 'Choose the client, the product and the plan to proceed.'
          : phoneError
            ? 'Correct the phone number, or leave it blank, to proceed.'
            : 'Complete the plan above to proceed.'
      : quoteState === 'loading'
        ? 'Pricing…'
        : quoteState === 'refused'
          ? 'This cannot proceed: the quotation above was refused. Change what it names and try again.'
          : 'Complete what the quotation above asks for to proceed.';

  // What else the customer could choose, each priced by the server for this same person (2026-10-23). Less and more
  // of what they asked for, rounded to a figure a person would say; another plan; another annuity form.
  const commencement = watch('proposedCommencementDate');
  const annuityFormCode = watch('annuityFormCode');
  const annuityFrequency = watch('annuityFrequency');
  const alternatives: Alternative[] = (() => {
    if (!result) return [];
    const round = (n: number) => Math.max(1000, Math.round(n / 1000) * 1000);
    const currency = watch('sumAssuredCurrency') || 'TZS';
    if (result.kind === 'premium') {
      const base = Number(result.sumAssured);
      const each = PREMIUM_FREQUENCY_LABELS[inputs.frequency as keyof typeof PREMIUM_FREQUENCY_LABELS]?.toLowerCase() ?? '';
      return [0.5, 1, 2].map((f) => {
        const cover = f === 1 ? base : round(base * f);
        return {
          key: `premium-${cover}`,
          label: `Cover of ${formatMoney({ amount: String(cover), currencyCode: currency })}`,
          current: f === 1,
          price: () => quotePremium(productId, premiumQuoteRequest(inputs, String(cover))).then((q) => ({
            value: formatMoney({ amount: String(q.instalmentAmount), currencyCode: q.currency ?? currency }),
            note: `${each} premium`,
          })),
        };
      });
    }
    if (result.kind === 'savings') {
      const q = result.quote;
      const term = q.termMonths ?? (termText ? Number(termText) : undefined);
      if (q.kind === 'TERM_ACCOUNT' && q.amount && q.termAccount) {
        const base = Number(q.amount.amount);
        const freq = q.termAccount.frequency;
        return [0.75, 1, 1.5].map((f) => {
          const amount = f === 1 ? base : round(base * f);
          return {
            key: `ta-${amount}-${term}-${q.ratePercent}`,
            label: `${formatMoney({ amount: String(amount), currencyCode: currency })} ${freq ? PREMIUM_FREQUENCY_LABELS[freq].toLowerCase() : ''}`,
            current: f === 1,
            price: () => quoteProductSavings(productId, productVersionId, {
              amount, currency, ...(term ? { termMonths: term } : {}),
              ...(commencement ? { asOf: commencement } : {}),
              ...(q.ratePercent ? { ratePercent: Number(q.ratePercent) } : {}),
              ...(freq ? { frequency: freq } : {}),
            }).then((alt) => {
              if (!alt.maturityValue) throw new Error('not priced');
              return { value: formatMoney(alt.maturityValue), note: 'paid out at maturity' };
            }),
          };
        });
      }
      if (q.kind === 'DEPOSIT' && q.amount && q.offeredTermMonths.length > 1) {
        const current = term ?? q.offeredTermMonths[0]!;
        const at = Math.max(0, q.offeredTermMonths.indexOf(current));
        const terms = q.offeredTermMonths.slice(Math.max(0, Math.min(at - 1, q.offeredTermMonths.length - 3)))
          .slice(0, 3);
        return terms.map((months) => ({
          key: `dep-${months}-${q.amount!.amount}`,
          label: `For ${formatMonths(months)}`,
          current: months === current,
          price: () => quoteProductSavings(productId, productVersionId, {
            amount: Number(q.amount!.amount), currency, termMonths: months,
            ...(commencement ? { asOf: commencement } : {}),
          }).then((alt) => {
            if (!alt.maturityValue) throw new Error('not priced');
            return { value: formatMoney(alt.maturityValue), note: 'paid out at maturity' };
          }),
        }));
      }
      return [];
    }
    if (result.kind === 'funeral' && funeral && funeralRead?.terms) {
      const plans = funeralRead.terms.plans.slice(0, 3);
      const family = {
        productId, productVersionId, planCode: funeral.planCode, frequency: inputs.frequency ?? '',
        commencement, mainMember: funeral.mainMember, dependants: funeral.dependants,
      };
      return plans.map((plan) => ({
        key: `fun-${plan.planCode}-${JSON.stringify(funeral.dependants)}-${inputs.frequency}`,
        label: plan.name || plan.planCode,
        current: plan.planCode === funeral.planCode,
        price: () => quoteFuneral(productId, productVersionId, funeralQuoteRequest(family, plan.planCode)).then((q) => ({
          value: formatMoney({ amount: String(q.instalment), currencyCode: 'TZS' }),
          note: `${q.frequency.toLowerCase()} for the family`,
        })),
      }));
    }
    if (result.kind === 'annuity' && annuityRead) {
      const forms = annuityRead.forms.slice(0, 3);
      return forms.map((form) => ({
        key: `ann-${form.formCode}-${result.purchasePrice}-${annuityFrequency}-${rating.dateOfBirth}`,
        label: form.guaranteeYears ? `Paid for life, at least ${form.guaranteeYears} years` : 'Paid for life',
        current: form.formCode === annuityFormCode,
        price: () => quoteAnnuity({
          productVersionId,
          formCode: form.formCode,
          frequency: annuityFrequency as AnnuityQuoteView['frequency'],
          purchasePrice: Number(result.purchasePrice),
          ...(who === 'CLIENT'
            ? { annuitantPartyId: applicantPartyId }
            : { annuitantDateOfBirth: rating.dateOfBirth ?? null, annuitantSex: (rating.sex || null) as 'FEMALE' | 'MALE' | null }),
        }).then((q) => ({ value: formatMoney(q.instalment), note: `${q.frequency === 'MONTHLY' ? 'a month' : q.frequency.toLowerCase()}, for life` })),
      }));
    }
    return [];
  })();

  // Proceed, then confirm: nothing is sent until the second click, and Cancel sends nothing at all.
  const navigate = useNavigate();
  const [step, setStep] = useState<'quote' | 'confirm'>('quote');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);
  async function confirm() {
    if (!request) return;
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await acceptQuotation(request);
      navigate(
        who === 'CLIENT' ? `../quotation/${saved.quotationId}` : `../customers/new?quotation=${saved.quotationId}`,
        { relative: 'path' },
      );
    } catch (e) {
      setSaveError(toApiError(e));
      setSaving(false);
    }
  }

  // A product sold on ONE term fills that term in itself, and a bounded one says its bounds -- as the staff
  // open-case form does, so the calculator never invites a term the product will refuse.
  const termBounds = snapshot.data?.eligibility ?? null;
  const fixedTermMonths =
    termBounds?.minTermMonths != null && termBounds.minTermMonths === termBounds.maxTermMonths
      ? termBounds.minTermMonths
      : null;
  useEffect(() => {
    if (fixedTermMonths != null) setValue('requestedTermMonths', String(fixedTermMonths));
  }, [fixedTermMonths, setValue]);
  const termHint =
    fixedTermMonths != null
      ? `Sold on one term only: ${formatMonths(fixedTermMonths)}.`
      : termBounds?.minTermMonths != null && termBounds.maxTermMonths != null
        ? `This product sells terms from ${formatMonths(termBounds.minTermMonths)} to ${formatMonths(termBounds.maxTermMonths)}.`
        : undefined;

  return (
    <>
      <PageHeader
        title="Quotation calculator"
        description="What a product costs and what it pays, before anybody is registered. Nothing is saved."
      />

      {/*
        One form in all but element: an edit anywhere on the page clears a refusal shown in the confirmation.

        Two columns on a desk or a tablet held sideways (2026-10-22): what the customer tells the agent on the left, the
        quotation on the right, kept in view while they talk -- every change on the left reprices it where both can
        see. On a phone it is one column, in the order the conversation goes: the person, the plan, the price.
      */}
      <div
        className="grid items-start gap-6 px-6 pb-8 lg:grid-cols-[minmax(0,40rem)_minmax(20rem,26rem)]"
        data-edit-scope
      >
        <div className="min-w-0 space-y-5 rounded-xl border border-border bg-surface p-6 shadow-card">
        <div className="inline-flex gap-1 rounded-xl bg-control p-1">
          <TypeTab label="New person" active={who === 'PROSPECT'} onClick={() => setWho('PROSPECT')} />
          <TypeTab label="Existing client" active={who === 'CLIENT'} onClick={() => setWho('CLIENT')} />
        </div>

        {who === 'PROSPECT' ? (
          <fieldset className="space-y-4">
            <legend className="pb-2 text-eyebrow text-subtle-foreground uppercase">The person</legend>
            <FormField label="Full name">
              <Input placeholder="Amina Hassan" {...person.register('fullName')} />
            </FormField>
            <div className="grid grid-cols-2 gap-3">
              <FormField label="Date of birth">
                <Controller
                  control={person.control}
                  name="dateOfBirth"
                  render={({ field }) => (
                    <DatePicker
                      value={field.value || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
              <FormField label="Phone number (optional)" error={phoneError}>
                <Input placeholder="0712 345 678" {...person.register('phoneNumber')} />
              </FormField>
              <FormField label="Sex">
                <Select {...person.register('sex')}>
                  <option value="">Not recorded</option>
                  <option value="FEMALE">Female</option>
                  <option value="MALE">Male</option>
                </Select>
              </FormField>
              <FormField label="Smoker status">
                <Select {...person.register('smokerStatus')}>
                  <option value="">Not recorded</option>
                  <option value="NON_SMOKER">Non-smoker</option>
                  <option value="SMOKER">Smoker</option>
                  <option value="UNKNOWN">Asked, declined to say</option>
                </Select>
              </FormField>
            </div>
          </fieldset>
        ) : (
          <FormField label="Client" hint="Your registered clients. Their details are read from their record.">
            <Controller
              control={control}
              name="applicantPartyId"
              render={({ field }) => (
                <PartyPicker
                  value={field.value || null}
                  onChange={(partyId) => field.onChange(partyId ?? '')}
                  placeholder="Search your clients by name"
                />
              )}
            />
          </FormField>
        )}

        <fieldset className="space-y-4 border-t border-border pt-3">
          <legend className="pr-2 text-eyebrow text-subtle-foreground uppercase">The plan</legend>
          <FormField
            label="Product"
            hint="Life, endowment, savings, deposit, funeral and annuity products. Unit-linked and group cover are quoted by the office."
          >
            {isInitialLoad(products) ? (
              <p className="text-xs text-muted-foreground">Loading products…</p>
            ) : products.status === 'error' ? (
              <p className="text-xs text-status-danger-fg">Could not load products.</p>
            ) : (
              <Select {...register('productId')}>
                <option value="">Select a product</option>
                {offered.map((product) => (
                  <option key={product.productId} value={product.productId}>
                    {product.productName} ({product.productCode})
                  </option>
                ))}
              </Select>
            )}
            {productId && isInitialLoad(snapshot) && (
              <p className="mt-1 text-xs text-muted-foreground">Resolving product version…</p>
            )}
            {productId && snapshot.status === 'error' && snapshot.error && (
              <div className="mt-1">
                <InlineError sticky error={snapshot.error} />
              </div>
            )}
          </FormField>

          {isFuneral && funeralRead?.error && (
            <InlineError sticky error={funeralRead.error} lead="This funeral plan's terms could not be read:" />
          )}
          {isFuneral && funeralRead && !funeralRead.error && !funeralRead.terms && (
            <p role="alert" className="text-xs text-status-danger-fg">
              This product's current version has no funeral plans set up, so it cannot be quoted. Ask the office.
            </p>
          )}
          {isFuneral && funeralRead?.terms && (
            <FuneralLivesFields terms={funeralRead.terms} mainMember={mainMember} register={register} control={control}
              errors={errors} setValue={setValue} />
          )}

          {/* An immediate annuity: the form and how often it pays; the amount below is the purchase price. */}
          {isAnnuity && annuityTerms && !annuityRead && (
            <p role="alert" className="text-xs text-status-danger-fg">
              This product&apos;s current version has no annuity forms set up, so it cannot be quoted. Ask the office.
            </p>
          )}
          {isAnnuity && annuityRead && (
            <div className="grid grid-cols-2 gap-4">
              <FormField label="Annuity form">
                <Select {...register('annuityFormCode')}>
                  <option value="">Choose a form</option>
                  {annuityRead.forms.map((f) => (
                    <option key={f.formCode} value={f.formCode}>
                      {f.guaranteeYears ? `For life, at least ${f.guaranteeYears} years` : 'For life'}
                      {f.joint ? `, joint ${f.survivorPercent}%` : ''}
                      {Number(f.escalationPercent) > 0 ? `, rising ${f.escalationPercent}% a year` : ''}
                      {` (${f.formCode})`}
                    </option>
                  ))}
                </Select>
              </FormField>
              <FormField label="Income paid">
                <Select {...register('annuityFrequency')}>
                  <option value="">Choose how often</option>
                  {annuityRead.frequencies.map((f) => (
                    <option key={f.frequency} value={f.frequency}>
                      {{ MONTHLY: 'Monthly', QUARTERLY: 'Quarterly', SEMI_ANNUAL: 'Every six months', ANNUAL: 'Annually' }[f.frequency] ?? f.frequency}
                    </option>
                  ))}
                </Select>
              </FormField>
            </div>
          )}

          {/* A term account: what is wanted at maturity, or the contribution -- either one, and the other follows. */}
          {isTermAccount && termAccountTerms && (
            <div className="grid grid-cols-2 gap-4">
              <FormField
                label="Wanted at maturity"
                hint={leads === 'contribution' && termAccountTarget.trim() !== ''
                  ? 'Worked out from the contribution: what it pays at maturity.'
                  : 'Or type the contribution below, and this fills itself.'}
              >
                <Input
                  inputMode="decimal"
                  placeholder="5000000"
                  value={termAccountTarget}
                  onChange={(e) => {
                    setTermAccountTarget(e.target.value);
                    setLeads('target');
                    // Typed away to nothing, the contribution it worked out goes with it.
                    if (e.target.value.trim() === '') setValue('sumAssuredAmount', '');
                  }}
                />
              </FormField>
              <FormField
                label="Rate (% a year)"
                hint={`This product offers ${termAccountTerms.minRatePercent}% to ${termAccountTerms.maxRatePercent}%, fixed for the term.`}
              >
                <Input inputMode="decimal" placeholder={String(termAccountTerms.minRatePercent)} {...register('proposedRatePercent')} />
              </FormField>
            </div>
          )}

          <div className="grid grid-cols-[1fr_auto] gap-2">
            <FormField
              label={isFuneral ? "Sum assured (the main member's benefit on the plan)"
                : isTermAccount ? 'Contribution per payment' : isAnnuity ? 'Purchase price' : 'Sum assured or amount'}
              error={amountError}
              hint={isTermAccount && leads === 'target' && (watch('sumAssuredAmount') ?? '').trim() !== ''
                ? 'Worked out from the amount wanted at maturity.'
                : undefined}
            >
              <Input placeholder="1500000.00" readOnly={isFuneral}
                {...register('sumAssuredAmount', {
                  onChange: (e: { target: { value: string } }) => {
                    if (!isTermAccount) return;
                    setLeads('contribution');
                    // Typed away to nothing, what it worked out at maturity goes with it.
                    if (e.target.value.trim() === '') setTermAccountTarget('');
                  },
                })} />
            </FormField>
            <FormField label="Currency">
              <Input className="w-20 uppercase" {...register('sumAssuredCurrency')} />
            </FormField>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {!isFuneral && !isAnnuity && (
              <>
                <FormField label="Term (months)" hint={termHint} error={termError}>
                  <Input placeholder="120" readOnly={fixedTermMonths != null} {...register('requestedTermMonths')} />
                </FormField>
                <FormField label="Premium-paying term (months)" error={pptError}>
                  <Input
                    placeholder={watch('premiumFrequency') === 'SINGLE' ? 'Blank — paid once' : 'Same as the term'}
                    {...register('premiumPayingTermMonths')}
                  />
                </FormField>
              </>
            )}
            {!isAnnuity && (
            <FormField
              label="Premium frequency"
              hint={isDeposit ? 'Paid once — a fixed-term deposit takes no recurring premium.' : undefined}
            >
              <Select {...register('premiumFrequency')} disabled={isDeposit}>
                <option value="">Not stated</option>
                {PREMIUM_FREQUENCIES.filter(
                  (f) =>
                    !(isFuneral && f === 'SINGLE') &&
                    !(f === 'SINGLE' && singleRefusedByTerm) &&
                    !(isDeposit && f !== 'SINGLE'),
                ).map((f) => (
                  <option key={f} value={f}>
                    {PREMIUM_FREQUENCY_LABELS[f]}
                  </option>
                ))}
              </Select>
            </FormField>
            )}
            <FormField label="Cover from" hint="Blank means today.">
              <Controller
                control={control}
                name="proposedCommencementDate"
                render={({ field }) => (
                  <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />
                )}
              />
            </FormField>
          </div>
        </fieldset>
        </div>

        {/* The quotation and the one act on it. Sticky beside the inputs, and bounded to the screen so Proceed never
            slides below it on a short laptop; it scrolls on its own when a long quotation is taller than that. */}
        <div className="min-w-0 space-y-4 lg:sticky lg:top-[var(--pagebar-h,0px)] lg:col-start-2 lg:row-span-2 lg:row-start-1 lg:max-h-[calc(100dvh-var(--pagebar-h,0px)-1rem)] lg:overflow-y-auto lg:pb-1">
        <QuotationPreview
          forCustomer
          inputs={inputs}
          premiumPayingTermMonths={watch('premiumPayingTermMonths')}
          commencementDate={watch('proposedCommencementDate')}
          portfolioCode={selectedProduct?.portfolioCode}
          productVersionId={productVersionId}
          applicantPartyId={who === 'CLIENT' ? applicantPartyId : ''}
          prospect={who === 'PROSPECT' ? prospect : undefined}
          funeral={funeral}
          onQuoteState={setQuoteState}
          onResult={setResult}
          annuity={isAnnuity
            ? {
                formCode: annuityFormCode,
                frequency: annuityFrequency,
                annuitantPartyId: who === 'CLIENT' ? applicantPartyId : '',
                dateOfBirth: rating.dateOfBirth ?? null,
                sex: rating.sex ?? null,
              }
            : undefined}
          termAccount={
            isTermAccount
              ? {
                  ratePercent: watch('proposedRatePercent'),
                  // Sent only while it leads: otherwise the contribution is quoted and this is its answer.
                  targetAmount: leads === 'target' ? termAccountTarget : '',
                  onUseContribution: fillSolvedContribution,
                  onUseMaturity: leads === 'contribution' ? fillProjectedMaturity : undefined,
                }
              : undefined
          }
        />

        {/* The customer agrees: the one place on this screen that writes anything, and only after a second click.
            Offered only on a quotation the server has priced -- never on a refusal, or on inputs it was not asked
            about -- and it says why when it is not. */}
        {/* Pinned to the foot of the column, so on a short screen the next step is in view while the quotation
            above it scrolls. */}
        <div className="lg:sticky lg:bottom-0">
        {step === 'quote' ? (
          <div className="space-y-2 rounded-xl border border-border bg-surface p-4 shadow-card">
            <Button
              type="button"
              variant="primary"
              className="w-full"
              disabled={!canProceed}
              onClick={() => setStep('confirm')}
            >
              {who === 'CLIENT' ? 'Proceed to proposal' : 'Proceed to onboarding'}
            </Button>
            <p className="text-xs text-muted-foreground">{proceedHint}</p>
            {result && quoteState === 'quoted' && (
              <Button
                type="button"
                variant="outline"
                className="w-full"
                onClick={() => document.getElementById('quotation-insights')?.scrollIntoView({
                  behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
                  block: 'start',
                })}
              >
                <ChartColumn className="size-4" aria-hidden />
                See it over time, and compare options
              </Button>
            )}
          </div>
        ) : (
          <ConfirmAct
            heading="Has the customer agreed to this quotation?"
            consequence={
              <>
                Saves this quotation for {rating.name || 'the customer'}: {selectedProduct?.productName ?? 'the product'}
                {request?.sumAssured != null ? `, ${request.currency} ${formatAmount(request.sumAssured)}` : ''}
                {request?.frequency ? `, ${PREMIUM_FREQUENCY_LABELS[request.frequency].toLowerCase()}` : ''}.{' '}
                {who === 'CLIENT'
                  ? 'Then you submit it as a proposal.'
                  : 'Then you register the customer, with these details filled in.'}
              </>
            }
            reversal="Cancel on the next screen discards it. A quotation not submitted as a proposal by the end of today is discarded."
            confirmLabel={who === 'CLIENT' ? 'Confirm and start the proposal' : 'Confirm and start onboarding'}
            busy={saving}
            // Inputs changed while the question was open, and the new ones are not priced (yet, or at all).
            confirmDisabled={!canProceed}
            onConfirm={() => void confirm()}
            onCancel={() => {
              setStep('quote');
              setSaveError(null);
            }}
          >
            {saveError && <InlineError error={saveError} />}
          </ConfirmAct>
        )}
        </div>
        </div>

      </div>

      {/* The quotation over time and its alternatives, at full width once there is an answer to draw (2026-10-23). */}
      <div className="space-y-5 px-6 pb-8">
        {result && quoteState === 'quoted' && (
          <section id="quotation-insights" aria-label="The quotation over time" className="scroll-mt-[calc(var(--pagebar-h,0px)+1rem)]">
            <QuotationInsights result={result} alternatives={alternatives} />
          </section>
        )}
        <WhatHappensNext />
      </div>
    </>
  );
}

/** A sum the agent typed, with grouping -- formatting only, never arithmetic. */
function formatAmount(amount: number): string {
  return new Intl.NumberFormat('en-GB', { maximumFractionDigits: 2 }).format(amount);
}

/** The road from this quotation to a policy, in the order it is travelled. */
function WhatHappensNext() {
  return (
    <Panel title="What happens next" subtitle="From this quotation to a policy.">
      <ol className="list-decimal space-y-1.5 px-4 pb-4 pl-8 text-sm">
        <li>Quote, and give the customer the quotation PDF to take away. Nothing is saved.</li>
        <li>When the customer agrees, proceed and confirm: the accepted quotation is saved.</li>
        <li>Register the customer. Their details come from the quotation.</li>
        <li>Submit the proposal. It goes to an underwriter.</li>
        <li>
          The underwriter accepts it, or returns it to you with what to correct. A returned proposal appears under
          Needs your action; correct it and resubmit.
        </li>
        <li>Once accepted, the policy is issued. The final premium is confirmed at acceptance.</li>
      </ol>
    </Panel>
  );
}
