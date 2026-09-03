import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect } from 'react';
import { useForm, useFieldArray, useWatch, Controller } from 'react-hook-form';
import { BENEFIT_TYPES, IFRS_MEASUREMENT_MODELS, RATING_FACTOR_TYPES, type ProductCategory } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { selectPublishing, useProductStore } from '@/store/productStore';
import {
  blankBenefitRow,
  blankFundRow,
  blankRatingFactorRow,
  publishVersionFormSchema,
  toApiRequest,
  type PublishVersionFormInput,
  type PublishVersionFormValues,
} from './publishVersionSchema';

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
      benefitSchedule: [],
      fundDefinitions: [],
      minEntryAge: '',
      maxEntryAge: '',
      minTermMonths: '',
      maxTermMonths: '',
      minSumAssured: '',
      maxSumAssured: '',
    },
  });

  const ratingTable = useFieldArray({ control, name: 'ratingTable' });
  // useWatch, not watch(): one subscription for the whole array rather than a watch() call
  // per row inside the render loop, which is both fewer re-renders and the form the React
  // Compiler can reason about.
  const ratingRows = useWatch({ control, name: 'ratingTable' });
  const benefitSchedule = useFieldArray({ control, name: 'benefitSchedule' });
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
          <select
            className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
            {...register('ifrsMeasurementModel')}
          >
            {IFRS_MEASUREMENT_MODELS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
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
            <input
              inputMode="numeric"
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="18"
              {...register('minEntryAge')}
            />
          </FormField>
          <FormField label="Maximum entry age" error={errors.maxEntryAge?.message}>
            <input
              inputMode="numeric"
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="65"
              {...register('maxEntryAge')}
            />
          </FormField>

          <FormField label="Minimum term (months)" error={errors.minTermMonths?.message}>
            <input
              inputMode="numeric"
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="60"
              {...register('minTermMonths')}
            />
          </FormField>
          <FormField label="Maximum term (months)" error={errors.maxTermMonths?.message}>
            <input
              inputMode="numeric"
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="360"
              {...register('maxTermMonths')}
            />
          </FormField>

          <FormField label="Minimum sum assured" error={errors.minSumAssured?.message}>
            <input
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="500000.00"
              {...register('minSumAssured')}
            />
          </FormField>
          <FormField label="Maximum sum assured" error={errors.maxSumAssured?.message}>
            <input
              className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
              placeholder="300000000.00"
              {...register('maxSumAssured')}
            />
          </FormField>
        </div>
      </div>

      <div className="rounded-md border border-border p-3">
        <p className="mb-2 text-xs font-medium text-muted-foreground">
          Rating table -- must cover at least AGE and SUM_ASSURED_BAND
        </p>
        <div className="space-y-2">
          {ratingTable.fields.map((field, index) => (
            <div key={field.id} className="flex items-center gap-2">
              <select
                className="h-8 rounded-md border border-input bg-surface px-2 text-xs"
                {...register(`ratingTable.${index}.factorType`)}
              >
                {RATING_FACTOR_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
              <input
                className="h-8 flex-1 rounded-md border border-input bg-surface px-2 text-xs"
                placeholder="Band, e.g. 18-30"
                {...register(`ratingTable.${index}.band`)}
              />
              {/* AGE is rated by RANGE, not by matching the band text. The band stays as
                  the label an actuary reads on the product screen; these two are what the
                  platform actually resolves against, so they show only where they mean
                  something and the backend refuses them anywhere else. */}
              {ratingRows?.[index]?.factorType === 'AGE' && (
                <>
                  <input
                    type="number"
                    min={0}
                    className="h-8 w-16 rounded-md border border-input bg-surface px-2 text-right text-xs"
                    placeholder="from"
                    aria-label={`Rating factor ${index + 1} from age`}
                    {...register(`ratingTable.${index}.ageFrom`)}
                  />
                  <input
                    type="number"
                    min={0}
                    className="h-8 w-16 rounded-md border border-input bg-surface px-2 text-right text-xs"
                    placeholder="to"
                    aria-label={`Rating factor ${index + 1} to age`}
                    {...register(`ratingTable.${index}.ageTo`)}
                  />
                </>
              )}
              <input
                type="number"
                step="0.01"
                className="h-8 w-24 rounded-md border border-input bg-surface px-2 text-right text-xs"
                placeholder="1.0"
                {...register(`ratingTable.${index}.multiplier`)}
              />
              <Button
                type="button"
                size="icon"
                variant="ghost"
                aria-label="Remove rating factor"
                onClick={() => ratingTable.remove(index)}
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
          onClick={() => ratingTable.append(blankRatingFactorRow())}
        >
          <Plus />
          Add rating factor
        </Button>
        {errors.ratingTable?.root?.message && (
          <p className="mt-1 text-[11px] text-status-danger-fg">{errors.ratingTable.root.message}</p>
        )}
      </div>

      <div className="rounded-md border border-border p-3">
        <p className="mb-2 text-xs font-medium text-muted-foreground">Benefit schedule (optional)</p>
        <div className="space-y-2">
          {benefitSchedule.fields.map((field, index) => (
            <div key={field.id} className="flex items-center gap-2">
              <select
                className="h-8 rounded-md border border-input bg-surface px-2 text-xs"
                {...register(`benefitSchedule.${index}.benefitType`)}
              >
                {BENEFIT_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
              <input
                className="h-8 flex-1 rounded-md border border-input bg-surface px-2 text-xs"
                placeholder="Calculation method"
                {...register(`benefitSchedule.${index}.calculationMethod`)}
              />
              <Button
                type="button"
                size="icon"
                variant="ghost"
                aria-label="Remove benefit"
                onClick={() => benefitSchedule.remove(index)}
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
                <input
                  className="h-8 flex-1 rounded-md border border-input bg-surface px-2 text-xs"
                  placeholder="Fund code"
                  {...register(`fundDefinitions.${index}.fundCode`)}
                />
                <input
                  type="number"
                  step="0.01"
                  className="h-8 w-28 rounded-md border border-input bg-surface px-2 text-right text-xs"
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
        <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
          {publishing.error.detail ?? publishing.error.title}
          {publishing.error.traceId && (
            <span className="ml-2 font-mono text-[10px] opacity-80">({publishing.error.traceId})</span>
          )}
        </div>
      )}

      <Button type="submit" variant="primary" disabled={publishing.status === 'loading'}>
        {publishing.status === 'loading' ? 'Publishing…' : 'Publish version'}
      </Button>
    </form>
  );
}
