import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm, useFieldArray, useWatch, Controller, type FieldErrors } from 'react-hook-form';
import {
  BENEFIT_CALCULATION_METHODS,
  BENEFIT_CALCULATION_METHOD_LABELS,
  BENEFIT_TYPES,
  IFRS_MEASUREMENT_MODELS,
  RATING_FACTOR_TYPES,
  type ProductCategory,
} from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DatePicker } from '@/components/DatePicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { selectPublishing, useProductStore } from '@/store/productStore';
import {
  ACCOUNT_CATEGORIES,
  WITH_PROFITS_CATEGORIES,
  blankBonusSurrenderRow,
  blankAccountChargeRow,
  blankBenefitRow,
  blankDepositBand,
  blankCashValueRow,
  CASH_VALUE_CATEGORIES,
  FREE_LOOK_CATEGORIES,
  SCHEDULED_CATEGORIES,
  blankPayoutRow,
  blankBaseRateBand,
  blankRatingFactorRow,
  doubleCountedFactorMessage,
  isBaseRateRowPriced,
  BASE_RATE_COLUMNS,
  publishVersionFormSchema,
  toApiRequest,
  type PublishVersionFormInput,
  type PublishVersionFormValues,
} from './publishVersionSchema';
import { Input, Select } from '@/components/ui/input';
import { CheckboxField } from '@/components/ui/checkbox';
import { InlineError } from '@/components/InlineError';
import { humanizeStatus } from '@/lib/status';
import { blankAnnuityFields } from './annuitySchema';
import { AnnuityTermsSection } from './AnnuityTermsSection';
import { blankFuneralFields } from './funeralSchema';
import { FuneralTermsSection } from './FuneralTermsSection';
import { blankUnitLinkedFields } from './unitLinkedSchema';
import { UnitLinkedTermsSection } from './UnitLinkedTermsSection';

/**
 * An example shaped like the factor it belongs to.
 *
 * Every row shared one placeholder, "Band, e.g. 18-30" -- an AGE example offered on a
 * SUM_ASSURED_BAND row, which is the row where the band text is not a label at all but the
 * value the rules engine matches on. Suggesting an age range there invites a band that
 * resolves against nothing.
 */
const BAND_PLACEHOLDER: Record<(typeof RATING_FACTOR_TYPES)[number], string> = {
  AGE: 'Label, optional',
  // Optional for the same reason AGE's is: the amounts beside it are what the platform rates
  // on now, and a band left blank is labelled from them rather than left to disagree with them.
  SUM_ASSURED_BAND: 'Label, optional',
  OCCUPATION_CLASS: 'e.g. CLASS_2',
  SMOKER_STATUS: 'e.g. NON_SMOKER',
};

/**
 * Every message the deposit grid carries, once each: the grid's own, then each band's start and
 * cell, then each term. RHF holds a nested array's errors as sparse objects, so they are walked
 * rather than indexed.
 */
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

/**
 * Publishing a version is the ONLY way a product ever becomes visible through
 * `GET /products` (it flips DRAFT -> ACTIVE) -- reused by both the new-product
 * wizard's second phase and, later, publishing a further version onto an
 * already-active product, since the wire shape and every validation rule are
 * identical either way.
 */
