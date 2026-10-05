import { Plus, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Controller, useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister, type UseFormSetValue } from 'react-hook-form';
import { quoteFuneral } from '@/api/funeral';
import type { FuneralQuoteView, FuneralTermsView } from '@/api/types';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select } from '@/components/ui/input';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatMoney } from '@/lib/money';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';
import { blankFuneralDependant, toFuneralDependants, type OpenCaseFormInput, type OpenCaseFormValues } from './openCaseForm';

const tzs = (amount: number) => formatMoney({ amount: String(amount), currencyCode: 'TZS' });

/**
 * A funeral plan on the case (family funeral cover): the plan, the family, and the server's own quote of it
 * as it is typed -- a line per life and the instalment, or the product's refusal in its words. The applicant
 * (or life assured) is the main member. The sum assured is the plan's main-member benefit, filled here
 * (plan R3); nothing on this screen computes a premium.
 */
export function FuneralLivesFields({
  terms,
  productId,
  productVersionId,
  mainMember,
  register,
  control,
  errors,
  setValue,
}: {
  terms: FuneralTermsView;
  productId: string;
  productVersionId: string;
  mainMember: { name: string; dateOfBirth: string | null } | null;
  register: UseFormRegister<OpenCaseFormInput>;
  control: Control<OpenCaseFormInput, unknown, OpenCaseFormValues>;
  errors: FieldErrors<OpenCaseFormInput>;
  setValue: UseFormSetValue<OpenCaseFormInput>;
}) {
  const dependants = useFieldArray({ control, name: 'funeralDependants' });
  const planCode = useWatch({ control, name: 'funeralPlanCode' });
  const frequency = useWatch({ control, name: 'premiumFrequency' });
  const commencement = useWatch({ control, name: 'proposedCommencementDate' });
  const rows = useWatch({ control, name: 'funeralDependants' });
  const [quote, setQuote] = useState<FuneralQuoteView | null>(null);
  const [refusal, setRefusal] = useState<ApiError | null>(null);

  // The sum assured follows the plan: it IS the main member's benefit (R3).
  const mainBenefit = terms.benefits.find((b) => b.planCode === planCode && b.role === 'MAIN_MEMBER')?.benefit;
  useEffect(() => {
    setValue('sumAssuredAmount', mainBenefit != null ? mainBenefit.toFixed(2) : '');
  }, [mainBenefit, setValue]);

  const quotable = planCode !== '' && frequency !== '' && mainMember?.dateOfBirth != null;
  const lives = JSON.stringify(toFuneralDependants({ funeralDependants: rows ?? [] }));
  useEffect(() => {
    if (!quotable || !mainMember?.dateOfBirth) return undefined;
    const timer = setTimeout(() => {
      quoteFuneral(productId, productVersionId, {
        planCode,
        frequency: frequency as 'MONTHLY' | 'QUARTERLY' | 'ANNUALLY',
        ...(commencement ? { asOf: commencement } : {}),
        lives: [
          { role: 'MAIN_MEMBER', name: mainMember.name, dateOfBirth: mainMember.dateOfBirth },
          ...(JSON.parse(lives) as { role: FuneralRoleName; fullName: string; dateOfBirth: string; student: boolean }[])
            .map((d) => ({ role: d.role, name: d.fullName, dateOfBirth: d.dateOfBirth || null, student: d.student })),
        ],
      }).then(
        (q) => { setQuote(q); setRefusal(null); },
        (e: unknown) => { setQuote(null); setRefusal(toApiError(e)); },
      );
    }, 400);
    return () => clearTimeout(timer);
  }, [quotable, productId, productVersionId, planCode, frequency, commencement, lives, mainMember?.name, mainMember?.dateOfBirth]);

  const allowedRoles = terms.roles.map((r) => r.role).filter((r) => r !== 'MAIN_MEMBER');

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

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">The family</legend>
        {dependants.fields.map((field, i) => (
          <div key={field.id} className="grid grid-cols-[8rem_1fr_10rem_6rem_auto] items-end gap-2">
            <FormField label="Role">
              <Select inputSize="sm" {...register(`funeralDependants.${i}.role`)}>
                {allowedRoles.map((r) => (
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
        <Button type="button" variant="outline" size="sm" onClick={() => dependants.append(blankFuneralDependant())}>
          <Plus className="size-4" /> Add dependant
        </Button>
      </fieldset>

      {refusal && <InlineError error={refusal} lead="This family cannot be covered as entered:" />}
      {quote && (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-sm">
            <caption className="sr-only">Family quote</caption>
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="p-2">Life</th><th className="p-2">Role</th><th className="p-2">Age</th>
                <th className="p-2">Benefit</th><th className="p-2">Yearly premium</th>
              </tr>
            </thead>
            <tbody>
              {quote.lines.map((line, i) => (
                <tr key={i} className="border-t border-border">
                  <td className="p-2">{line.name}</td>
                  <td className="p-2">{FUNERAL_ROLE_LABELS[line.role as FuneralRoleName]}</td>
                  <td className="p-2">{line.age}</td>
                  <td className="p-2">{tzs(line.benefit)}</td>
                  <td className="p-2">{tzs(line.yearlyPremium)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t border-border font-medium">
                <td className="p-2" colSpan={4}>Family total a year</td>
                <td className="p-2">{tzs(quote.totalYearlyPremium)}</td>
              </tr>
              <tr className="font-medium">
                <td className="p-2" colSpan={4}>Each {quote.frequency.toLowerCase()} payment</td>
                <td className="p-2" aria-label="Instalment">{tzs(quote.instalment)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </div>
  );
}
