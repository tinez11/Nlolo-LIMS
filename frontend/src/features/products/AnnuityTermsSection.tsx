import { Plus, X } from 'lucide-react';
import {
  Controller,
  useFieldArray,
  useWatch,
  type Control,
  type FieldErrors,
  type UseFormRegister,
  type UseFormSetValue,
} from 'react-hook-form';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select, Textarea } from '@/components/ui/input';
import { blankAnnuityForm, rateColumns } from './annuitySchema';
import type { PublishVersionFormInput, PublishVersionFormValues } from './publishVersionSchema';

const FREQUENCY_LABEL: Record<string, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  SEMI_ANNUAL: 'Semi-annual',
  ANNUAL: 'Annual',
};

function Alert({ message }: { message: string | undefined }) {
  return message ? <p role="alert" className="mt-1 text-xs text-status-danger-fg">{message}</p> : null;
}

/**
 * An ANNUITY version's terms (product step 5): when income is paid, the actuarial basis, the forms --
 * each a combination of guarantee, joint life, escalation and capital protection, with its own rate
 * grid -- and the frequencies offered. Every field renders its own error, in AnnuityPlanValidator's
 * words, so nothing the server would refuse is refused invisibly.
 */
export function AnnuityTermsSection({
  register,
  control,
  errors,
  setValue,
}: {
  register: UseFormRegister<PublishVersionFormInput>;
  control: Control<PublishVersionFormInput, unknown, PublishVersionFormValues>;
  errors: FieldErrors<PublishVersionFormInput>;
  setValue: UseFormSetValue<PublishVersionFormInput>;
}) {
  const forms = useFieldArray({ control, name: 'annuityForms' });
  const formValues = useWatch({ control, name: 'annuityForms' });
  const anyJoint = (formValues ?? []).some((f) => f.joint);
  const annuityKind = useWatch({ control, name: 'annuityKind' });
  const deferred = annuityKind === 'DEFERRED';
  const kindField = register('annuityKind');

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <div>
        <p className="text-xs font-medium text-muted-foreground">Annuity terms</p>
        <p className="mt-0.5 text-xs text-subtle-foreground">
          {deferred
            ? 'The income each 1,000 of the balance buys a year at vesting, by form and age. Every vesting age in the window must have a rate in every form.'
            : 'The income each 1,000 of purchase price buys a year, by form and age. Every entry age this version accepts must have a rate in every form — a gap is refused here, never defaulted at purchase.'}
        </p>
      </div>
      <FormField label="Annuity kind">
        <Select
          inputSize="sm"
          {...kindField}
          onChange={(e) => {
            void kindField.onChange(e);
            // A deferred annuity saves in an account first; an immediate one never does.
            setValue('valueBasis', e.target.value === 'DEFERRED' ? 'ACCOUNT' : 'SCALE');
          }}
        >
          <option value="IMMEDIATE">Immediate (bought with a single premium)</option>
          <option value="DEFERRED">Deferred (saves in an account, then vests)</option>
        </Select>
      </FormField>
      {deferred && (
        <fieldset className="space-y-2 rounded-md border border-border p-3">
          <legend className="text-xs font-medium text-muted-foreground">Vesting</legend>
          <p className="text-xs text-subtle-foreground">
            The pension vests on the retirement age chosen at sale, or as staff instruct inside this window. With no
            instruction it vests into the default form and frequency with no lump sum, at the rates in force that day.
          </p>
          <div className="grid grid-cols-3 gap-3">
            <FormField label="Minimum vesting age" error={errors.vestingMinAge?.message}>
              <Input inputSize="sm" inputMode="numeric" {...register('vestingMinAge')} />
            </FormField>
            <FormField label="Maximum vesting age" error={errors.vestingMaxAge?.message}>
              <Input inputSize="sm" inputMode="numeric" {...register('vestingMaxAge')} />
            </FormField>
            <FormField label="Lump-sum cap (%)" error={errors.vestingCap?.message}>
              <Input inputSize="sm" inputMode="decimal" placeholder="25" {...register('vestingCap')} />
            </FormField>
            <FormField label="Default form" error={errors.vestingDefaultForm?.message}>
              <Select inputSize="sm" {...register('vestingDefaultForm')}>
                <option value="">Choose…</option>
                {(formValues ?? []).filter((f) => f.formCode !== '').map((f) => (
                  <option key={f.formCode} value={f.formCode}>{f.formCode}</option>
                ))}
              </Select>
            </FormField>
            <FormField label="Default frequency" error={errors.vestingDefaultFrequency?.message}>
              <Select inputSize="sm" {...register('vestingDefaultFrequency')}>
                <option value="">Choose…</option>
                {(['MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'] as const).map((f) => (
                  <option key={f} value={f}>{FREQUENCY_LABEL[f]}</option>
                ))}
              </Select>
            </FormField>
            <FormField label="Locked before vesting" error={errors.vestingLocked?.message}>
              <Select inputSize="sm" {...register('vestingLocked')}>
                <option value="">Choose…</option>
                <option value="YES">Yes — no surrender or withdrawal before it vests</option>
                <option value="NO">No — it may be surrendered before it vests</option>
              </Select>
            </FormField>
          </div>
        </fieldset>
      )}
      <div className="grid grid-cols-2 gap-3">
        <FormField label="Income paid" error={errors.annuityTiming?.message}>
          <Select inputSize="sm" {...register('annuityTiming')}>
            <option value="">Choose…</option>
            <option value="ARREARS">In arrears — one period after purchase</option>
            <option value="ADVANCE">In advance — from the purchase date</option>
          </Select>
        </FormField>
        <FormField label="Proof of life every (months)" error={errors.annuityProofOfLifeMonths?.message}>
          <Input inputSize="sm" inputMode="numeric" {...register('annuityProofOfLifeMonths')} />
        </FormField>
        <FormField label="Rate basis reference" error={errors.annuityBasisReference?.message}>
          <Input inputSize="sm" placeholder="e.g. a(90) ult, 4% net" {...register('annuityBasisReference')} />
        </FormField>
        <FormField label="Rate basis date" error={errors.annuityBasisDate?.message}>
          <Controller
            control={control}
            name="annuityBasisDate"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
      </div>

      <fieldset className="space-y-2">
        <legend className="text-xs font-medium text-muted-foreground">Payment frequencies</legend>
        <p className="text-xs text-subtle-foreground">
          The factor scales the annual income for paying more often. Leave blank for a frequency not offered; annual is 1.
        </p>
        <div className="grid grid-cols-4 gap-3">
          {(['MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'] as const).map((frequency, index) => (
            <FormField
              key={frequency}
              label={`${FREQUENCY_LABEL[frequency]} factor`}
              error={errors.annuityFactors?.[index]?.factor?.message}
            >
              <Input inputSize="sm" inputMode="decimal" placeholder="Not offered" {...register(`annuityFactors.${index}.factor`)} />
            </FormField>
          ))}
        </div>
        <Alert message={errors.annuityFactors?.message ?? errors.annuityFactors?.root?.message} />
      </fieldset>

      {anyJoint && (
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Smallest age difference (annuitant − joint life)" error={errors.annuityJointDiffMin?.message}>
            <Input inputSize="sm" inputMode="numeric" {...register('annuityJointDiffMin')} />
          </FormField>
          <FormField label="Largest age difference" error={errors.annuityJointDiffMax?.message}>
            <Input inputSize="sm" inputMode="numeric" {...register('annuityJointDiffMax')} />
          </FormField>
        </div>
      )}

      <div className="space-y-3">
        {forms.fields.map((field, index) => {
          const value = formValues?.[index] ?? blankAnnuityForm();
          const formErrors = errors.annuityForms?.[index];
          return (
            <fieldset key={field.id} className="space-y-2 rounded-md border border-border p-3">
              <div className="flex items-center justify-between">
                <legend className="text-xs font-medium">Form {index + 1}</legend>
                <Button
                  type="button" size="icon" variant="ghost" aria-label={`Remove annuity form ${index + 1}`}
                  onClick={() => forms.remove(index)}
                >
                  <X className="size-4" />
                </Button>
              </div>
              <div className="grid grid-cols-4 gap-3">
                <FormField label="Form code" error={formErrors?.formCode?.message}>
                  <Input inputSize="sm" placeholder="L10" {...register(`annuityForms.${index}.formCode`)} />
                </FormField>
                <FormField label="Guaranteed years" error={formErrors?.guaranteeYears?.message}>
                  <Input inputSize="sm" inputMode="numeric" {...register(`annuityForms.${index}.guaranteeYears`)} />
                </FormField>
                <FormField label="Escalation (% a year)" error={formErrors?.escalationPercent?.message}>
                  <Input inputSize="sm" inputMode="decimal" {...register(`annuityForms.${index}.escalationPercent`)} />
                </FormField>
                <FormField label="Rate basis">
                  <Select inputSize="sm" {...register(`annuityForms.${index}.rateBasis`)}>
                    <option value="UNISEX">Unisex</option>
                    <option value="BY_SEX">By sex</option>
                  </Select>
                </FormField>
              </div>
              <div className="flex flex-wrap items-end gap-4">
                <CheckboxField label="Joint life" {...register(`annuityForms.${index}.joint`)} />
                <CheckboxField label="Capital protected" {...register(`annuityForms.${index}.capitalProtected`)} />
                {value.joint && (
                  <FormField label="To the survivor (%)" error={formErrors?.survivorPercent?.message}>
                    <Input inputSize="sm" inputMode="decimal" className="w-28" {...register(`annuityForms.${index}.survivorPercent`)} />
                  </FormField>
                )}
              </div>
              {/* Rendered even on a single-life form: "only for a joint-life form" lands here after
                  untick-ing joint with a percentage left behind. */}
              {!value.joint && <Alert message={formErrors?.survivorPercent?.message} />}
              <FormField
                label={`Rates — one line each: ${rateColumns(value).join(', ')}`}
                error={formErrors?.ratesText?.message}
              >
                <Textarea
                  className="min-h-28 font-mono text-xs"
                  placeholder={value.joint ? '65, -5, 5, 62.5' : value.rateBasis === 'BY_SEX' ? 'FEMALE, 65, 58.2' : '65, 60.75'}
                  {...register(`annuityForms.${index}.ratesText`)}
                />
              </FormField>
            </fieldset>
          );
        })}
        <Alert message={errors.annuityForms?.message ?? errors.annuityForms?.root?.message} />
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => forms.append(blankAnnuityForm())}>
          <Plus className="size-4" /> Add an annuity form
        </Button>
      </div>
    </div>
  );
}
