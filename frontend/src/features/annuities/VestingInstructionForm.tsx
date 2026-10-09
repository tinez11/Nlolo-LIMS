import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm, useWatch } from 'react-hook-form';
import type { AnnuityTermsView, VestingView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PartyPicker } from '@/components/PartyPicker';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { useAnnuityStore } from '@/store/annuityStore';
import { toVestingInstruction, vestingFormSchema, type VestingFormContext, type VestingFormValues } from './vestingForm';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

const FREQUENCY_LABEL: Record<string, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  SEMI_ANNUAL: 'Every six months',
  ANNUAL: 'Annually',
};

/**
 * When and into what a pension vests (product step 5 D2): early, on its target, or deferred inside
 * the window it was sold with; a form and frequency the product's CURRENT version offers -- the one
 * that prices it on the day -- and a lump sum up to the cap. A deferral says whether contributions
 * continue to the new date or stop at the target.
 */
export function VestingInstructionForm({
  policyNumber,
  vesting,
  terms,
  today,
  onDone,
}: {
  policyNumber: string;
  vesting: VestingView;
  terms: AnnuityTermsView;
  today: string;
  onDone: () => void;
}) {
  const context: VestingFormContext = {
    today,
    target: vesting.targetDate,
    earliest: vesting.earliestVestingDate,
    latest: vesting.latestVestingDate,
    cap: vesting.maxCommutationPercent,
    jointForms: terms.forms.filter((f) => f.joint).map((f) => f.formCode),
  };
  const recordInstruction = useAnnuityStore((s) => s.recordInstruction);
  const acting = useAnnuityStore((s) => s.acting[`vesting.${policyNumber}`]);
  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<VestingFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(vestingFormSchema(context)),
    defaultValues: {
      vestingDate: vesting.vestingDate,
      formCode: vesting.formCode,
      frequency: vesting.frequency,
      jointLifePartyId: vesting.jointLifePartyId ?? '',
      lumpSumPercent: vesting.lumpSumPercent,
      contributions: vesting.contributions ?? '',
    },
  });
  const vestingDate = useWatch({ control, name: 'vestingDate' });
  const formCode = useWatch({ control, name: 'formCode' });
  const deferral = vestingDate > vesting.targetDate;
  const joint = context.jointForms.includes(formCode);

  async function onSubmit(values: VestingFormValues) {
    await recordInstruction(policyNumber, toVestingInstruction(values, context));
    if (useAnnuityStore.getState().acting[`vesting.${policyNumber}`]?.status === 'success') onDone();
  }

  return (
    <form className="space-y-3" aria-label="Vesting instruction" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
      <div className="grid grid-cols-2 gap-3">
        <FormField label="Vesting date" error={errors.vestingDate?.message}>
          <Controller
            control={control}
            name="vestingDate"
            render={({ field }) => <DatePicker value={field.value || null} onChange={(iso) => field.onChange(iso ?? '')} />}
          />
        </FormField>
        <FormField label="Lump sum (%)" error={errors.lumpSumPercent?.message}>
          <Input inputMode="decimal" {...register('lumpSumPercent')} />
          <p className="mt-1 text-xs text-subtle-foreground">Up to {vesting.maxCommutationPercent}% of the balance on the day.</p>
        </FormField>
        <FormField label="Form" error={errors.formCode?.message}>
          <Select {...register('formCode')}>
            <option value="">Choose…</option>
            {terms.forms.map((f) => (
              <option key={f.formCode} value={f.formCode}>{f.formCode}</option>
            ))}
          </Select>
        </FormField>
        <FormField label="Payment frequency" error={errors.frequency?.message}>
          <Select {...register('frequency')}>
            <option value="">Choose…</option>
            {terms.frequencies.map((f) => (
              <option key={f.frequency} value={f.frequency}>{FREQUENCY_LABEL[f.frequency] ?? f.frequency}</option>
            ))}
          </Select>
        </FormField>
      </div>
      {joint && (
        <FormField label="Joint life" error={errors.jointLifePartyId?.message}>
          <Controller
            control={control}
            name="jointLifePartyId"
            render={({ field }) => (
              <PartyPicker value={field.value || null} onChange={(id) => field.onChange(id ?? '')} placeholder="Search for the joint life" />
            )}
          />
        </FormField>
      )}
      {deferral && (
        <FormField label="Contributions" error={errors.contributions?.message}>
          <Select {...register('contributions')}>
            <option value="">Choose…</option>
            <option value="CONTINUE">Continue to the new date</option>
            <option value="STOP">Stop at {formatDate(vesting.targetDate)}</option>
          </Select>
        </FormField>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="flex gap-2">
        <Button type="submit" size="sm" pending={acting?.status === 'loading'}>
          Record instruction
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
