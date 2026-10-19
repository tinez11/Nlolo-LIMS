import { Plus, X } from 'lucide-react';
import { useEffect } from 'react';
import { Controller, useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister, type UseFormSetValue } from 'react-hook-form';
import type { FuneralTermsView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select } from '@/components/ui/input';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';
import { blankFuneralDependant, type OpenCaseFormInput, type OpenCaseFormValues } from './openCaseForm';

/** Every role but the main member's: the main member is the applicant, never a row in the family list. */
type DependantRole = Exclude<FuneralRoleName, 'MAIN_MEMBER'>;

/**
 * A funeral plan on the case (family funeral cover): the plan, the family, and the server's own quote of it
 * as it is typed -- a line per life and the instalment, or the product's refusal in its words. The applicant
 * (or life assured) is the main member. The sum assured is the plan's main-member benefit, filled here
 * (plan R3); nothing on this screen computes a premium.
 */
export function FuneralLivesFields({
  terms,
  mainMember,
  register,
  control,
  errors,
  setValue,
}: {
  terms: FuneralTermsView;
  mainMember: { name: string; dateOfBirth: string | null } | null;
  register: UseFormRegister<OpenCaseFormInput>;
  control: Control<OpenCaseFormInput, unknown, OpenCaseFormValues>;
  errors: FieldErrors<OpenCaseFormInput>;
  setValue: UseFormSetValue<OpenCaseFormInput>;
}) {
  const dependants = useFieldArray({ control, name: 'funeralDependants' });
  const planCode = useWatch({ control, name: 'funeralPlanCode' });
  const rows = useWatch({ control, name: 'funeralDependants' });

  // The sum assured follows the plan: it IS the main member's benefit (R3).
  const mainBenefit = terms.benefits.find((b) => b.planCode === planCode && b.role === 'MAIN_MEMBER')?.benefit;
  useEffect(() => {
    setValue('sumAssuredAmount', mainBenefit != null ? mainBenefit.toFixed(2) : '');
  }, [mainBenefit, setValue]);

  /* The family price now lives in the Quotation panel with every other product: useFuneralQuotation,
     called there, asks exactly what this used to ask. */

  /**
   * The roles this cover will actually take -- and so the only ones the form offers (2026-10-16).
   *
   * <p>It used to offer every role the VERSION rules, which is a different set from what the chosen PLAN pays for,
   * and a new dependant row arrived hard-coded as a CHILD whether or not the product covered one. On a product with
   * no child rule the select then showed an allowed role while the stored value stayed CHILD, and the quote came
   * back "This product does not cover a child" about a row nobody had chosen -- the refusal the user hit.
   *
   * <p>Two gates, because the server has two: a role with no rule on the version is not covered at all
   * ({@code plan.rule}), and a role the chosen plan prices no benefit for is not covered on THIS plan
   * ({@code plan.benefit}). Before a plan is picked only the first can be applied, which is why the plan narrows
   * the list rather than defining it.
   */
  const ruledRoles = terms.roles
    .map((r) => r.role)
    .filter((r): r is DependantRole => r !== 'MAIN_MEMBER');
  const allowedRoles = planCode
    ? ruledRoles.filter((r) => terms.benefits.some((b) => b.planCode === planCode && b.role === r))
    : ruledRoles;

  /**
   * A row already entered keeps its own role in its list even when the plan just chosen does not cover it.
   *
   * <p>Dropping it from the options would leave the select showing one role while the form held another -- exactly
   * the mismatch above, in reverse. Shown instead, so the quote's refusal is about something the screen admits to.
   */
  const rolesFor = (current: string) =>
    current && !allowedRoles.includes(current as DependantRole)
      ? [...allowedRoles, current as DependantRole]
      : allowedRoles;

  return (
    <div className="space-y-4">
      <FormField label="Plan" error={errors.funeralPlanCode?.message}>
        <Select {...register('funeralPlanCode')}>
          <option value="">Choose the plan</option>
          {terms.plans.map((p) => (
            <option key={p.planCode} value={p.planCode}>{p.name} ({p.planCode})</option>
          ))}
        </Select>
      </FormField>
      {!mainMember?.dateOfBirth && (
        <p className="text-xs text-subtle-foreground">Record the main member’s date of birth to quote the family.</p>
      )}

      {/* A cover that takes no dependant at all has no family to enter: the fieldset and its Add button are not
          shown rather than offered and then refused. The main member is still covered -- that is the whole plan. */}
      {allowedRoles.length === 0 ? (
        <p className="text-xs text-subtle-foreground">
          {planCode
            ? 'This plan covers the main member only, so there are no dependants to add.'
            : 'This product covers the main member only, so there are no dependants to add.'}
        </p>
      ) : (
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">The family</legend>
        {dependants.fields.map((field, i) => (
          <div key={field.id} className="grid grid-cols-[8rem_1fr_10rem_6rem_auto] items-end gap-2">
            <FormField label="Role">
              <Select inputSize="sm" {...register(`funeralDependants.${i}.role`)}>
                {rolesFor(rows?.[i]?.role ?? '').map((r) => (
                  <option key={r} value={r}>{FUNERAL_ROLE_LABELS[r as FuneralRoleName]}</option>
                ))}
              </Select>
            </FormField>
            <FormField label="Full name" error={errors.funeralDependants?.[i]?.fullName?.message}>
              <Input inputSize="sm" {...register(`funeralDependants.${i}.fullName`)} />
            </FormField>
            <FormField label="Date of birth" error={errors.funeralDependants?.[i]?.dateOfBirth?.message}>
              <Controller control={control} name={`funeralDependants.${i}.dateOfBirth`}
                render={({ field: f }) => <DatePicker value={f.value} onChange={f.onChange} />} />
            </FormField>
            <FormField label="Sex">
              <Select inputSize="sm" {...register(`funeralDependants.${i}.sex`)}>
                <option value="">—</option>
                <option value="FEMALE">Female</option>
                <option value="MALE">Male</option>
              </Select>
            </FormField>
            <div className="flex items-center gap-2 pb-1">
              {rows?.[i]?.role === 'CHILD' && <CheckboxField label="Student" {...register(`funeralDependants.${i}.student`)} />}
              <Button type="button" variant="ghost" size="sm" aria-label={`Remove dependant ${i + 1}`} onClick={() => dependants.remove(i)}>
                <X className="size-4" />
              </Button>
            </div>
          </div>
        ))}
        {/* The first role this cover actually takes, not a hard-coded CHILD: a row that arrives as a role the
            product does not cover is refused by a server that is right, about a choice nobody made. */}
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => dependants.append({ ...blankFuneralDependant(), role: allowedRoles[0] as DependantRole })}
        >
          <Plus className="size-4" /> Add dependant
        </Button>
      </fieldset>
      )}

    </div>
  );
}
