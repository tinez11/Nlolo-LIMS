import { zodResolver } from '@hookform/resolvers/zod';
import { Plus, X } from 'lucide-react';
import { useEffect } from 'react';
import { useForm, useFieldArray, Controller } from 'react-hook-form';
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
    },
  });

  const ratingTable = useFieldArray({ control, name: 'ratingTable' });
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