export function PublishVersionForm({
  productId,
  category,
  onPublished,
}: {
  productId: string;
  category: ProductCategory;
  onPublished: () => void;
}) {
  const publishVersion = useProductStore((s) => s.publishVersion);
  const resetPublishVersion = useProductStore((s) => s.resetPublishVersion);
  const publishing = useProductStore(selectPublishing(productId));
  const schema = publishVersionFormSchema(category);

  // `publishing` is keyed by productId and outlives this component's own
  // mount/unmount, so without this a stale rejection from a PREVIOUS visit to
  // this form (e.g. reopening "publish another version" after a failed attempt)
  // would resurface immediately -- the same bug found live on beneficiaries,
  // claims, and policy issuance, built in from the start here instead.
  useEffect(() => {
    resetPublishVersion(productId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId]);

  // Three generics: Input/Context/Output -- see BeneficiariesPanel.tsx for why
  // z.coerce.number() (multiplier, currentNav) forces the split.
  const {
    register,
    handleSubmit,
    control,
    getValues,
    setValue,
    formState: { errors },
  } = useForm<PublishVersionFormInput, unknown, PublishVersionFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      ifrsMeasurementModel: 'PAA',
      effectiveDate: '',
      retirementDate: '',
      // A FUNERAL version carries no rating factors (plan R1); every other starts with the two required.
      // A UNIT_LINKED version's cost of insurance is its mortality table (product step 6): no rating factors either.
      ratingTable: category === 'FUNERAL' || category === 'UNIT_LINKED' ? [] : [blankRatingFactorRow(), { ...blankRatingFactorRow(), factorType: 'SUM_ASSURED_BAND' }],
      baseRates: [],
      // One row from the start, like the rating table above: at least one benefit is now
      // required, and a panel that starts empty would make every publish begin with a
      // refusal for something the form could have offered.
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
      valueBasis: 'SCALE',
      guaranteedRatePercent: '',
      minimumBalance: '',
      accountCharges: [],
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
  // A fixed-term deposit: terms are the grid's columns, bands its rows. Adding a column adds a cell
  // to every band, so a band can never be shorter than the terms it must offer.
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
  // Product step 3: an ACCOUNT version carries its charges by policy year instead of a cash-value
  // table -- the server refuses both on one version, so choosing ACCOUNT hides the table.
  const accountCharges = useFieldArray({ control, name: 'accountCharges' });
  const valueBasis = useWatch({ control, name: 'valueBasis' });
  const annuityKind = useWatch({ control, name: 'annuityKind' });
  const deferredAnnuity = category === 'ANNUITY' && annuityKind === 'DEFERRED';
  const payoutRows = useFieldArray({ control, name: 'payoutRows' });
  // Which kinds are on the form right now, so the two conditional terms appear the moment a row
  // needs them. Watched rather than read off `payoutRows.fields`, which useFieldArray only
  // re-renders on append/remove -- a kind CHANGED in place would not show the field it requires.
  const watchedPayoutKinds = useWatch({ control, name: 'payoutRows' })?.map((r) => r?.kind);

  const ratingTable = useFieldArray({ control, name: 'ratingTable' });
  // useWatch, not watch(): one subscription for the whole array rather than a watch() call
  // per row inside the render loop, which is both fewer re-renders and the form the React
  // Compiler can reason about.
  const ratingRows = useWatch({ control, name: 'ratingTable' });
  const baseRates = useFieldArray({ control, name: 'baseRates' });
  // Priced or not decides which factors are legal, so the rating table above has to react
  // to what is typed in the base rate panel below. `baseRates.fields` will not do it --
  // useFieldArray re-renders on append/remove, not on keystrokes.
  const baseRateRows = useWatch({ control, name: 'baseRates' });
  const priced = (baseRateRows ?? []).some(isBaseRateRowPriced);
  const benefitSchedule = useFieldArray({ control, name: 'benefitSchedule' });
  // Which amount input a benefit row shows follows the method selected on that row, and
  // `benefitSchedule.fields` will not do it for the reason given above.
  const benefitRows = useWatch({ control, name: 'benefitSchedule' });

  /*
    Held between the submit and the second, deliberate click. Publishing retires the version
    that is active now and repoints every subsequent policy at these rates -- a compliance-grade
    act with no retraction -- and it was the one such act on the platform reached in a single
    click, while claim settlement, EFT execution and reinstatement all confirm.
  */
  const [pending, setPending] = useState<PublishVersionFormValues | null>(null);

  async function onSubmit(values: PublishVersionFormValues) {
    await publishVersion(productId, toApiRequest(values, category));
    if (useProductStore.getState().publishing[productId]?.status === 'success') {
      onPublished();
    }
  }

  return (
    <form className="space-y-4" onSubmit={(e) => void handleSubmit((v) => setPending(v))(e)}>
      <div className="grid grid-cols-2 gap-3">
        <FormField label="IFRS measurement model">
          <Select
            {...register('ifrsMeasurementModel')}
          >
            {IFRS_MEASUREMENT_MODELS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="Effective date" error={errors.effectiveDate?.message}>
          <Controller
            control={control}
            name="effectiveDate"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
              />
            )}
          />
        </FormField>
      </div>

      <FormField label="Retirement date (optional)" error={errors.retirementDate?.message}>
        <Controller
          control={control}
          name="retirementDate"
          render={({ field }) => (
            <DatePicker
              value={field.value || null}
              onChange={(iso) => field.onChange(iso ?? '')}
            />
          )}
        />
      </FormField>

      {/* Eligibility. Every bound optional -- an unbounded dimension is a real product
          design. The caption names which bounds refuse business and which only flag it,
          because that is the difference between a bound set casually and one thought
          about. */}
      <div className="rounded-md border border-border p-3">
        <p className="text-xs font-medium text-muted-foreground">Eligibility (optional)</p>
        <p className="mt-0.5 mb-2.5 text-xs text-subtle-foreground">
          Age and term are refused at issue — an age outside the rate table cannot be
          priced at all. A sum assured outside its bounds is flagged, not blocked, and the
          reason is recorded: above retention is what reinsurance is for.
        </p>

        <div className="grid grid-cols-2 gap-3">
          <FormField label="Minimum entry age" error={errors.minEntryAge?.message}>
            <Input
              inputMode="numeric"
              inputSize="sm"
              placeholder="18"
              {...register('minEntryAge')}
            />
          </FormField>
          <FormField label="Maximum entry age" error={errors.maxEntryAge?.message}>
            <Input
              inputMode="numeric"
              inputSize="sm"
              placeholder="65"
              {...register('maxEntryAge')}
            />
          </FormField>

          <FormField label="Minimum term (months)" error={errors.minTermMonths?.message}>
            <Input
              inputMode="numeric"
              inputSize="sm"
              placeholder="60"
              {...register('minTermMonths')}
            />
          </FormField>
          <FormField label="Maximum term (months)" error={errors.maxTermMonths?.message}>
            <Input
              inputMode="numeric"
              inputSize="sm"
              placeholder="360"
              {...register('maxTermMonths')}
            />
          </FormField>

          <FormField label="Minimum sum assured" error={errors.minSumAssured?.message}>
            <Input
              inputSize="sm"
              placeholder="500000.00"
              {...register('minSumAssured')}
            />
          </FormField>
          <FormField label="Maximum sum assured" error={errors.maxSumAssured?.message}>
            <Input
              inputSize="sm"
              placeholder="300000000.00"
              {...register('maxSumAssured')}
            />
          </FormField>

          <FormField label="Monthly loading %" error={errors.monthlyLoadingPercent?.message}>
            <Input
              inputMode="decimal"
              inputSize="sm"
              placeholder="8"
              {...register('monthlyLoadingPercent')}
            />
          </FormField>
          <FormField label="TIRA filing reference" error={errors.tiraReference?.message}>
            <Input inputSize="sm" placeholder="TIRA/LIFE/2026/0001" {...register('tiraReference')} />
          </FormField>
          <FormField label="TIRA approval date" error={errors.tiraApprovalDate?.message}>
            {/*
              DatePicker, not a raw type="date" input: every other date on this form uses it, it
              holds ISO while displaying dd/mm/yyyy, and a native date input would be the only
              control on the screen that takes a different format.
            */}
            <Controller
              control={control}
              name="tiraApprovalDate"
              render={({ field }) => (
                <DatePicker
                  value={field.value || null}
                  onChange={(iso) => field.onChange(iso ?? '')}
                />
              )}
            />
          </FormField>
          <FormField label="Quarterly loading %" error={errors.quarterlyLoadingPercent?.message}>
            <Input
              inputMode="decimal"
              inputSize="sm"
              placeholder="3"
              {...register('quarterlyLoadingPercent')}
            />
          </FormField>
        </div>
      </div>

      {/* A FUNERAL version is priced by its premium table alone (plan R1): no rating table, no base rates. */}
      {category !== 'FUNERAL' && category !== 'UNIT_LINKED' && (
      <div className="rounded-md border border-border p-3">
        {/*
          Which factors are required flips with the base rate panel below, so this line
          cannot state one rule. Unpriced, age is rated by multiplier and AGE is required;
          priced, the rate table keys on age itself and an AGE multiplier is refused -- so
          the old unconditional wording named the one factor that would now be rejected.
        */}
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          {priced
            ? 'Rating table -- must cover at least SUM_ASSURED_BAND'
            : 'Rating table -- must cover at least AGE and SUM_ASSURED_BAND'}
        </p>
        {priced && (
          <p className="-mt-1 mb-2 text-xs text-subtle-foreground">
            Age and smoker status are keys of the base rate table, so they are not rated by
            multiplier here.
          </p>
        )}
        {/*
          The row WRAPS rather than scrolling or crushing.

          Band was originally the only `flex: 1 1 0%` item, so it absorbed the whole space
          deficit as the panel narrowed -- measured at 296px of band at a 1440 viewport and
          18px at 1100, or at any width once a reader zooms. Giving every control a floor
          fixed the crushing but bought a horizontal scrollbar inside a form row, which is
          worse: a control you have to scroll to reach is a control you do not know is there.

          The header is behind a CONTAINER query, not a viewport one, because what matters is
          how wide this panel is -- `DetailLayout` takes 320px of it for the record rail at
          `lg` and gives it back below that, so panel width and window width are different
          questions. It shows only while the row is genuinely one line; once the row wraps, a
          column header above it would be labelling the wrong things.
        */}
        <div className="@container">
          <div className="mb-1 hidden items-center gap-2 px-1 text-xs text-subtle-foreground @min-[37.5rem]:flex">
            <span className="w-44 shrink-0">Factor</span>
            <span className="min-w-32 flex-1">Band</span>
            {/* Wide enough for an amount, not just an age: the same two columns now carry a
                sum assured band's bounds, and a seven-figure amount does not fit in 4rem. */}
            <span className="w-24 shrink-0 text-right">From</span>
            <span className="w-24 shrink-0 text-right">To</span>
            <span className="w-24 shrink-0 text-right">Multiplier</span>
            {/* Matches the remove button's 32px footprint, so the columns stay aligned. */}
            <span className="w-8 shrink-0" aria-hidden />
          </div>

          <div className="space-y-2">
          {ratingTable.fields.map((field, index) => {
            const rowType = ratingRows?.[index]?.factorType;
            const rowErrors = errors.ratingTable?.[index];
            // The refinements put their messages on the row's own fields. Nothing rendered
            // them before, so a blank age bound refused the submit in silence -- the form
            // simply did not respond, with no field marked and no reason given anywhere.
            // A factor the rate table already keys on has to go, and saying so takes
            // precedence over every field-level complaint about it: those tell the user to
            // FILL IN the ages ("an AGE factor needs a from and to age") when the only
            // correct action is to delete the row. It is computed here rather than read
            // from `errors` because zod stops before the object-level refinement that
            // raises it as soon as any field on the row has failed -- which, on exactly
            // this row, is always.
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
                {/* Wraps onto a second line when the panel is narrow. Nothing is allowed to
                    crush and nothing scrolls: the floors below (`min-w-32` on band,
                    `shrink-0` on the numerics) mean the row runs out of space rather than
                    squeezing, and `flex-wrap` is what it does when it runs out. Below its
                    600px content width -- where it does wrap -- the row is bordered, so
                    the multiplier and the remove button reading as a second, half-empty
                    factor is instead one visible block, exactly as in the base rate panel
                    below. Above it the border goes and the shared header takes over. */}
                <div className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 @min-[37.5rem]:rounded-none @min-[37.5rem]:border-0 @min-[37.5rem]:p-0">
                  <Select
                    inputSize="sm"
                    className="w-44 shrink-0"
                    aria-label={`Rating factor ${index + 1} type`}
                    aria-invalid={doubleCounted ? true : undefined}
                    {...register(`ratingTable.${index}.factorType`)}
                  >
                    {RATING_FACTOR_TYPES.map((t) => (
                      // Humanised, like every other enum on this console. These read as
                      // raw SUM_ASSURED_BAND wire literals before.
                      <option key={t} value={t}>
                        {humanizeStatus(t)}
                      </option>
                    ))}
                  </Select>
                  <Input
                    inputSize="sm" className="min-w-32 flex-1"
                    placeholder={BAND_PLACEHOLDER[rowType ?? 'AGE']}
                    aria-label={`Rating factor ${index + 1} band`}
                    aria-invalid={!doubleCounted && rowErrors?.band ? true : undefined}
                    {...register(`ratingTable.${index}.band`)}
                  />
                  {/* AGE and SUM_ASSURED_BAND are both rated by RANGE, not by matching the band
                      text. The band stays as the label an actuary reads on the product screen;
                      these two are what the platform actually resolves against, so they show
                      only where they mean something and the backend refuses them anywhere else.
                      The columns are held open on other rows so the grid does not shift as the
                      type changes.

                      Sum assured shares the two columns rather than adding its own pair: a row
                      is one factor type at a time, and four bound inputs -- two of them always
                      inert -- would put the grid's width into a set of fields that can never
                      both apply. The header says From/To for the same reason it did before. */}
                  {rowType === 'AGE' ? (
                    <>
                      <Input
                        type="number"
                        min={0}
                        inputSize="sm" className="w-24 shrink-0 text-right"
                        placeholder="from"
                        aria-label={`Rating factor ${index + 1} from age`}
                        aria-invalid={!doubleCounted && rowErrors?.ageFrom ? true : undefined}
                        {...register(`ratingTable.${index}.ageFrom`)}
                      />
                      <Input
                        type="number"
                        min={0}
                        inputSize="sm" className="w-24 shrink-0 text-right"
                        placeholder="to"
                        aria-label={`Rating factor ${index + 1} to age`}
                        aria-invalid={!doubleCounted && rowErrors?.ageTo ? true : undefined}
                        {...register(`ratingTable.${index}.ageTo`)}
                      />
                    </>
                  ) : rowType === 'SUM_ASSURED_BAND' ? (
                    <>
                      <Input
                        type="number"
                        min={0}
                        inputSize="sm" className="w-24 shrink-0 text-right"
                        placeholder="from"
                        aria-label={`Rating factor ${index + 1} from sum assured`}
                        aria-invalid={!doubleCounted && rowErrors?.sumAssuredFrom ? true : undefined}
                        {...register(`ratingTable.${index}.sumAssuredFrom`)}
                      />
                      <Input
                        type="number"
                        min={0}
                        inputSize="sm" className="w-24 shrink-0 text-right"
                        placeholder="to"
                        aria-label={`Rating factor ${index + 1} to sum assured`}
                        aria-invalid={!doubleCounted && rowErrors?.sumAssuredTo ? true : undefined}
                        {...register(`ratingTable.${index}.sumAssuredTo`)}
                      />
                    </>
                  ) : (
                    /* ...and only while there is a grid to hold open. Once the row wraps,
                       these are two stray dashes, one ending the first line and one
                       starting the second. */
                    <>
                      <span className="hidden w-24 shrink-0 text-right text-xs text-subtle-foreground @min-[37.5rem]:inline">
                        —
                      </span>
                      <span className="hidden w-24 shrink-0 text-right text-xs text-subtle-foreground @min-[37.5rem]:inline">
                        —
                      </span>
                    </>
                  )}
                  <Input
                    type="number"
                    min={0}
                    step="0.01"
                    inputSize="sm" className="w-24 shrink-0 text-right"
                    placeholder="1.0"
                    aria-label={`Rating factor ${index + 1} multiplier`}
                    aria-invalid={!doubleCounted && rowErrors?.multiplier ? true : undefined}
                    {...register(`ratingTable.${index}.multiplier`)}
                  />
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    className="shrink-0"
                    aria-label="Remove rating factor"
                    onClick={() => ratingTable.remove(index)}
                  >
                    <X />
                  </Button>
                </div>

                {rowMessage && (
                  <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">
                    {rowMessage}
                  </p>
                )}
                {/* An AGE row needs no band typed -- it is derived from the bounds on submit
                    so the label and the range cannot disagree. Said once, on the row that
                    would otherwise look unfinished. */}
                {!rowMessage && rowType === 'AGE' && !ratingRows?.[index]?.band && (
                  <p className="mt-1 px-1 text-xs text-subtle-foreground">
                    Band is optional for age — it is labelled from the range.
                  </p>
                )}
              </div>
            );
          })}
          </div>
        </div>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="-ml-2 mt-2"
          onClick={() => ratingTable.append(blankRatingFactorRow())}
        >
          <Plus />
          Add rating factor
        </Button>
        {errors.ratingTable?.root?.message && (
          <p className="mt-1 text-xs text-status-danger-fg">{errors.ratingTable.root.message}</p>
        )}
      </div>
      )}

      {/*
        The base rate table -- the thing that makes a version quotable at all.

        This form sent no `baseRates` whatsoever before, so every product published
        through this console was permanently unquotable: `POST /products/{id}/quote-premium`
        refuses a version with no rate table, and there is NO endpoint to add rates
        after publishing, so the only remedy was publishing another version that
        also could not be priced. The product screen said so honestly -- "this
        version is unpriced ... a rate table has to be supplied at publish time" --
        describing a dead end.
      */}
      {category !== 'FUNERAL' && category !== 'UNIT_LINKED' && (
      <div className="rounded-md border border-border p-3">
        <p className="text-xs font-medium text-muted-foreground">Base rates (optional)</p>
        <p className="mt-0.5 mb-2.5 text-xs text-subtle-foreground">
          The annual rate per 1,000 of sum assured, by age band, sex and smoker status, and
          optionally by policy term in months (blank term = every term). A
          version published without them is valid and sellable but{' '}
          <strong className="font-medium text-foreground">can never be quoted</strong>, and
          rates cannot be added afterwards. Leave a rate blank to not price that
          combination.
        </p>

        {/*
          54rem is this row's measured content width: 64 + 64 + 112 + 2x96 (term) + 3x112 + 32
          with eight 8px gaps = 864px. The header appears only at or above it, because a header
          over a row that has already wrapped labels the wrong boxes.
        */}
        <div className="@container">
          <div className="mb-1 hidden items-center gap-2 px-1 text-xs text-subtle-foreground @min-[54rem]:flex">
            <span className="w-16 shrink-0 text-right">From</span>
            <span className="w-16 shrink-0 text-right">To</span>
            <span className="w-28 shrink-0">Sex</span>
            <span className="w-24 shrink-0 text-right">Term from</span>
            <span className="w-24 shrink-0 text-right">Term to</span>
            {BASE_RATE_COLUMNS.map((c) => (
              <span key={c.key} className="w-28 shrink-0 text-right">
                {c.label}
              </span>
            ))}
            <span className="w-8 shrink-0" aria-hidden />
          </div>

          <div className="space-y-2">
            {baseRates.fields.map((field, index) => {
              const rowErrors = errors.baseRates?.[index];
              const rowMessage =
                rowErrors?.ageFrom?.message ??
                rowErrors?.ageTo?.message ??
                rowErrors?.termFromMonths?.message ??
                rowErrors?.termToMonths?.message ??
                BASE_RATE_COLUMNS.map((c) => rowErrors?.[c.key]?.message).find(Boolean);

              return (
                <div key={field.id}>
                  {/*
                    Seven controls need 656px and the wizard's form column is 468px, so
                    this row wraps -- measured, not assumed. Wrapping is the right
                    behaviour (a scrollbar inside a form row is worse), but the wrapped
                    remainder read as a separate, broken row, so below the breakpoint each
                    band gets a border and becomes one visible block. Above it the border
                    goes and the rows sit under the shared header instead.
                  */}
                  <div className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 @min-[54rem]:rounded-none @min-[54rem]:border-0 @min-[54rem]:p-0">
                    <Input
                      type="number"
                      min={0}
                      inputSize="sm" className="w-16 shrink-0 text-right"
                      placeholder="from"
                      aria-label={`Base rate ${index + 1} from age`}
                      aria-invalid={rowErrors?.ageFrom ? true : undefined}
                      {...register(`baseRates.${index}.ageFrom`)}
                    />
                    <Input
                      type="number"
                      min={0}
                      inputSize="sm" className="w-16 shrink-0 text-right"
                      placeholder="to"
                      aria-label={`Base rate ${index + 1} to age`}
                      aria-invalid={rowErrors?.ageTo ? true : undefined}
                      {...register(`baseRates.${index}.ageTo`)}
                    />
                    <Select
                      inputSize="sm"
                      className="w-28 shrink-0"
                      aria-label={`Base rate ${index + 1} sex`}
                      {...register(`baseRates.${index}.sex`)}
                    >
                      <option value="FEMALE">Female</option>
                      <option value="MALE">Male</option>
                    </Select>
                    {/* Optional term band in months: blank on both means the rate applies to
                        every term. */}
                    <Input
                      type="number"
                      min={1}
                      inputSize="sm" className="w-24 shrink-0 text-right"
                      placeholder="Term from"
                      aria-label={`Base rate ${index + 1} term from months`}
                      aria-invalid={rowErrors?.termFromMonths ? true : undefined}
                      {...register(`baseRates.${index}.termFromMonths`)}
                    />
                    <Input
                      type="number"
                      min={1}
                      inputSize="sm" className="w-24 shrink-0 text-right"
                      placeholder="Term to"
                      aria-label={`Base rate ${index + 1} term to months`}
                      aria-invalid={rowErrors?.termToMonths ? true : undefined}
                      {...register(`baseRates.${index}.termToMonths`)}
                    />
                    {BASE_RATE_COLUMNS.map((c) => (
                      <Input
                        key={c.key}
                        type="number"
                        min={0}
                        step="0.0001"
                        // w-28, not w-24: the placeholder below is the only label this box
                        // has once the row wraps, and "Non-smoker" was being cut mid-word
                        // at 96px.
                        inputSize="sm" className="w-28 shrink-0 text-right"
                        // The column header is hidden below the breakpoint, where this row
                        // wraps -- and three boxes all placeheld "—" on a second line name
                        // nothing at all. Each box says which rate it is instead, which
                        // repeats the header when there is one and carries the row when
                        // there is not. It goes away the moment a rate is typed.
                        placeholder={c.label}
                        aria-label={`Base rate ${index + 1} ${c.label.toLowerCase()} rate`}
                        aria-invalid={rowErrors?.[c.key] ? true : undefined}
                        {...register(`baseRates.${index}.${c.key}`)}
                      />
                    ))}
                    <Button
                      type="button"
                      size="icon"
                      variant="ghost"
                      className="shrink-0"
                      aria-label="Remove base rate band"
                      onClick={() => baseRates.remove(index)}
                    >
                      <X />
                    </Button>
                  </div>

                  {rowMessage && (
                    <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">
                      {rowMessage}
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="-ml-2 mt-2"
          // Both sexes at once: a rate table is written per age band, and adding one
          // row per sex invites a table priced for women and not men -- which the
          // exact quote lookup turns into a 422 for every male applicant rather than
          // anything visible here.
          onClick={() => blankBaseRateBand().forEach((row) => baseRates.append(row))}
        >
          <Plus />
          Add age band
        </Button>
      </div>
      )}

      <div className="rounded-md border border-border p-3">
        {/* Not optional any more, and the label has to say so before the submit does: a
            version that covers nothing is refused, and what is authored here is what a
            claim is later valued at. */}
        <p className="mb-2 text-xs font-medium text-muted-foreground">Benefit schedule</p>
        {errors.benefitSchedule?.root?.message && (
          <p role="alert" className="mb-2 text-xs text-status-danger-fg">
            {errors.benefitSchedule.root.message}
          </p>
        )}
        <div className="space-y-2">
          {benefitSchedule.fields.map((field, index) => {
            const method = benefitRows?.[index]?.calculationMethod;
            const rowErrors = errors.benefitSchedule?.[index];
            const rowMessage =
              rowErrors?.calculationMethod?.message ??
              rowErrors?.percent?.message ??
              rowErrors?.flatAmount?.message;

            return (
              <div key={field.id}>
                <div className="flex flex-wrap items-center gap-2">
                  <Select
                    inputSize="sm"
                    className="w-40 shrink-0"
                    aria-label={`Benefit ${index + 1} type`}
                    {...register(`benefitSchedule.${index}.benefitType`)}
                  >
                    {BENEFIT_TYPES.map((t) => (
                      // Humanised, like every other enum on this console.
                      <option key={t} value={t}>
                        {humanizeStatus(t)}
                      </option>
                    ))}
                  </Select>
                  <Select
                    inputSize="sm"
                    className="w-44 shrink-0"
                    aria-label={`Benefit ${index + 1} calculation method`}
                    aria-invalid={rowErrors?.calculationMethod ? true : undefined}
                    {...register(`benefitSchedule.${index}.calculationMethod`)}
                  >
                    {BENEFIT_CALCULATION_METHODS.map((m) => (
                      <option key={m} value={m}>
                        {BENEFIT_CALCULATION_METHOD_LABELS[m]}
                      </option>
                    ))}
                  </Select>
                  {/* One amount input, showing only for the method that uses it. The shape
                      rule refuses an amount the method does not use, so an always-present
                      pair of inputs would be a field that can only ever be wrong. A full
                      sum assured benefit needs no amount at all and says so. */}
                  {method === 'PERCENTAGE_OF_SUM_ASSURED' ? (
                    <Input
                      type="number"
                      min={0}
                      step="0.01"
                      inputSize="sm" className="w-28 shrink-0 text-right"
                      placeholder="%"
                      aria-label={`Benefit ${index + 1} percentage`}
                      aria-invalid={rowErrors?.percent ? true : undefined}
                      {...register(`benefitSchedule.${index}.percent`)}
                    />
                  ) : method === 'FLAT_AMOUNT' ? (
                    <Input
                      type="number"
                      min={0}
                      step="0.01"
                      inputSize="sm" className="w-28 shrink-0 text-right"
                      placeholder="amount"
                      aria-label={`Benefit ${index + 1} flat amount`}
                      aria-invalid={rowErrors?.flatAmount ? true : undefined}
                      {...register(`benefitSchedule.${index}.flatAmount`)}
                    />
                  ) : (
                    <span className="w-28 shrink-0 text-right text-xs text-subtle-foreground">
                      —
                    </span>
                  )}
                  <Button
                    type="button"
                    size="icon"
                    variant="ghost"
                    className="shrink-0"
                    aria-label="Remove benefit"
                    onClick={() => benefitSchedule.remove(index)}
                  >
                    <X />
                  </Button>
                </div>
                {rowMessage && (
                  <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">
                    {rowMessage}
                  </p>
                )}
              </div>
            );
          })}
        </div>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="-ml-2 mt-2"
          onClick={() => benefitSchedule.append(blankBenefitRow())}
        >
          <Plus />
          Add benefit
        </Button>
      </div>

      {/*
        The cash-value (surrender value) table -- what a savings policy is worth as it runs, the
        figure surrender, paid-up and policy loans all read. Only on the savings categories: pure
        protection has no cash value and the server refuses a table there. Cannot be added after
        publishing, the same as base rates.
      */}
      {/*
        How the policy's value is defined (product step 3). SCALE is step 1's cash-value table, below.
        ACCOUNT is a savings account: contributions in, charges out, interest credited at the higher
        of a declared rate and this version's guarantee. Only on the three savings categories.
      */}
      {(ACCOUNT_CATEGORIES.includes(category) || deferredAnnuity) && (
        <div className="rounded-md border border-border p-3">
          <p className="text-xs font-medium text-muted-foreground">
            {deferredAnnuity ? 'The account it saves in before it vests' : "How the policy's value is defined"}
          </p>
          {/* A deferred annuity (D2) is always a savings account: the annuity kind chose it. */}
          {!deferredAnnuity && (
            <div className="mt-2 grid grid-cols-2 gap-3">
              <FormField label="Value basis" error={errors.valueBasis?.message}>
                <Select inputSize="sm" {...register('valueBasis')}>
                  <option value="SCALE">Cash-value table (scale)</option>
                  <option value="ACCOUNT">Savings account</option>
                  <option value="DEPOSIT">Fixed-term deposit</option>
                </Select>
              </FormField>
            </div>
          )}
          {valueBasis === 'ACCOUNT' && (
            <div className="mt-3 space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <FormField label="Guaranteed interest rate (% a year)" error={errors.guaranteedRatePercent?.message}>
                  <Input inputSize="sm" inputMode="decimal" placeholder="3" {...register('guaranteedRatePercent')} />
                </FormField>
                <FormField label="Minimum balance after a withdrawal" error={errors.minimumBalance?.message}>
                  <Input inputSize="sm" inputMode="decimal" placeholder="50000" {...register('minimumBalance')} />
                </FormField>
              </div>
              <p className="text-xs text-subtle-foreground">
                Charges by policy year. Start at year 1 and leave the last row&apos;s end blank, so every year has a charge.
              </p>
              {accountCharges.fields.map((field, index) => {
                const rowErrors = errors.accountCharges?.[index];
                const rowMessage =
                  rowErrors?.contributionAllocationPercent?.message ??
                  rowErrors?.monthlyPolicyFee?.message ??
                  rowErrors?.toPolicyYear?.message;
                return (
                  <div key={field.id}>
                    <div className="flex flex-wrap items-center gap-2">
                      <Input
                        type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="From"
                        aria-label={`Charge row ${index + 1} from policy year`}
                        {...register(`accountCharges.${index}.fromPolicyYear`)}
                      />
                      <Input
                        type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right" placeholder="To"
                        aria-label={`Charge row ${index + 1} to policy year`}
                        {...register(`accountCharges.${index}.toPolicyYear`)}
                      />
                      <Input
                        inputSize="sm" inputMode="decimal" className="w-24 shrink-0 text-right" placeholder="Premium %"
                        aria-label={`Charge row ${index + 1} allocation charge on premiums`}
                        {...register(`accountCharges.${index}.contributionAllocationPercent`)}
                      />
                      <Input
                        inputSize="sm" inputMode="decimal" className="w-24 shrink-0 text-right" placeholder="Transfer %"
                        aria-label={`Charge row ${index + 1} allocation charge on transfers in`}
                        {...register(`accountCharges.${index}.transferAllocationPercent`)}
                      />
                      <Input
                        inputSize="sm" inputMode="decimal" className="w-28 shrink-0 text-right" placeholder="Monthly fee"
                        aria-label={`Charge row ${index + 1} monthly policy fee`}
                        {...register(`accountCharges.${index}.monthlyPolicyFee`)}
                      />
                      <Button
                        type="button" size="icon" variant="ghost"
                        aria-label={`Remove charge row ${index + 1}`}
                        onClick={() => accountCharges.remove(index)}
                      >
                        <X className="size-4" />
                      </Button>
                    </div>
                    {rowMessage && <p role="alert" className="mt-1 text-xs text-status-danger-fg">{rowMessage}</p>}
                  </div>
                );
              })}
              {errors.accountCharges?.root?.message && (
                <p role="alert" className="text-xs text-status-danger-fg">{errors.accountCharges.root.message}</p>
              )}
              {errors.accountCharges?.message && (
                <p role="alert" className="text-xs text-status-danger-fg">{errors.accountCharges.message}</p>
              )}
              <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => accountCharges.append(blankAccountChargeRow())}>
                <Plus className="size-4" /> Add a charge row
              </Button>
            </div>
          )}
          {valueBasis === 'DEPOSIT' && (
            <div className="mt-3 space-y-3">
              <p className="text-xs text-subtle-foreground">
                One deposit for a term the client chooses. Each rate is for the whole term, not a year. A band runs from its
                start up to the next band&apos;s start. Nothing can be added or taken out until maturity, and the version
                carries no payout schedule.
              </p>
              {depositTerms.fields.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="text-sm" aria-label="Deposit rates">
                    <thead>
                      <tr>
                        <th className="px-1 py-1 text-left text-xs font-medium text-muted-foreground">Deposits from</th>
                        {depositTerms.fields.map((field, j) => (
                          <th key={field.id} className="px-1 py-1">
                            <div className="flex items-center gap-1">
                              <Input
                                type="number" min={1} inputSize="sm" className="w-20 text-right" placeholder="Months"
                                aria-label={`Term ${j + 1} in months`}
                                {...register(`depositTerms.${j}.months`)}
                              />
                              <Button
                                type="button" size="icon" variant="ghost" aria-label={`Remove term ${j + 1}`}
                                onClick={() => removeDepositTerm(j)}
                              >
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
                            <Input
                              inputSize="sm" inputMode="decimal" className="w-32 text-right" placeholder="500000"
                              aria-label={`Band ${i + 1} starts at`}
                              {...register(`depositBands.${i}.minAmount`)}
                            />
                          </td>
                          {depositTerms.fields.map((term, j) => (
                            <td key={term.id} className="px-1 py-1">
                              <Input
                                inputSize="sm" inputMode="decimal" className="w-20 text-right" placeholder="%"
                                aria-label={`Band ${i + 1} rate for term ${j + 1}`}
                                {...register(`depositBands.${i}.rates.${j}`)}
                              />
                            </td>
                          ))}
                          <td className="px-1 py-1">
                            <Button
                              type="button" size="icon" variant="ghost" aria-label={`Remove band ${i + 1}`}
                              onClick={() => depositBands.remove(i)}
                            >
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
                <Button
                  type="button" size="sm" variant="ghost"
                  onClick={() => depositBands.append(blankDepositBand(depositTerms.fields.length))}
                >
                  <Plus className="size-4" /> Add a band
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      {/*
        With-profits (product step 4): bonuses declared on the product attach to every eligible
        policy. Every field renders its own error, including the surrender basis -- an empty select
        with no default, because what bonuses add to a surrender is a contract term the version
        must state.
      */}
      {/* Product step 5: required on an ANNUITY product, and offered on no other. */}
      {category === 'ANNUITY' && (
        <AnnuityTermsSection register={register} control={control} errors={errors} setValue={setValue} />
      )}
      {/* Family funeral cover: required on a FUNERAL product, and offered on no other. */}
      {category === 'FUNERAL' && <FuneralTermsSection register={register} control={control} errors={errors} />}
      {/* Product step 6: required on a UNIT_LINKED product, and offered on no other. */}
      {category === 'UNIT_LINKED' && (
        <UnitLinkedTermsSection register={register} control={control} errors={errors} currency={undefined} />
      )}

      {WITH_PROFITS_CATEGORIES.includes(category) && valueBasis === 'SCALE' && (
        <div className="rounded-md border border-border p-3">
          <p className="text-xs font-medium text-muted-foreground">With profits (optional)</p>
          <div className="mt-2">
            <CheckboxField label="With-profits version" {...register('withProfits')} />
            {errors.withProfits?.message && (
              <p role="alert" className="mt-1 text-xs text-status-danger-fg">{errors.withProfits.message}</p>
            )}
          </div>
          {withProfits && (
            <div className="mt-3 space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <FormField label="Bonus method" error={errors.bonusMethod?.message}>
                  <Select inputSize="sm" {...register('bonusMethod')}>
                    <option value="">Choose…</option>
                    <option value="SIMPLE">Simple (on the sum assured)</option>
                    <option value="COMPOUND">Compound (on the sum assured plus attached bonuses)</option>
                  </Select>
                </FormField>
                <FormField label="Bonus surrender basis" error={errors.bonusSurrenderBasis?.message}>
                  <Select inputSize="sm" {...register('bonusSurrenderBasis')}>
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
                  <p className="text-xs text-subtle-foreground">
                    What each 1,000 of attached bonus is worth on surrender, from a number of completed years.
                  </p>
                  {bonusSurrenderRows.fields.map((field, index) => {
                    const rowErrors = errors.bonusSurrenderRows?.[index];
                    const rowMessage = rowErrors?.fromCompletedYears?.message ?? rowErrors?.perMille?.message;
                    return (
                      <div key={field.id}>
                        <div className="flex flex-wrap items-center gap-2">
                          <Input
                            type="number" min={0} inputSize="sm" className="w-28 shrink-0 text-right" placeholder="From year"
                            aria-label={`Bonus surrender row ${index + 1} from completed years`}
                            {...register(`bonusSurrenderRows.${index}.fromCompletedYears`)}
                          />
                          <Input
                            inputSize="sm" inputMode="decimal" className="w-28 shrink-0 text-right" placeholder="Per mille"
                            aria-label={`Bonus surrender row ${index + 1} value per mille`}
                            {...register(`bonusSurrenderRows.${index}.perMille`)}
                          />
                          <Button
                            type="button" size="icon" variant="ghost"
                            aria-label={`Remove bonus surrender row ${index + 1}`}
                            onClick={() => bonusSurrenderRows.remove(index)}
                          >
                            <X className="size-4" />
                          </Button>
                        </div>
                        {rowMessage && <p role="alert" className="mt-1 text-xs text-status-danger-fg">{rowMessage}</p>}
                      </div>
                    );
                  })}
                  <Button
                    type="button" size="sm" variant="ghost" className="-ml-2"
                    onClick={() => bonusSurrenderRows.append(blankBonusSurrenderRow())}
                  >
                    <Plus className="size-4" /> Add a bonus surrender row
                  </Button>
                </div>
              )}
              {/* Rendered whatever the basis: "only for OWN_SCALE" lands here when rows were left
                  behind by switching away from it. */}
              {errors.bonusSurrenderRows?.message && (
                <p role="alert" className="text-xs text-status-danger-fg">{errors.bonusSurrenderRows.message}</p>
              )}
              {errors.bonusSurrenderRows?.root?.message && (
                <p role="alert" className="text-xs text-status-danger-fg">{errors.bonusSurrenderRows.root.message}</p>
              )}
            </div>
          )}
        </div>
      )}

      {CASH_VALUE_CATEGORIES.includes(category) && valueBasis === 'SCALE' && (
        <div className="rounded-md border border-border p-3">
          <p className="text-xs font-medium text-muted-foreground">Cash value (optional)</p>
          <p className="mt-0.5 mb-2.5 text-xs text-subtle-foreground">
            The surrender value per 1,000 of sum assured at each policy year, from the table the
            actuary supplies, optionally by entry-age band. Leave it empty for a version with no
            cash value. A table needs the actuarial sign-off it was issued under.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Actuarial basis reference" error={errors.cashValueBasisReference?.message}>
              <Input inputSize="sm" placeholder="ACT/2026/ENDOW-01" {...register('cashValueBasisReference')} />
            </FormField>
            <FormField label="Basis date" error={errors.cashValueBasisDate?.message}>
              <Controller
                control={control}
                name="cashValueBasisDate"
                render={({ field }) => (
                  <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />
                )}
              />
            </FormField>
            <FormField label="Paid-up basis" error={errors.cashValuePaidUpBasis?.message}>
              <Select inputSize="sm" {...register('cashValuePaidUpBasis')}>
                <option value="">Choose…</option>
                <option value="PROPORTIONATE">Proportionate</option>
                <option value="TABLE">From the table</option>
              </Select>
            </FormField>
            <FormField label="Years before any value" error={errors.cashValueMinYears?.message}>
              <Select inputSize="sm" {...register('cashValueMinYears')}>
                <option value="">Choose…</option>
                <option value="2">2</option>
                <option value="3">3</option>
              </Select>
            </FormField>
          </div>

          <div className="mt-3 space-y-2">
            {cashValueRows.fields.map((field, index) => {
              const rowErrors = errors.cashValueRows?.[index];
              const rowMessage =
                rowErrors?.policyYear?.message ??
                rowErrors?.ageFrom?.message ??
                rowErrors?.ageTo?.message ??
                rowErrors?.cashValuePerMille?.message ??
                rowErrors?.paidUpPerMille?.message;
              return (
                <div key={field.id}>
                  <div className="flex flex-wrap items-center gap-2">
                    <Input
                      type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right"
                      placeholder="Year"
                      aria-label={`Cash value ${index + 1} policy year`}
                      aria-invalid={rowErrors?.policyYear ? true : undefined}
                      {...register(`cashValueRows.${index}.policyYear`)}
                    />
                    <Input
                      type="number" min={0} inputSize="sm" className="w-20 shrink-0 text-right"
                      placeholder="Age from"
                      aria-label={`Cash value ${index + 1} entry age from`}
                      aria-invalid={rowErrors?.ageFrom ? true : undefined}
                      {...register(`cashValueRows.${index}.ageFrom`)}
                    />
                    <Input
                      type="number" min={0} inputSize="sm" className="w-20 shrink-0 text-right"
                      placeholder="Age to"
                      aria-label={`Cash value ${index + 1} entry age to`}
                      aria-invalid={rowErrors?.ageTo ? true : undefined}
                      {...register(`cashValueRows.${index}.ageTo`)}
                    />
                    <Input
                      type="number" min={0} step="0.0001" inputSize="sm" className="w-28 shrink-0 text-right"
                      placeholder="Value per 1,000"
                      aria-label={`Cash value ${index + 1} value per 1,000`}
                      aria-invalid={rowErrors?.cashValuePerMille ? true : undefined}
                      {...register(`cashValueRows.${index}.cashValuePerMille`)}
                    />
                    <Input
                      type="number" min={0} step="0.0001" inputSize="sm" className="w-28 shrink-0 text-right"
                      placeholder="Paid-up per 1,000"
                      aria-label={`Cash value ${index + 1} paid-up per 1,000`}
                      aria-invalid={rowErrors?.paidUpPerMille ? true : undefined}
                      {...register(`cashValueRows.${index}.paidUpPerMille`)}
                    />
                    <Button
                      type="button" size="icon" variant="ghost" className="shrink-0"
                      aria-label="Remove cash value row"
                      onClick={() => cashValueRows.remove(index)}
                    >
                      <X />
                    </Button>
                  </div>
                  {rowMessage && (
                    <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">{rowMessage}</p>
                  )}
                </div>
              );
            })}
          </div>
          {errors.cashValueRows?.root?.message && (
            <p role="alert" className="mt-1 text-xs text-status-danger-fg">{errors.cashValueRows.root.message}</p>
          )}
          <Button
            type="button" size="sm" variant="ghost" className="-ml-2 mt-2"
            onClick={() => cashValueRows.append(blankCashValueRow())}
          >
            <Plus />
            Add policy year
          </Button>
        </div>
      )}

      {/*
        What the contract pays while the life assured is ALIVE (product step 2), and the free-look
        window. Free-look is shown for every individual product -- it is required there, and a term
        policy has one even though it pays nothing before death. The schedule rows appear only
        where a schedule is legal, so a category that cannot carry one is not offered rows it
        would be refused for.
      */}
      {(FREE_LOOK_CATEGORIES.includes(category) || SCHEDULED_CATEGORIES.includes(category)) && (
        <div className="rounded-md border border-border p-3">
          <p className="text-xs font-medium text-muted-foreground">Payouts and free-look</p>
          <p className="mt-0.5 mb-2.5 text-xs text-subtle-foreground">
            {SCHEDULED_CATEGORIES.includes(category)
              ? 'What this contract pays while the life assured is alive. An endowment must say what it pays at the end of its term; survival benefits and an income stream are optional.'
              : 'How long after issue the customer may cancel and have their premiums back.'}
          </p>
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Free-look days" error={errors.freeLookDays?.message}>
              <Input
                type="number" min={1} max={365} inputSize="sm"
                placeholder="15"
                {...register('freeLookDays')}
              />
            </FormField>
            <FormField
              label="Death benefit at least this % of premiums"
              error={errors.deathBenefitPremiumPercent?.message}
            >
              <Input
                type="number" min={0} step="0.01" inputSize="sm"
                placeholder="Optional"
                {...register('deathBenefitPremiumPercent')}
              />
            </FormField>
          </div>

          {SCHEDULED_CATEGORIES.includes(category) && (
            <>
              <div className="mt-3 space-y-2">
                {payoutRows.fields.map((field, index) => {
                  const rowErrors = errors.payoutRows?.[index];
                  const rowMessage =
                    rowErrors?.kind?.message ??
                    rowErrors?.fromPolicyYear?.message ??
                    rowErrors?.toPolicyYear?.message ??
                    rowErrors?.amountBasis?.message ??
                    rowErrors?.amountValue?.message ??
                    rowErrors?.frequency?.message;
                  // A maturity pays once, on the policy's own maturity date -- the product does
                  // not fix the term, each policy does -- so years and frequency are not merely
                  // optional there, they are refused.
                  const endOfTerm = watchedPayoutKinds?.[index] === 'MATURITY';
                  return (
                    <div key={field.id}>
                      <div className="flex flex-wrap items-center gap-2">
                        <Select
                          inputSize="sm" className="w-40 shrink-0"
                          aria-label={`Payout ${index + 1} kind`}
                          aria-invalid={rowErrors?.kind ? true : undefined}
                          {...register(`payoutRows.${index}.kind`)}
                        >
                          <option value="">Choose…</option>
                          <option value="MATURITY">Maturity</option>
                          <option value="SURVIVAL">Survival benefit</option>
                          <option value="INCOME">Income</option>
                        </Select>
                        <Input
                          type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right"
                          placeholder="From year" disabled={endOfTerm}
                          aria-label={`Payout ${index + 1} from policy year`}
                          aria-invalid={rowErrors?.fromPolicyYear ? true : undefined}
                          {...register(`payoutRows.${index}.fromPolicyYear`)}
                        />
                        <Input
                          type="number" min={1} inputSize="sm" className="w-20 shrink-0 text-right"
                          placeholder="To year" disabled={endOfTerm}
                          aria-label={`Payout ${index + 1} to policy year`}
                          aria-invalid={rowErrors?.toPolicyYear ? true : undefined}
                          {...register(`payoutRows.${index}.toPolicyYear`)}
                        />
                        <Select
                          inputSize="sm" className="w-36 shrink-0"
                          aria-label={`Payout ${index + 1} basis`}
                          aria-invalid={rowErrors?.amountBasis ? true : undefined}
                          {...register(`payoutRows.${index}.amountBasis`)}
                        >
                          <option value="">Choose…</option>
                          <option value="PERCENT_OF_SA">% of sum assured</option>
                          <option value="FIXED">Fixed amount</option>
                          {/* Product step 3: an account version's maturity pays the account, at 100. */}
                          <option value="ACCOUNT_VALUE">The whole account value</option>
                        </Select>
                        <Input
                          type="number" min={0} step="0.01" inputSize="sm" className="w-24 shrink-0 text-right"
                          placeholder="Value"
                          aria-label={`Payout ${index + 1} amount`}
                          aria-invalid={rowErrors?.amountValue ? true : undefined}
                          {...register(`payoutRows.${index}.amountValue`)}
                        />
                        <Select
                          inputSize="sm" className="w-32 shrink-0" disabled={endOfTerm}
                          aria-label={`Payout ${index + 1} frequency`}
                          aria-invalid={rowErrors?.frequency ? true : undefined}
                          {...register(`payoutRows.${index}.frequency`)}
                        >
                          <option value="">Choose…</option>
                          <option value="ANNUAL">Yearly</option>
                          <option value="SEMI_ANNUAL">Twice a year</option>
                          <option value="QUARTERLY">Quarterly</option>
                          <option value="MONTHLY">Monthly</option>
                        </Select>
                        <Button
                          type="button" size="icon" variant="ghost" className="shrink-0"
                          aria-label="Remove payout row"
                          onClick={() => payoutRows.remove(index)}
                        >
                          <X />
                        </Button>
                      </div>
                      {rowMessage && (
                        <p role="alert" className="mt-1 px-1 text-xs text-status-danger-fg">{rowMessage}</p>
                      )}
                    </div>
                  );
                })}
              </div>
              {errors.payoutRows?.root?.message && (
                <p role="alert" className="mt-1 text-xs text-status-danger-fg">{errors.payoutRows.root.message}</p>
              )}
              {errors.payoutRows?.message && (
                <p role="alert" className="mt-1 text-xs text-status-danger-fg">{errors.payoutRows.message}</p>
              )}
              <Button
                type="button" size="sm" variant="ghost" className="-ml-2 mt-2"
                onClick={() => payoutRows.append(blankPayoutRow())}
              >
                <Plus />
                Add a payout
              </Button>

              <div className="mt-3 grid grid-cols-2 gap-3">
                {/* Each is required only once a row makes it necessary, so each appears then --
                    neither has a sensible default, which is why the server refuses to guess. */}
                {watchedPayoutKinds?.includes('SURVIVAL') && (
                  <FormField
                    label="Survival benefits paid come off the death benefit"
                    error={errors.survivalBenefitsDeductedFromDeath?.message}
                  >
                    <Select inputSize="sm" {...register('survivalBenefitsDeductedFromDeath')}>
                      <option value="">Choose…</option>
                      <option value="true">Yes, deduct them</option>
                      <option value="false">No, pay the full death benefit</option>
                    </Select>
                  </FormField>
                )}
                {watchedPayoutKinds?.includes('INCOME') && (
                  <FormField
                    label="Proof of life every (months)"
                    error={errors.proofOfLifeIntervalMonths?.message}
                  >
                    <Input
                      type="number" min={1} max={60} inputSize="sm"
                      placeholder="12"
                      {...register('proofOfLifeIntervalMonths')}
                    />
                  </FormField>
                )}
              </div>
            </>
          )}
        </div>
      )}

      {publishing.status === 'error' && publishing.error && (
        <InlineError error={publishing.error} />
      )}

      {pending ? (
        <ConfirmAct
          heading="Publish this version?"
          consequence={
            <>
              Make this the active version from <strong>{pending.effectiveDate}</strong>. Every
              policy priced from then on uses these rates, and the version that is active now is
              retired.
            </>
          }
          /*
            A fact about the backend, not caution: `ProductApi.publishVersion` retires every
            currently-ACTIVE version before inserting the new one, and there is no operation
            that retracts a published version.
          */
          reversal={
            <>
              Nothing here retracts it. Correcting a published price means publishing a further
              version, and any policy written in the meantime keeps the price it was sold at.
            </>
          }
          // Distinct from the trigger, for the reason given in DecisionPanel.
          confirmLabel="Publish and make active"
          busy={publishing.status === 'loading'}
          onConfirm={() => void onSubmit(pending)}
          onCancel={() => setPending(null)}
        />
      ) : (
        <Button type="submit" variant="primary" pending={publishing.status === 'loading'}>
          Publish version
        </Button>
      )}
    </form>
  );
}
