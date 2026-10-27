import { zodResolver } from '@hookform/resolvers/zod';
import { Check, CircleHelp, Plus, X } from 'lucide-react';
import { useEffect, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useForm, useFieldArray, useWatch, Controller, type Control, type FieldErrors } from 'react-hook-form';
import {
  BENEFIT_CALCULATION_METHODS,
  BENEFIT_CALCULATION_METHOD_LABELS,
  BENEFIT_TYPES,
  RATING_FACTOR_TYPES,
  type ProductCategory,
} from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { BUCKET_LABEL, MODEL_LABEL, PORTFOLIO_LABEL, portfolioLabel, type PortfolioCode } from '@/lib/ifrs17';
import { changeProductPortfolio } from '@/api/accountCharges';
import { toApiError, type ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { revealIfHidden } from '@/lib/reveal';
import { DatePicker } from '@/components/DatePicker';
import { Button } from '@/components/ui/button';
import { Tip } from '@/components/ui/tooltip';
import { FormField } from '@/components/FormField';
import { selectPublishing, useProductStore } from '@/store/productStore';
import {
  ACCOUNT_CATEGORIES,
  keepsAccount,
  blankBonusSurrenderRow,
  blankBenefitRow,
  blankDepositBand,
  blankCashValueRow,
  FREE_LOOK_CATEGORIES,
  SCHEDULED_CATEGORIES,
  blankPayoutRow,
  blankRatingFactorRow,
  doubleCountedFactorMessage,
  isBaseRateRowPriced,
  publishVersionFormSchema,
  toApiRequest,
  type PublishVersionFormInput,
  type PublishVersionFormValues,
} from './publishVersionSchema';
import { Input, Select, Textarea } from '@/components/ui/input';
import { CheckboxField } from '@/components/ui/checkbox';
import { InlineError } from '@/components/InlineError';
import { humanizeStatus } from '@/lib/status';
import { blankAnnuityFields } from './annuitySchema';
import { AnnuityTermsSection } from './AnnuityTermsSection';
import { blankFuneralFields } from './funeralSchema';
import { FuneralTermsSection } from './FuneralTermsSection';
import { blankUnitLinkedFields } from './unitLinkedSchema';
import { UnitLinkedTermsSection } from './UnitLinkedTermsSection';
import { BaseRateGrid } from './BaseRateGrid';
import { VersionChargePicker } from './VersionChargePicker';
import {
  KIND_LABEL,
  KIND_SUMMARY,
  SAVINGS_KINDS,
  defaultValueBasis,
  isRatedKind,
  kindOf,
  paysSections,
  stepsFor,
  type ProductKind,
  type StepId,
} from './productKind';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { UnsavedGuard } from '@/components/UnsavedGuard';

/** An example shaped like the factor it belongs to. */
const BAND_PLACEHOLDER: Record<(typeof RATING_FACTOR_TYPES)[number], string> = {
  AGE: 'Label, optional',
  SUM_ASSURED_BAND: 'Label, optional',
  OCCUPATION_CLASS: 'e.g. CLASS_2',
  SMOKER_STATUS: 'e.g. NON_SMOKER',
};

/**
 * The rating rows a version must carry and that change no price (2026-10-22).
 *
 * The server requires an AGE and a SUM_ASSURED_BAND factor on every version that is not funeral or unit-linked --
 * deposits and accounts included, where a premium multiplier means nothing -- so people typed two rows of nothing to
 * be allowed to publish. These are those rows, filled in: every age, every amount, times one. A kind not priced from
 * a premium table carries them unseen; a rated kind starts with them and adjusts from there.
 */
function neutralRatingRows(): PublishVersionFormInput['ratingTable'] {
  return [
    { ...blankRatingFactorRow(), factorType: 'AGE', ageFrom: '0', ageTo: '120' },
    { ...blankRatingFactorRow(), factorType: 'SUM_ASSURED_BAND', band: 'All amounts' },
  ];
}

function isNeutralAge(row: PublishVersionFormInput['ratingTable'][number] | undefined): boolean {
  return !!row && row.factorType === 'AGE' && row.ageFrom === '0' && row.ageTo === '120' && !row.band && Number(row.multiplier) === 1;
}

/** Which step a field lives on, so a refused publish can send the person to it. Unlisted fields are pricing terms. */
const STEP_OF_FIELD: Partial<Record<string, StepId>> = {
  valueBasis: 'kind',
  effectiveDate: 'basics',
  retirementDate: 'basics',
  tiraReference: 'basics',
  tiraApprovalDate: 'basics',
  expectedProfitabilityBucket: 'basics',
  measurementModelOverride: 'basics',
  survivalInvestmentComponentPercent: 'basics',
  description: 'basics',
  minEntryAge: 'eligibility',
  maxEntryAge: 'eligibility',
  minTermMonths: 'eligibility',
  maxTermMonths: 'eligibility',
  minSumAssured: 'eligibility',
  maxSumAssured: 'eligibility',
  benefitSchedule: 'pays',
  cashValueBasisReference: 'pays',
  cashValueBasisDate: 'pays',
  cashValuePaidUpBasis: 'pays',
  cashValueMinYears: 'pays',
  cashValueRows: 'pays',
  freeLookDays: 'pays',
  deathBenefitPremiumPercent: 'pays',
  payoutRows: 'pays',
  survivalBenefitsDeductedFromDeath: 'pays',
  proofOfLifeIntervalMonths: 'pays',
  withProfits: 'pays',
  bonusMethod: 'pays',
  bonusPaidUpParticipates: 'pays',
  bonusSurrenderBasis: 'pays',
  bonusSurrenderRows: 'pays',
  accountCharges: 'charges',
  accountChargeIds: 'charges',
};

/** Every message in the form's errors, once each, with the step it belongs to. */
function problemsOf(errors: FieldErrors<PublishVersionFormInput>): { step: StepId; message: string }[] {
  const out: { step: StepId; message: string }[] = [];
  const walk = (node: unknown, step: StepId) => {
    if (!node || typeof node !== 'object') return;
    const message = (node as { message?: unknown }).message;
    if (typeof message === 'string' && message && !out.some((p) => p.message === message)) out.push({ step, message });
    for (const [key, child] of Object.entries(node)) {
      if (key !== 'ref' && key !== 'message' && key !== 'type') walk(child, step);
    }
  };
  for (const [key, node] of Object.entries(errors)) walk(node, STEP_OF_FIELD[key] ?? 'pricing');
  return out;
}

/**
 * Publishing a version -- the only way a product becomes sellable -- as steps (2026-10-22).
 *
 * It was one page of up to nine sections, decided by category alone, so a fixed deposit was asked for premium rate
 * bands, instalment loadings, with-profits and a cash-value table. Now the product's kind decides the steps and what
 * each one asks; the person moves freely between them, and Review lists anything still wrong with a link to it.
 */
export function PublishVersionForm({
  productId,
  category,
  onPublished,
  portfolioCode,
  draft = false,
  description = '',
}: {
  productId: string;
  category: ProductCategory;
  onPublished: () => void;
  /** The product's IFRS 17 portfolio, and whether it can still change: see `requiredPortfolios`. */
  portfolioCode?: string | undefined;
  draft?: boolean | undefined;
  /** The product's current description, which this version starts from. */
  description?: string | null | undefined;
}) {
  const publishVersion = useProductStore((s) => s.publishVersion);
  const resetPublishVersion = useProductStore((s) => s.resetPublishVersion);
  const publishing = useProductStore(selectPublishing(productId));
  const schema = publishVersionFormSchema(category);

  // `publishing` outlives this form; a previous visit's refusal must not greet this one.
  useEffect(() => {
    resetPublishVersion(productId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId]);

  const {
    register,
    handleSubmit,
    control,
    getValues,
    setValue,
    formState: { errors, isDirty },
  } = useForm<PublishVersionFormInput, unknown, PublishVersionFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(schema),
    defaultValues: {
      expectedProfitabilityBucket: 'REMAINING',
      measurementModelOverride: '',
      survivalInvestmentComponentPercent: '',
      description: description ?? '',
      effectiveDate: '',
      retirementDate: '',
      // Funeral is priced by its premium grid and unit-linked by its mortality table: no rating rows at all.
      ratingTable: category === 'FUNERAL' || category === 'UNIT_LINKED' ? [] : neutralRatingRows(),
      baseRates: [],
      benefitSchedule: [blankBenefitRow()],
      fundDefinitions: [],
      minEntryAge: '',
      maxEntryAge: '',
      minTermMonths: '',
      maxTermMonths: '',
      minSumAssured: '',
      maxSumAssured: '',
      monthlyLoadingPercent: '',
      quarterlyLoadingPercent: '',
      tiraReference: '',
      tiraApprovalDate: '',
      cashValueBasisReference: '',
      cashValueBasisDate: '',
      cashValuePaidUpBasis: '',
      cashValueMinYears: '',
      cashValueRows: [],
      freeLookDays: '',
      proofOfLifeIntervalMonths: '',
      survivalBenefitsDeductedFromDeath: '',
      deathBenefitPremiumPercent: '',
      payoutRows: [],
      // A product already filed as a deposit or a savings account starts as one.
      valueBasis: defaultValueBasis(category, portfolioCode),
      guaranteedRatePercent: '',
      minimumBalance: '',
      minRatePercent: '',
      maxRatePercent: '',
      accountCharges: [],
      accountChargeIds: [],
      depositTerms: [],
      depositBands: [],
      withProfits: false,
      bonusMethod: '',
      bonusPaidUpParticipates: false,
      bonusSurrenderBasis: '',
      bonusSurrenderRows: [],
      ...blankAnnuityFields(),
      ...blankFuneralFields(),
      ...blankUnitLinkedFields(),
    },
  });
  const bonusSurrenderRows = useFieldArray({ control, name: 'bonusSurrenderRows' });
  const withProfits = useWatch({ control, name: 'withProfits' });
  const bonusSurrenderBasis = useWatch({ control, name: 'bonusSurrenderBasis' });
  const cashValueRows = useFieldArray({ control, name: 'cashValueRows' });
  // A deposit's terms are the grid's columns and its bands the rows: a new term adds a cell to every band.
  const depositTerms = useFieldArray({ control, name: 'depositTerms' });
  const depositBands = useFieldArray({ control, name: 'depositBands' });
  function addDepositTerm() {
    depositTerms.append({ months: '' });
    getValues('depositBands').forEach((band, i) => setValue(`depositBands.${i}.rates`, [...band.rates, '']));
  }
  function removeDepositTerm(j: number) {
    depositTerms.remove(j);
    getValues('depositBands').forEach((band, i) =>
      setValue(`depositBands.${i}.rates`, band.rates.filter((_, k) => k !== j)),
    );
  }
  const valueBasis = useWatch({ control, name: 'valueBasis' });
  const annuityKind = useWatch({ control, name: 'annuityKind' });
  const deferredAnnuity = category === 'ANNUITY' && annuityKind === 'DEFERRED';
  const payoutRows = useFieldArray({ control, name: 'payoutRows' });
  const watchedPayoutKinds = useWatch({ control, name: 'payoutRows' })?.map((r) => r?.kind);

  const ratingTable = useFieldArray({ control, name: 'ratingTable' });
  const ratingRows = useWatch({ control, name: 'ratingTable' });
  const baseRateRows = useWatch({ control, name: 'baseRates' });
  const priced = (baseRateRows ?? []).some(isBaseRateRowPriced);
  const benefitSchedule = useFieldArray({ control, name: 'benefitSchedule' });
  const benefitRows = useWatch({ control, name: 'benefitSchedule' });

  const kind: ProductKind = kindOf(category, valueBasis);
  const rated = isRatedKind(kind);
  const hasAccount = keepsAccount(valueBasis) && (ACCOUNT_CATEGORIES.includes(category) || deferredAnnuity);
  const pays = paysSections(category, kind);

  /*
    What a kind without premium pricing must not carry: rate bands and instalment loadings (the server refuses a
    loading on a deposit), and a rating table that does anything. Switching kind clears them rather than leaving a
    hidden value to be refused at the end.
  */
  useEffect(() => {
    if (rated || category === 'FUNERAL' || category === 'UNIT_LINKED') return;
    setValue('ratingTable', neutralRatingRows());
    setValue('baseRates', []);
    setValue('monthlyLoadingPercent', '');
    setValue('quarterlyLoadingPercent', '');
  }, [rated, category, setValue]);

  /*
    Priced, age is a key of the rate table and an AGE multiplier is refused as double-counting; unpriced, an AGE factor
    is required. The untouched neutral row follows the table so neither refusal is ever met for it.
  */
  useEffect(() => {
    if (!rated || category === 'FUNERAL' || category === 'UNIT_LINKED') return;
    const rows = getValues('ratingTable');
    const neutral = rows.findIndex(isNeutralAge);
    if (priced && neutral >= 0) ratingTable.remove(neutral);
    if (!priced && !rows.some((r) => r.factorType === 'AGE')) ratingTable.prepend(neutralRatingRows()[0]!);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [priced, rated]);

  const steps = stepsFor(category, kind, hasAccount);
  const [stepId, setStepId] = useState<StepId>(steps[0]!.id);
  // A step can disappear when the kind changes (Charges, for one); fall back to the first.
  const current = steps.some((s) => s.id === stepId) ? stepId : steps[0]!.id;
  const position = steps.findIndex((s) => s.id === current);
  const [reviewed, setReviewed] = useState(false);
  const problems = reviewed ? problemsOf(errors) : [];
  const stepsWithProblems = new Set(problems.map((p) => p.step));

  // Held between Publish and the second, deliberate click: publishing retires the active version for good.
  const [pending, setPending] = useState<PublishVersionFormValues | null>(null);

  /*
    The portfolio a version that keeps an account must sit in -- ProductApiImpl.refuseUnlessASavingsPortfolio. A draft
    takes the right one here and is moved to it on publish; a published product's portfolio is fixed.
  */
  const requiredPortfolios: readonly string[] | null =
    valueBasis === 'DEPOSIT' ? ['DEP']
      : deferredAnnuity ? ['PEN', 'DANN']
        : keepsAccount(valueBasis) ? ['SAV', 'PEN']
          : null;
  const portfolioMismatch = requiredPortfolios !== null && !!portfolioCode && !requiredPortfolios.includes(portfolioCode);
  const [portfolioPick, setPortfolioPick] = useState('');
  const portfolioTarget = requiredPortfolios
    ? (requiredPortfolios.includes(portfolioPick) ? portfolioPick : requiredPortfolios[0]!)
    : '';
  const [portfolioError, setPortfolioError] = useState<ApiError | null>(null);

  async function onSubmit(values: PublishVersionFormValues) {
    if (portfolioMismatch && draft) {
      setPortfolioError(null);
      try {
        await changeProductPortfolio(productId, portfolioTarget);
      } catch (e) {
        setPortfolioError(toApiError(e));
        return;
      }
    }
    await publishVersion(productId, toApiRequest(values, category));
    if (useProductStore.getState().publishing[productId]?.status === 'success') {
      onPublished();
    }
  }

  function goTo(id: StepId) {
    setStepId(id);
    setPending(null);
    // Back to the step bar only when it has scrolled out of sight, and then to just under the sticky page bar -- a
    // plain scrollIntoView put the bar behind the header and moved the page on every click (the user, 2026-10-22).
    revealIfHidden(document.getElementById('publish-version-form'));
  }

  // Enter in a field moves nothing and publishes nothing before Review; on Review it is the Publish button's.
  function holdEnter(e: KeyboardEvent<HTMLFormElement>) {
    if (e.key === 'Enter' && current !== 'review' && (e.target as HTMLElement).tagName === 'INPUT') e.preventDefault();
  }

  return (
    <form
      id="publish-version-form"
      className="scroll-mt-[calc(var(--pagebar-h,0px)+1rem)] space-y-5"
      onKeyDown={holdEnter}
      onSubmit={(e) => {
        setReviewed(true);
        void handleSubmit((v) => setPending(v))(e);
      }}
    >
      <UnsavedGuard
        when={isDirty && publishing.status !== 'loading' && publishing.status !== 'success'}
        what="The product version you were building"
      />

      <ol aria-label="Steps" className="flex flex-wrap gap-1 rounded-xl bg-control p-1">
        {steps.map((s, i) => {
          const active = s.id === current;
          const done = i < position;
          return (
            <li key={s.id}>
              <button
                type="button"
                aria-current={active ? 'step' : undefined}
                onClick={() => goTo(s.id)}
                className={cn(
                  'inline-flex min-h-8 items-center gap-1.5 rounded-lg px-3 text-sm font-medium transition-colors pointer-coarse:min-h-11',
                  active ? 'bg-surface text-foreground shadow-raise' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                <span
                  className={cn(
                    'grid size-5 place-items-center rounded-full text-xs',
                    done ? 'bg-accent text-accent-foreground' : active ? 'bg-foreground text-background' : 'bg-control-hover',
                  )}
                  aria-hidden
                >
                  {done ? <Check className="size-3" /> : i + 1}
                </span>
                {s.label}
                {stepsWithProblems.has(s.id) && (
                  <span className="size-1.5 rounded-full bg-status-danger-fg" aria-label="has a problem" />
                )}
              </button>
            </li>
          );
        })}
      </ol>

      <div className="space-y-5">
        {current === 'kind' && renderKind()}
        {current === 'basics' && renderBasics()}
        {current === 'eligibility' && renderEligibility()}
        {current === 'pricing' && renderPricing()}
        {current === 'pays' && renderPays()}
        {current === 'charges' && renderCharges()}
        {current === 'review' && renderReview()}
      </div>

      {current !== 'review' && (
        <div className="flex items-center gap-2 border-t border-border pt-4">
          {position > 0 && (
            <Button type="button" variant="ghost" onClick={() => goTo(steps[position - 1]!.id)}>
              Back
            </Button>
          )}
          <Button type="button" variant="primary" onClick={() => goTo(steps[position + 1]!.id)}>
            Next: {steps[position + 1]!.label}
          </Button>
        </div>
      )}
    </form>
  );

  // ---- Steps ------------------------------------------------------------------------------------

  function renderKind() {
    return (
      <Section title="What kind of product is this?">
        <div role="radiogroup" aria-label="Product type" className="grid gap-2 sm:grid-cols-2">
          {SAVINGS_KINDS.map(({ basis, kind: k }) => (
            <label
              key={basis}
              className={cn(
                'flex cursor-pointer gap-3 rounded-lg border p-3 transition-colors hover:bg-hover',
                valueBasis === basis ? 'border-accent bg-selected' : 'border-border',
              )}
            >
              <input type="radio" value={basis} className="mt-1 accent-accent" {...register('valueBasis')} />
              <span>
                <span className="block text-sm font-medium">{KIND_LABEL[k]}</span>
                <span className="block text-xs text-muted-foreground">{KIND_SUMMARY[k]}</span>
              </span>
            </label>
          ))}
        </div>
        {errors.valueBasis?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.valueBasis.message}</p>}
        {portfolioMismatch && draft && requiredPortfolios && (
          <FormField label="IFRS 17 portfolio" hint={`Moves from ${portfolioLabel(portfolioCode)} when this version is published.`}>
            <Select value={portfolioTarget} onChange={(e) => setPortfolioPick(e.target.value)}>
              {requiredPortfolios.map((p) => (
                <option key={p} value={p}>{PORTFOLIO_LABEL[p as PortfolioCode] ?? p}</option>
              ))}
            </Select>
          </FormField>
        )}
        {portfolioMismatch && !draft && (
          <p role="alert" className="text-xs text-status-danger-fg">
            This product is in {portfolioLabel(portfolioCode)} and already published, so it cannot become a{' '}
            {KIND_LABEL[kind].toLowerCase()}. Create a new product in{' '}
            {(requiredPortfolios ?? []).map((p) => portfolioLabel(p)).join(' or ')}.
          </p>
        )}
      </Section>
    );
  }

  function renderBasics() {
    return (
      <>
        <Section title="Basics">
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Effective date" error={errors.effectiveDate?.message}>
              <Controller
                control={control}
                name="effectiveDate"
                render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
              />
            </FormField>
            {/* `retirementDate` on the server: the day this version stops being sold. Not a pension's retirement. */}
            <FormField label="Stop selling on (optional)" error={errors.retirementDate?.message}>
              <Controller
                control={control}
                name="retirementDate"
                render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
              />
            </FormField>
            <FormField
              label="Description (optional)"
              hint="What staff read when choosing this product. Changing it here updates the product from this version on."
              error={errors.description?.message}
              className="col-span-2"
            >
              <Textarea className="min-h-20 py-2" {...register('description')} />
            </FormField>
            <FormField label="TIRA filing reference" error={errors.tiraReference?.message}>
              <Input placeholder="TIRA/LIFE/2026/0001" {...register('tiraReference')} />
            </FormField>
            <FormField label="TIRA approval date" error={errors.tiraApprovalDate?.message}>
              <Controller
                control={control}
                name="tiraApprovalDate"
                render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
              />
            </FormField>
          </div>
        </Section>
        <Section title="Accounting (IFRS 17)">
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Expected profitability">
              <Select {...register('expectedProfitabilityBucket')}>
                {(Object.keys(BUCKET_LABEL) as (keyof typeof BUCKET_LABEL)[]).map((b) => (
                  <option key={b} value={b}>{BUCKET_LABEL[b]}</option>
                ))}
              </Select>
            </FormField>
            <FormField label="Measurement model override">
              <Select {...register('measurementModelOverride')}>
                <option value="">None — the register decides</option>
                {(Object.keys(MODEL_LABEL) as (keyof typeof MODEL_LABEL)[]).map((m) => (
                  <option key={m} value={m}>{MODEL_LABEL[m]}</option>
                ))}
              </Select>
            </FormField>
            {/* Only a version paying survival benefits or income instalments uses it. */}
            {(SCHEDULED_CATEGORIES.includes(category) || category === 'ANNUITY') && (
              <FormField
                label="Survival benefit investment component (%)"
                hint="Blank for none."
                error={errors.survivalInvestmentComponentPercent?.message}
              >
                <Input inputMode="decimal" placeholder="e.g. 80" {...register('survivalInvestmentComponentPercent')} />
              </FormField>
            )}
          </div>
        </Section>
      </>
    );
  }

  function renderEligibility() {
    const amount = kind === 'DEPOSIT' ? 'deposit' : 'sum assured';
    return (
      <Section
        title="Who can buy"
        hint="Leave a limit blank for none."
        tip="An age or term outside these is refused at sale. An amount outside them is flagged, not refused, so reinsurance can take it."
      >
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Minimum entry age" error={errors.minEntryAge?.message}>
            <Input inputMode="numeric" placeholder="18" {...register('minEntryAge')} />
          </FormField>
          <FormField label="Maximum entry age" error={errors.maxEntryAge?.message}>
            <Input inputMode="numeric" placeholder="65" {...register('maxEntryAge')} />
          </FormField>
          <FormField label="Minimum term (months)" error={errors.minTermMonths?.message}>
            <Input inputMode="numeric" placeholder="60" {...register('minTermMonths')} />
          </FormField>
          <FormField label="Maximum term (months)" error={errors.maxTermMonths?.message}>
            <Input inputMode="numeric" placeholder="360" {...register('maxTermMonths')} />
          </FormField>
          <FormField label={`Minimum ${amount}`} error={errors.minSumAssured?.message}>
            <Input inputMode="decimal" placeholder="500000.00" {...register('minSumAssured')} />
          </FormField>
          <FormField label={`Maximum ${amount}`} error={errors.maxSumAssured?.message}>
            <Input inputMode="decimal" placeholder="300000000.00" {...register('maxSumAssured')} />
          </FormField>
        </div>
      </Section>
    );
  }

  function renderPricing() {
    return (
      <>
        {rated && (
          <>
            <Section
              title="Base rates"
              hint="Without rates the product cannot be quoted."
              tip="Rates cannot be added after publishing. A blank cell means that combination is not sold, never zero."
            >
              <BaseRateGrid control={control} setValue={setValue} errors={errors} />
            </Section>
            {renderAdjustments()}
          </>
        )}
        {kind === 'DEPOSIT' && renderDepositGrid()}
        {(kind === 'SAVINGS_ACCOUNT' || kind === 'TERM_ACCOUNT' || deferredAnnuity) && renderAccountRates()}
        {category === 'ANNUITY' && (
          <AnnuityTermsSection register={register} control={control} errors={errors} setValue={setValue} />
        )}
        {category === 'FUNERAL' && (
          <FuneralTermsSection register={register} control={control} errors={errors} setValue={setValue} getValues={getValues} />
        )}
        {category === 'UNIT_LINKED' && (
          <UnitLinkedTermsSection register={register} control={control} errors={errors} currency={undefined} />
        )}
        {/* Only a premium table is loaded for paying by instalment: the rated kinds and a funeral plan (FuneralQuoter). */}
        {(rated || category === 'FUNERAL') && (
          <Section title="Instalment loadings" hint="Added to the premium when it is paid monthly or quarterly.">
            <div className="grid grid-cols-2 gap-3">
              <FormField label="Monthly loading %" error={errors.monthlyLoadingPercent?.message}>
                <Input inputMode="decimal" placeholder="8" {...register('monthlyLoadingPercent')} />
              </FormField>
              <FormField label="Quarterly loading %" error={errors.quarterlyLoadingPercent?.message}>
                <Input inputMode="decimal" placeholder="3" {...register('quarterlyLoadingPercent')} />
              </FormField>
            </div>
          </Section>
        )}
      </>
    );
  }

  function renderAdjustments() {
    return (
      <Section
        title="Adjustments"
        hint="Multipliers on the rate, e.g. a higher rate for large amounts. 1 means no change."
      >
        <div className="@container">
          <div className="mb-1 hidden items-center gap-2 px-1 text-xs text-subtle-foreground @min-[34rem]:grid @min-[34rem]:grid-cols-[9rem_minmax(4rem,1fr)_6rem_6rem_4.5rem_2rem]">
            <span>Factor</span>
            <span>Band</span>
            <span className="text-right">From</span>
            <span className="text-right">To</span>
            <span className="text-right">Multiplier</span>
            <span aria-hidden />
          </div>
          <div className="space-y-2">
            {ratingTable.fields.map((field, index) => {
              const rowType = ratingRows?.[index]?.factorType;
              const rowErrors = errors.ratingTable?.[index];
              const doubleCounted = priced && (rowType === 'AGE' || rowType === 'SMOKER_STATUS');
              const rowMessage = doubleCounted
                ? doubleCountedFactorMessage(rowType)
                : (rowErrors?.band?.message ??
                  rowErrors?.ageFrom?.message ??
                  rowErrors?.ageTo?.message ??
                  rowErrors?.sumAssuredFrom?.message ??
                  rowErrors?.sumAssuredTo?.message ??
                  rowErrors?.multiplier?.message);
              return (
                <div key={field.id}>
                  <div className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 @min-[34rem]:rounded-none @min-[34rem]:border-0 @min-[34rem]:p-0 @min-[34rem]:grid @min-[34rem]:grid-cols-[9rem_minmax(4rem,1fr)_6rem_6rem_4.5rem_2rem]">
                    <Select
                      inputSize="sm"
                      className="w-44 shrink-0 @min-[34rem]:w-full"
                      aria-label={`Rating factor ${index + 1} type`}
                      aria-invalid={doubleCounted ? true : undefined}
                      {...register(`ratingTable.${index}.factorType`)}
                    >
                      {RATING_FACTOR_TYPES.map((t) => (
                        <option key={t} value={t}>{humanizeStatus(t)}</option>
                      ))}
                    </Select>
                    <Input
                      inputSize="sm"
                      className="min-w-32 flex-1 @min-[34rem]:w-full @min-[34rem]:min-w-0"
                      placeholder={BAND_PLACEHOLDER[rowType ?? 'AGE']}
                      aria-label={`Rating factor ${index + 1} band`}
                      aria-invalid={!doubleCounted && rowErrors?.band ? true : undefined}
                      {...register(`ratingTable.${index}.band`)}
                    />
                    {rowType === 'AGE' ? (
                      <>
                        <Input type="number" min={0} inputSize="sm" className="w-24 shrink-0 text-right @min-[34rem]:w-full" placeholder="from"
                          aria-label={`Rating factor ${index + 1} from age`}
                          aria-invalid={!doubleCounted && rowErrors?.ageFrom ? true : undefined}
                          {...register(`ratingTable.${index}.ageFrom`)} />
                        <Input type="number" min={0} inputSize="sm" className="w-24 shrink-0 text-right @min-[34rem]:w-full" placeholder="to"
                          aria-label={`Rating factor ${index + 1} to age`}
                          aria-invalid={!doubleCounted && rowErrors?.ageTo ? true : undefined}
                          {...register(`ratingTable.${index}.ageTo`)} />
                      </>
                    ) : rowType === 'SUM_ASSURED_BAND' ? (
                      <>
                        <Input type="number" min={0} inputSize="sm" className="w-24 shrink-0 text-right @min-[34rem]:w-full" placeholder="from"
                          aria-label={`Rating factor ${index + 1} from sum assured`}
                          aria-invalid={!doubleCounted && rowErrors?.sumAssuredFrom ? true : undefined}
                          {...register(`ratingTable.${index}.sumAssuredFrom`)} />
                        <Input type="number" min={0} inputSize="sm" className="w-24 shrink-0 text-right @min-[34rem]:w-full" placeholder="to"
                          aria-label={`Rating factor ${index + 1} to sum assured`}
                          aria-invalid={!doubleCounted && rowErrors?.sumAssuredTo ? true : undefined}
                          {...register(`ratingTable.${index}.sumAssuredTo`)} />
                      </>
                    ) : (
                      <>
                        <span className="hidden w-24 shrink-0 text-right text-xs text-subtle-foreground @min-[34rem]:inline">—</span>
                        <span className="hidden w-24 shrink-0 text-right text-xs text-subtle-foreground @min-[34rem]:inline">—</span>
                      </>
                    )}
                    <Input type="number" min={0} step="0.01" inputSize="sm" className="w-24 shrink-0 text-right @min-[34rem]:w-full" placeholder="1.0"
                      aria-label={`Rating factor ${index + 1} multiplier`}
                      aria-invalid={!doubleCounted && rowErrors?.multiplier ? true : undefined}
                      {...register(`ratingTable.${index}.multiplier`)} />
                    <Button type="button" size="icon" variant="ghost" className="shrink-0" aria-label="Remove rating factor"
                      onClick={() => ratingTable.remove(index)}>
                      <X />
                    </Button>
                  </div>
                  {rowMessage && <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">{rowMessage}</p>}
                </div>
              );
            })}
          </div>
        </div>
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => ratingTable.append(blankRatingFactorRow())}>
          <Plus /> Add rating factor
        </Button>
        {errors.ratingTable?.root?.message && (
          <p role="alert" className="text-xs text-status-danger-fg">{errors.ratingTable.root.message}</p>
        )}
      </Section>
    );
  }

  function renderDepositGrid() {
    return (
      <Section
        title="Deposit rates"
        hint="Rate for the whole term, by deposit amount and term."
        tip="A band runs from its amount up to the next band's. The lowest band starts at the minimum deposit. Nothing is paid in or out before maturity."
      >
        {depositTerms.fields.length > 0 && (
          <div className="overflow-x-auto">
            <table className="text-sm" aria-label="Deposit rates">
              <thead>
                <tr>
                  <th className="px-1 py-1 text-left text-xs font-medium text-muted-foreground">Deposits from</th>
                  {depositTerms.fields.map((field, j) => (
                    <th key={field.id} className="px-1 py-1">
                      <div className="flex items-center gap-1">
                        <Input type="number" min={1} inputSize="sm" className="w-20 text-right" placeholder="Months"
                          aria-label={`Term ${j + 1} in months`} {...register(`depositTerms.${j}.months`)} />
                        <Button type="button" size="icon" variant="ghost" aria-label={`Remove term ${j + 1}`}
                          onClick={() => removeDepositTerm(j)}>
                          <X className="size-4" />
                        </Button>
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {depositBands.fields.map((field, i) => (
                  <tr key={field.id}>
                    <td className="px-1 py-1">
                      <Input inputSize="sm" inputMode="decimal" className="w-32 text-right" placeholder="500000"
                        aria-label={`Band ${i + 1} starts at`} {...register(`depositBands.${i}.minAmount`)} />
                    </td>
                    {depositTerms.fields.map((term, j) => (
                      <td key={term.id} className="px-1 py-1">
                        <Input inputSize="sm" inputMode="decimal" className="w-20 text-right" placeholder="%"
                          aria-label={`Band ${i + 1} rate for term ${j + 1}`} {...register(`depositBands.${i}.rates.${j}`)} />
                      </td>
                    ))}
                    <td className="px-1 py-1">
                      <Button type="button" size="icon" variant="ghost" aria-label={`Remove band ${i + 1}`}
                        onClick={() => depositBands.remove(i)}>
                        <X className="size-4" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {depositGridMessages(errors).map((message) => (
          <p key={message} role="alert" className="text-xs text-status-danger-fg">{message}</p>
        ))}
        <div className="flex gap-2">
          <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={addDepositTerm}>
            <Plus className="size-4" /> Add a term
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={() => depositBands.append(blankDepositBand(depositTerms.fields.length))}>
            <Plus className="size-4" /> Add a band
          </Button>
        </div>
      </Section>
    );
  }

  function renderAccountRates() {
    return (
      <Section
        title={deferredAnnuity ? 'The account before it vests' : 'Interest'}
        hint={valueBasis === 'TERM_ACCOUNT' ? 'Each case picks a rate inside this band; the policy keeps it.' : undefined}
      >
        <div className="grid grid-cols-2 gap-3">
          {valueBasis === 'TERM_ACCOUNT' && (
            <>
              <FormField label="Minimum rate (% a year)" error={errors.minRatePercent?.message}>
                <Input inputMode="decimal" placeholder="3" {...register('minRatePercent')} />
              </FormField>
              <FormField label="Maximum rate (% a year)" error={errors.maxRatePercent?.message}>
                <Input inputMode="decimal" placeholder="7" {...register('maxRatePercent')} />
              </FormField>
            </>
          )}
          {valueBasis === 'ACCOUNT' && (
            <FormField label="Guaranteed interest rate (% a year)" error={errors.guaranteedRatePercent?.message}>
              <Input inputMode="decimal" placeholder="3" {...register('guaranteedRatePercent')} />
            </FormField>
          )}
          <FormField label="Minimum balance after a withdrawal" error={errors.minimumBalance?.message}>
            <Input inputMode="decimal" placeholder="50000" {...register('minimumBalance')} />
          </FormField>
        </div>
      </Section>
    );
  }

  function renderPays() {
    return (
      <>
        <Section title="Benefits" hint="What a claim pays.">
          {errors.benefitSchedule?.root?.message && (
            <p role="alert" className="text-xs text-status-danger-fg">{errors.benefitSchedule.root.message}</p>
          )}
          <div className="space-y-2">
            {benefitSchedule.fields.map((field, index) => {
              const method = benefitRows?.[index]?.calculationMethod;
              const rowErrors = errors.benefitSchedule?.[index];
              const rowMessage = rowErrors?.calculationMethod?.message ?? rowErrors?.percent?.message ?? rowErrors?.flatAmount?.message;
              return (
                <div key={field.id}>
                  <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_7rem_auto] items-center gap-2">
                    <Select inputSize="sm" aria-label={`Benefit ${index + 1} type`} {...register(`benefitSchedule.${index}.benefitType`)}>
                      {BENEFIT_TYPES.map((t) => <option key={t} value={t}>{humanizeStatus(t)}</option>)}
                    </Select>
                    <Select inputSize="sm" aria-label={`Benefit ${index + 1} calculation method`}
                      aria-invalid={rowErrors?.calculationMethod ? true : undefined}
                      {...register(`benefitSchedule.${index}.calculationMethod`)}>
                      {BENEFIT_CALCULATION_METHODS.map((m) => <option key={m} value={m}>{BENEFIT_CALCULATION_METHOD_LABELS[m]}</option>)}
                    </Select>
                    {method === 'PERCENTAGE_OF_SUM_ASSURED' ? (
                      <Input type="number" min={0} step="0.01" inputSize="sm" className="text-right" placeholder="%"
                        aria-label={`Benefit ${index + 1} percentage`} aria-invalid={rowErrors?.percent ? true : undefined}
                        {...register(`benefitSchedule.${index}.percent`)} />
                    ) : method === 'FLAT_AMOUNT' ? (
                      <Input type="number" min={0} step="0.01" inputSize="sm" className="text-right" placeholder="amount"
                        aria-label={`Benefit ${index + 1} flat amount`} aria-invalid={rowErrors?.flatAmount ? true : undefined}
                        {...register(`benefitSchedule.${index}.flatAmount`)} />
                    ) : (
                      <span className="text-right text-xs text-subtle-foreground">No amount</span>
                    )}
                    <Button type="button" size="icon" variant="ghost" className="shrink-0" aria-label="Remove benefit"
                      onClick={() => benefitSchedule.remove(index)}>
                      <X />
                    </Button>
                  </div>
                  {rowMessage && <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">{rowMessage}</p>}
                </div>
              );
            })}
          </div>
          <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => benefitSchedule.append(blankBenefitRow())}>
            <Plus /> Add benefit
          </Button>
        </Section>

        {(FREE_LOOK_CATEGORIES.includes(category) || SCHEDULED_CATEGORIES.includes(category)) && renderPayouts()}
        {pays.cashValue && renderCashValue()}
        {pays.withProfits && renderWithProfits()}
      </>
    );
  }

  function renderPayouts() {
    return (
      <Section
        title={pays.payouts ? 'Payouts and free-look' : 'Free-look'}
        hint={pays.payouts ? 'What it pays while the life assured is alive. An endowment needs a maturity payout.' : 'Days the customer may cancel and get premiums back.'}
      >
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Free-look days" error={errors.freeLookDays?.message}>
            <Input type="number" min={1} max={365} placeholder="15" {...register('freeLookDays')} />
          </FormField>
          <FormField label="Death benefit at least this % of premiums" error={errors.deathBenefitPremiumPercent?.message}>
            <Input type="number" min={0} step="0.01" placeholder="Optional" {...register('deathBenefitPremiumPercent')} />
          </FormField>
        </div>
        {pays.payouts && (
          <>
            <div className="space-y-2">
              {payoutRows.fields.map((field, index) => {
                const rowErrors = errors.payoutRows?.[index];
                const rowMessage =
                  rowErrors?.kind?.message ?? rowErrors?.fromPolicyYear?.message ?? rowErrors?.toPolicyYear?.message ??
                  rowErrors?.amountBasis?.message ?? rowErrors?.amountValue?.message ?? rowErrors?.frequency?.message;
                // A maturity pays once, on the policy's own maturity date: years and frequency are refused there.
                const endOfTerm = watchedPayoutKinds?.[index] === 'MATURITY';
                return (
                  <div key={field.id}>
                    <div className="flex flex-wrap items-center gap-2">
                      <Select inputSize="sm" className="w-40 shrink-0" aria-label={`Payout ${index + 1} kind`}
                        aria-invalid={rowErrors?.kind ? true : undefined} {...register(`payoutRows.${index}.kind`)}>
                        <option value="">Choose…</option>
                        <option value="MATURITY">Maturity</option>
                        <option value="SURVIVAL">Survival benefit</option>
                        <option value="INCOME">Income</option>
                      </Select>
                      <Input type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="From year"
                        disabled={endOfTerm} aria-label={`Payout ${index + 1} from policy year`}
                        aria-invalid={rowErrors?.fromPolicyYear ? true : undefined} {...register(`payoutRows.${index}.fromPolicyYear`)} />
                      <Input type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="To year"
                        disabled={endOfTerm} aria-label={`Payout ${index + 1} to policy year`}
                        aria-invalid={rowErrors?.toPolicyYear ? true : undefined} {...register(`payoutRows.${index}.toPolicyYear`)} />
                      <Select inputSize="sm" className="w-36 shrink-0" aria-label={`Payout ${index + 1} basis`}
                        aria-invalid={rowErrors?.amountBasis ? true : undefined} {...register(`payoutRows.${index}.amountBasis`)}>
                        <option value="">Choose…</option>
                        <option value="PERCENT_OF_SA">% of sum assured</option>
                        <option value="FIXED">Fixed amount</option>
                        <option value="ACCOUNT_VALUE">The whole account value</option>
                      </Select>
                      <Input type="number" min={0} step="0.01" inputSize="sm" className="w-24 shrink-0 text-right" placeholder="Value"
                        aria-label={`Payout ${index + 1} amount`} aria-invalid={rowErrors?.amountValue ? true : undefined}
                        {...register(`payoutRows.${index}.amountValue`)} />
                      <Select inputSize="sm" className="w-32 shrink-0" disabled={endOfTerm} aria-label={`Payout ${index + 1} frequency`}
                        aria-invalid={rowErrors?.frequency ? true : undefined} {...register(`payoutRows.${index}.frequency`)}>
                        <option value="">Choose…</option>
                        <option value="ANNUAL">Yearly</option>
                        <option value="SEMI_ANNUAL">Twice a year</option>
                        <option value="QUARTERLY">Quarterly</option>
                        <option value="MONTHLY">Monthly</option>
                      </Select>
                      <Button type="button" size="icon" variant="ghost" className="shrink-0" aria-label="Remove payout row"
                        onClick={() => payoutRows.remove(index)}>
                        <X />
                      </Button>
                    </div>
                    {rowMessage && <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">{rowMessage}</p>}
                  </div>
                );
              })}
            </div>
            {errors.payoutRows?.root?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.payoutRows.root.message}</p>}
            {errors.payoutRows?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.payoutRows.message}</p>}
            <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => payoutRows.append(blankPayoutRow())}>
              <Plus /> Add a payout
            </Button>
            <div className="grid grid-cols-2 gap-3">
              {watchedPayoutKinds?.includes('SURVIVAL') && (
                <FormField label="Survival benefits paid come off the death benefit" error={errors.survivalBenefitsDeductedFromDeath?.message}>
                  <Select {...register('survivalBenefitsDeductedFromDeath')}>
                    <option value="">Choose…</option>
                    <option value="true">Yes, deduct them</option>
                    <option value="false">No, pay the full death benefit</option>
                  </Select>
                </FormField>
              )}
              {watchedPayoutKinds?.includes('INCOME') && (
                <FormField label="Proof of life every (months)" error={errors.proofOfLifeIntervalMonths?.message}>
                  <Input type="number" min={1} max={60} placeholder="12" {...register('proofOfLifeIntervalMonths')} />
                </FormField>
              )}
            </div>
          </>
        )}
      </Section>
    );
  }

  function renderCashValue() {
    return (
      <Section
        title="Cash value (optional)"
        hint="Surrender value per 1,000 sum assured, by policy year."
        tip="Leave empty for no cash value. A table needs the actuarial sign-off it was issued under."
      >
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Actuarial basis reference" error={errors.cashValueBasisReference?.message}>
            <Input placeholder="ACT/2026/ENDOW-01" {...register('cashValueBasisReference')} />
          </FormField>
          <FormField label="Basis date" error={errors.cashValueBasisDate?.message}>
            <Controller
              control={control}
              name="cashValueBasisDate"
              render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
            />
          </FormField>
          <FormField label="Paid-up basis" error={errors.cashValuePaidUpBasis?.message}>
            <Select {...register('cashValuePaidUpBasis')}>
              <option value="">Choose…</option>
              <option value="PROPORTIONATE">Proportionate</option>
              <option value="TABLE">From the table</option>
            </Select>
          </FormField>
          <FormField label="Years before any value" error={errors.cashValueMinYears?.message}>
            <Select {...register('cashValueMinYears')}>
              <option value="">Choose…</option>
              <option value="2">2</option>
              <option value="3">3</option>
            </Select>
          </FormField>
        </div>
        <div className="space-y-2">
          {cashValueRows.fields.map((field, index) => {
            const rowErrors = errors.cashValueRows?.[index];
            const rowMessage =
              rowErrors?.policyYear?.message ?? rowErrors?.ageFrom?.message ?? rowErrors?.ageTo?.message ??
              rowErrors?.cashValuePerMille?.message ?? rowErrors?.paidUpPerMille?.message;
            return (
              <div key={field.id}>
                <div className="flex flex-wrap items-center gap-2">
                  <Input type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="Year"
                    aria-label={`Cash value ${index + 1} policy year`} aria-invalid={rowErrors?.policyYear ? true : undefined}
                    {...register(`cashValueRows.${index}.policyYear`)} />
                  <Input type="number" min={0} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="Age from"
                    aria-label={`Cash value ${index + 1} entry age from`} aria-invalid={rowErrors?.ageFrom ? true : undefined}
                    {...register(`cashValueRows.${index}.ageFrom`)} />
                  <Input type="number" min={0} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="Age to"
                    aria-label={`Cash value ${index + 1} entry age to`} aria-invalid={rowErrors?.ageTo ? true : undefined}
                    {...register(`cashValueRows.${index}.ageTo`)} />
                  <Input type="number" min={0} step="0.0001" inputSize="sm" className="text-right" placeholder="Value per 1,000"
                    aria-label={`Cash value ${index + 1} value per 1,000`} aria-invalid={rowErrors?.cashValuePerMille ? true : undefined}
                    {...register(`cashValueRows.${index}.cashValuePerMille`)} />
                  <Input type="number" min={0} step="0.0001" inputSize="sm" className="text-right" placeholder="Paid-up per 1,000"
                    aria-label={`Cash value ${index + 1} paid-up per 1,000`} aria-invalid={rowErrors?.paidUpPerMille ? true : undefined}
                    {...register(`cashValueRows.${index}.paidUpPerMille`)} />
                  <Button type="button" size="icon" variant="ghost" className="shrink-0" aria-label="Remove cash value row"
                    onClick={() => cashValueRows.remove(index)}>
                    <X />
                  </Button>
                </div>
                {rowMessage && <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">{rowMessage}</p>}
              </div>
            );
          })}
        </div>
        {errors.cashValueRows?.root?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.cashValueRows.root.message}</p>}
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => cashValueRows.append(blankCashValueRow())}>
          <Plus /> Add policy year
        </Button>
      </Section>
    );
  }

  function renderWithProfits() {
    return (
      <Section title="With profits (optional)">
        <CheckboxField label="With-profits version" {...register('withProfits')} />
        {errors.withProfits?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.withProfits.message}</p>}
        {withProfits && (
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <FormField label="Bonus method" error={errors.bonusMethod?.message}>
                <Select {...register('bonusMethod')}>
                  <option value="">Choose…</option>
                  <option value="SIMPLE">Simple (on the sum assured)</option>
                  <option value="COMPOUND">Compound (sum assured plus bonuses)</option>
                </Select>
              </FormField>
              <FormField label="Bonus surrender basis" error={errors.bonusSurrenderBasis?.message}>
                <Select {...register('bonusSurrenderBasis')}>
                  <option value="">Choose…</option>
                  <option value="NONE">None — bonuses add nothing to a surrender</option>
                  <option value="SUM_ASSURED_SCALE">The version&apos;s cash-value scale</option>
                  <option value="OWN_SCALE">Its own scale, below</option>
                </Select>
              </FormField>
            </div>
            <CheckboxField label="A paid-up policy still receives declarations" {...register('bonusPaidUpParticipates')} />
            {bonusSurrenderBasis === 'OWN_SCALE' && (
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">Value of each 1,000 of bonus on surrender, by completed years.</p>
                {bonusSurrenderRows.fields.map((field, index) => {
                  const rowErrors = errors.bonusSurrenderRows?.[index];
                  const rowMessage = rowErrors?.fromCompletedYears?.message ?? rowErrors?.perMille?.message;
                  return (
                    <div key={field.id}>
                      <div className="flex flex-wrap items-center gap-2">
                        <Input type="number" min={0} inputSize="sm" className="text-right" placeholder="From year"
                          aria-label={`Bonus surrender row ${index + 1} from completed years`}
                          {...register(`bonusSurrenderRows.${index}.fromCompletedYears`)} />
                        <Input inputSize="sm" inputMode="decimal" className="w-28 shrink-0 text-right" placeholder="Per mille"
                          aria-label={`Bonus surrender row ${index + 1} value per mille`}
                          {...register(`bonusSurrenderRows.${index}.perMille`)} />
                        <Button type="button" size="icon" variant="ghost" aria-label={`Remove bonus surrender row ${index + 1}`}
                          onClick={() => bonusSurrenderRows.remove(index)}>
                          <X className="size-4" />
                        </Button>
                      </div>
                      {rowMessage && <p role="alert" className="mt-1 text-xs text-status-danger-fg">{rowMessage}</p>}
                    </div>
                  );
                })}
                <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => bonusSurrenderRows.append(blankBonusSurrenderRow())}>
                  <Plus className="size-4" /> Add a bonus surrender row
                </Button>
              </div>
            )}
            {errors.bonusSurrenderRows?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.bonusSurrenderRows.message}</p>}
            {errors.bonusSurrenderRows?.root?.message && <p role="alert" className="text-xs text-status-danger-fg">{errors.bonusSurrenderRows.root.message}</p>}
          </div>
        )}
      </Section>
    );
  }

  function renderCharges() {
    return (
      <Section title="Charges (optional)" hint="Added from Account charges.">
        <Controller
          control={control}
          name="accountChargeIds"
          render={({ field }) => <VersionChargePicker value={field.value} onChange={field.onChange} />}
        />
      </Section>
    );
  }

  function renderReview() {
    return (
      <>
        <Section title="Review">
          <ReviewSummary control={control} kind={kind} category={category} />
        </Section>

        {problems.length > 0 && (
          <div role="alert" className="space-y-2 rounded-lg border border-status-danger-fg/40 bg-status-danger-bg p-3">
            <p className="text-sm font-medium text-status-danger-fg">
              {problems.length === 1 ? '1 thing to fix' : `${problems.length} things to fix`}
            </p>
            <ul className="space-y-1">
              {problems.map((p) => (
                <li key={p.message} className="flex flex-wrap items-baseline gap-x-2 text-sm">
                  <span>{p.message}</span>
                  <button type="button" className="text-xs font-medium text-status-danger-fg underline"
                    onClick={() => goTo(steps.some((s) => s.id === p.step) ? p.step : 'pricing')}>
                    Go to {steps.find((s) => s.id === p.step)?.label ?? 'Pricing'}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        {portfolioError && <InlineError error={portfolioError} lead="The product's portfolio could not be changed, so nothing was published:" />}
        {publishing.status === 'error' && publishing.error && <InlineError error={publishing.error} />}

        {pending ? (
          <ConfirmAct
            heading="Publish this version?"
            consequence={
              <>
                Make this the active version from <strong>{pending.effectiveDate}</strong>. Every policy priced from then
                on uses these terms, and the version that is active now is retired.
              </>
            }
            reversal={<>Nothing retracts it. Correcting a published price means publishing a further version.</>}
            confirmLabel="Publish and make active"
            busy={publishing.status === 'loading'}
            onConfirm={() => void onSubmit(pending)}
            onCancel={() => setPending(null)}
          />
        ) : (
          <div className="flex items-center gap-2 border-t border-border pt-4">
            <Button type="button" variant="ghost" onClick={() => goTo(steps[position - 1]!.id)}>
              Back
            </Button>
            <Button type="submit" variant="primary" pending={publishing.status === 'loading'}>
              Publish version
            </Button>
          </div>
        )}
      </>
    );
  }
}

/** A titled part of a step: a heading, at most one line under it, and the full rule behind a "?" when there is one. */
function Section({ title, hint, tip, children }: { title: string; hint?: string | undefined; tip?: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <div>
        <h3 className="flex items-center gap-1.5 text-sm font-semibold">
          {title}
          {tip && (
            <Tip content={tip}>
              <button type="button" aria-label={`About ${title.toLowerCase()}`} className="rounded-full text-muted-foreground hover:text-foreground">
                <CircleHelp className="size-3.5" aria-hidden />
              </button>
            </Tip>
          )}
        </h3>
        {hint && <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
      </div>
      {children}
    </section>
  );
}

/** What is about to be published, read back in the words it was entered in. Only the parts the kind has. */
function ReviewSummary({ control, kind, category }: { control: Control<PublishVersionFormInput>; kind: ProductKind; category: ProductCategory }) {
  const v = useWatch({ control });
  const range = (from?: string, to?: string, unit = '') =>
    from || to ? `${from || 'any'} – ${to || 'any'}${unit}` : 'No limit';
  const priced = (v.baseRates ?? []).filter((r) => r && isBaseRateRowPriced(r as PublishVersionFormInput['baseRates'][number]));
  const rows: [string, string][] = [
    ['Product type', KIND_LABEL[kind]],
    ['Effective', v.effectiveDate || 'Not set'],
    ...(v.retirementDate ? ([['Stop selling on', v.retirementDate]] as [string, string][]) : []),
    ['TIRA filing', v.tiraReference || 'Not set'],
    ['Entry ages', range(v.minEntryAge, v.maxEntryAge)],
    ['Terms', range(v.minTermMonths, v.maxTermMonths, ' months')],
    [kind === 'DEPOSIT' ? 'Deposits' : 'Sum assured', range(v.minSumAssured, v.maxSumAssured)],
  ];
  if (isRatedKind(kind)) {
    rows.push(['Base rates', priced.length ? `${Math.ceil(priced.length / 2)} age band${priced.length > 2 ? 's' : ''}` : 'None — cannot be quoted']);
  }
  if (v.monthlyLoadingPercent || v.quarterlyLoadingPercent) {
    rows.push(['Loadings', `Monthly ${v.monthlyLoadingPercent || 0}% · Quarterly ${v.quarterlyLoadingPercent || 0}%`]);
  }
  if (kind === 'DEPOSIT') rows.push(['Deposit rates', `${(v.depositBands ?? []).length} bands × ${(v.depositTerms ?? []).length} terms`]);
  if (kind === 'SAVINGS_ACCOUNT') rows.push(['Guaranteed rate', v.guaranteedRatePercent ? `${v.guaranteedRatePercent}% a year` : 'Not set']);
  if (kind === 'TERM_ACCOUNT') rows.push(['Rate band', range(v.minRatePercent, v.maxRatePercent, '% a year')]);
  rows.push(['Benefits', `${(v.benefitSchedule ?? []).length}`]);
  if ((FREE_LOOK_CATEGORIES.includes(category) || SCHEDULED_CATEGORIES.includes(category)) && v.freeLookDays) {
    rows.push(['Free-look', `${v.freeLookDays} days`]);
  }
  if ((v.accountChargeIds ?? []).length) rows.push(['Charges', `${(v.accountChargeIds ?? []).length} from Account charges`]);
  return (
    <dl className="divide-y divide-border rounded-lg border border-border">
      {rows.map(([label, value]) => (
        <div key={label} className="flex justify-between gap-4 px-3 py-2 text-sm">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="text-right">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Every message the deposit grid carries, once each: the grid's own, then each band's start and cell, then each term. */
function depositGridMessages(errors: FieldErrors<PublishVersionFormInput>): string[] {
  const out: string[] = [];
  const push = (m?: string) => {
    if (m && !out.includes(m)) out.push(m);
  };
  push(errors.depositBands?.message);
  push(errors.depositBands?.root?.message);
  push(errors.depositTerms?.message);
  type Leaf = { message?: string } | undefined;
  const bands = errors.depositBands as unknown as Record<string, { minAmount?: Leaf; rates?: Record<string, Leaf> } | undefined>;
  Object.values(bands ?? {}).forEach((band) => {
    push(band?.minAmount?.message);
    Object.values(band?.rates ?? {}).forEach((cell) => push(cell?.message));
  });
  const terms = errors.depositTerms as unknown as Record<string, { months?: Leaf } | undefined>;
  Object.values(terms ?? {}).forEach((term) => push(term?.months?.message));
  return out;
}
