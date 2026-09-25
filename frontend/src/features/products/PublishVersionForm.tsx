import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect } from 'react';
import { useForm, useFieldArray, useWatch, Controller } from 'react-hook-form';
import {
  BENEFIT_CALCULATION_METHODS,
  BENEFIT_CALCULATION_METHOD_LABELS,
  BENEFIT_TYPES,
  IFRS_MEASUREMENT_MODELS,
  RATING_FACTOR_TYPES,
  type ProductCategory,
} from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { selectPublishing, useProductStore } from '@/store/productStore';
import {
  blankBenefitRow,
  blankFundRow,
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
import { InlineError } from '@/components/InlineError';
import { humanizeStatus } from '@/lib/status';

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
    formState: { errors },
  } = useForm<PublishVersionFormInput, unknown, PublishVersionFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      ifrsMeasurementModel: 'PAA',
      effectiveDate: '',
      retirementDate: '',
      ratingTable: [blankRatingFactorRow(), { ...blankRatingFactorRow(), factorType: 'SUM_ASSURED_BAND' }],
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
    },
  });

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
  const fundDefinitions = useFieldArray({ control, name: 'fundDefinitions' });

  async function onSubmit(values: PublishVersionFormValues) {
    await publishVersion(productId, toApiRequest(values));
    if (useProductStore.getState().publishing[productId]?.status === 'success') {
      onPublished();
    }
  }

  return (
    <form className="space-y-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
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
        <p className="mt-0.5 mb-2.5 text-[11px] text-subtle-foreground">
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
          <p className="-mt-1 mb-2 text-[11px] text-subtle-foreground">
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
          <div className="mb-1 hidden items-center gap-2 px-1 text-[11px] text-subtle-foreground @min-[37.5rem]:flex">
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
                  <p role="alert" className="mt-1 px-1 text-[11px] text-status-danger-fg">
                    {rowMessage}
                  </p>
                )}
                {/* An AGE row needs no band typed -- it is derived from the bounds on submit
                    so the label and the range cannot disagree. Said once, on the row that
                    would otherwise look unfinished. */}
                {!rowMessage && rowType === 'AGE' && !ratingRows?.[index]?.band && (
                  <p className="mt-1 px-1 text-[11px] text-subtle-foreground">
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
          <p className="mt-1 text-[11px] text-status-danger-fg">{errors.ratingTable.root.message}</p>
        )}
      </div>

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
      <div className="rounded-md border border-border p-3">
        <p className="text-xs font-medium text-muted-foreground">Base rates (optional)</p>
        <p className="mt-0.5 mb-2.5 text-[11px] text-subtle-foreground">
          The annual rate per 1,000 of sum assured, by age band, sex and smoker status. A
          version published without them is valid and sellable but{' '}
          <strong className="font-medium text-foreground">can never be quoted</strong>, and
          rates cannot be added afterwards. Leave a rate blank to not price that
          combination.
        </p>

        {/*
          41rem is this row's measured content width: 64 + 64 + 112 + 3x112 + 32 with six
          8px gaps = 656px. The header appears only at or above it, because a header over a
          row that has already wrapped labels the wrong boxes -- which is what 37.5rem, a
          figure carried over from the narrower rating table above, would have done.
        */}
        <div className="@container">
          <div className="mb-1 hidden items-center gap-2 px-1 text-[11px] text-subtle-foreground @min-[41rem]:flex">
            <span className="w-16 shrink-0 text-right">From</span>
            <span className="w-16 shrink-0 text-right">To</span>
            <span className="w-28 shrink-0">Sex</span>
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
                  <div className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 @min-[41rem]:rounded-none @min-[41rem]:border-0 @min-[41rem]:p-0">
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
                    <p role="alert" className="mt-1 px-1 text-[11px] text-status-danger-fg">
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

      <div className="rounded-md border border-border p-3">
        {/* Not optional any more, and the label has to say so before the submit does: a
            version that covers nothing is refused, and what is authored here is what a
            claim is later valued at. */}
        <p className="mb-2 text-xs font-medium text-muted-foreground">Benefit schedule</p>
        {errors.benefitSchedule?.root?.message && (
          <p role="alert" className="mb-2 text-[11px] text-status-danger-fg">
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
                  <p role="alert" className="mt-1 px-1 text-[11px] text-status-danger-fg">
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

      {category === 'UNIT_LINKED' && (
        <div className="rounded-md border border-border p-3">
          <p className="mb-2 text-xs font-medium text-muted-foreground">
            Fund definitions -- only valid for UNIT_LINKED products
          </p>
          <div className="space-y-2">
            {fundDefinitions.fields.map((field, index) => (
              <div key={field.id} className="flex items-center gap-2">
                <Input
                  inputSize="sm" className="flex-1"
                  placeholder="Fund code"
                  {...register(`fundDefinitions.${index}.fundCode`)}
                />
                <Input
                  type="number"
                  step="0.01"
                  inputSize="sm" className="w-28 text-right"
                  placeholder="Current NAV"
                  {...register(`fundDefinitions.${index}.currentNav`)}
                />
                <Button
                  type="button"
                  size="icon"
                  variant="ghost"
                  aria-label="Remove fund"
                  onClick={() => fundDefinitions.remove(index)}
                >
                  <X />
                </Button>
              </div>
            ))}
          </div>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="-ml-2 mt-2"
            onClick={() => fundDefinitions.append(blankFundRow())}
          >
            <Plus />
            Add fund
          </Button>
        </div>
      )}

      {publishing.status === 'error' && publishing.error && (
        <InlineError error={publishing.error} />
      )}

      <Button type="submit" variant="primary" disabled={publishing.status === 'loading'}>
        {publishing.status === 'loading' ? 'Publishing…' : 'Publish version'}
      </Button>
    </form>
  );
}
