import { Plus, X } from 'lucide-react';
import { useFieldArray, useWatch, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select, Textarea } from '@/components/ui/input';
import { FUNERAL_ROLE_LABELS, FUNERAL_ROLES, blankFuneralPlan, parsePremiums } from './funeralSchema';
import type { PublishVersionFormInput, PublishVersionFormValues } from './publishVersionSchema';

function Alert({ message }: { message: string | undefined }) {
  return message ? <p role="alert" className="mt-1 text-xs text-status-danger-fg">{message}</p> : null;
}

/**
 * A FUNERAL version's terms (family funeral cover): the plans a customer picks from and what each pays per
 * role, the fixed yearly premium per plan, role and age band, who may be covered in each role, and the claim
 * rules. Every rule here is the version's own -- staff configure it; nothing is hard-coded. Every field
 * renders its own error, in FuneralPlanValidator's words.
 */
export function FuneralTermsSection({
  register,
  control,
  errors,
}: {
  register: UseFormRegister<PublishVersionFormInput>;
  control: Control<PublishVersionFormInput, unknown, PublishVersionFormValues>;
  errors: FieldErrors<PublishVersionFormInput>;
}) {
  const plans = useFieldArray({ control, name: 'funeralPlans' });
  const premiumsText = useWatch({ control, name: 'funeralPremiumsText' }) ?? '';
  const parsed = parsePremiums(premiumsText);

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <div>
        <p className="text-xs font-medium text-muted-foreground">Funeral plan terms</p>
        <p className="mt-0.5 text-xs text-subtle-foreground">
          One policy covers the main member and their family. Each plan sets what a death pays for each role; each
          life pays the yearly premium for its role and age band. Every age a covered role can reach must be priced
          — a gap is refused here, never defaulted at sale.
        </p>
      </div>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Plans and benefits</legend>
        <p className="text-xs text-subtle-foreground">Leave a benefit empty where the plan does not cover that role.</p>
        <Alert message={errors.funeralPlans?.message ?? errors.funeralPlans?.root?.message} />
        {plans.fields.map((field, i) => (
          <div key={field.id} className="space-y-2 rounded-md border border-border p-2">
            <div className="flex items-end gap-2">
              <FormField label="Plan code" error={errors.funeralPlans?.[i]?.planCode?.message}>
                <Input inputSize="sm" placeholder="B" {...register(`funeralPlans.${i}.planCode`)} />
              </FormField>
              <FormField label="Plan name" error={errors.funeralPlans?.[i]?.name?.message}>
                <Input inputSize="sm" placeholder="Familia B" {...register(`funeralPlans.${i}.name`)} />
              </FormField>
              <Button type="button" variant="ghost" size="sm" aria-label={`Remove plan ${i + 1}`} onClick={() => plans.remove(i)}>
                <X className="size-4" />
              </Button>
            </div>
            <div className="grid grid-cols-5 gap-2">
              {FUNERAL_ROLES.map((role, r) => (
                <FormField key={role} label={`${FUNERAL_ROLE_LABELS[role]} benefit`}
                  error={errors.funeralPlans?.[i]?.benefits?.[r]?.message}>
                  <Input inputSize="sm" inputMode="decimal" {...register(`funeralPlans.${i}.benefits.${r}`)} />
                </FormField>
              ))}
            </div>
          </div>
        ))}
        <Button type="button" variant="outline" size="sm" onClick={() => plans.append(blankFuneralPlan())}>
          <Plus className="size-4" /> Add plan
        </Button>
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Premium table</legend>
        <p className="text-xs text-subtle-foreground">
          One row per line: <code>plan,role,ageFrom,ageTo,yearlyPremium</code> — e.g. <code>B,CHILD,0,24,6000</code>. A
          header line is ignored. Roles: {FUNERAL_ROLES.join(', ')}.
        </p>
        <FormField label="Premium rows" error={errors.funeralPremiumsText?.message}>
          <Textarea rows={8} className="font-mono text-xs" {...register('funeralPremiumsText')} />
        </FormField>
        {!parsed.error && parsed.rows.length > 0 && (
          <p className="text-xs text-subtle-foreground">{parsed.rows.length} premium rows read.</p>
        )}
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Who may be covered</legend>
        <p className="text-xs text-subtle-foreground">
          One main member and one spouse per policy. Leave “Cover stops at” empty where cover never stops for age.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <caption className="sr-only">Role rules</caption>
            <thead>
              <tr className="text-left text-muted-foreground">
                <th className="p-1">Role</th><th className="p-1">Allowed</th><th className="p-1">Most lives</th>
                <th className="p-1">Youngest entry</th><th className="p-1">Oldest entry</th>
                <th className="p-1">Cover stops at</th><th className="p-1">Student to</th>
              </tr>
            </thead>
            <tbody>
              {FUNERAL_ROLES.map((role, r) => (
                <tr key={role}>
                  <td className="p-1">{FUNERAL_ROLE_LABELS[role]}</td>
                  <td className="p-1">
                    <CheckboxField label={<span className="sr-only">{`${FUNERAL_ROLE_LABELS[role]} allowed`}</span>}
                      disabled={role === 'MAIN_MEMBER'} {...register(`funeralRoles.${r}.allowed`)} />
                  </td>
                  <td className="p-1">
                    <Input inputSize="sm" inputMode="numeric" aria-label={`${FUNERAL_ROLE_LABELS[role]} most lives`}
                      readOnly={role === 'MAIN_MEMBER' || role === 'SPOUSE'} {...register(`funeralRoles.${r}.maxLives`)} />
                  </td>
                  <td className="p-1">
                    <Input inputSize="sm" inputMode="numeric" aria-label={`${FUNERAL_ROLE_LABELS[role]} youngest entry age`}
                      {...register(`funeralRoles.${r}.minEntryAge`)} />
                  </td>
                  <td className="p-1">
                    <Input inputSize="sm" inputMode="numeric" aria-label={`${FUNERAL_ROLE_LABELS[role]} oldest entry age`}
                      {...register(`funeralRoles.${r}.maxEntryAge`)} />
                    <Alert message={errors.funeralRoles?.[r]?.maxEntryAge?.message} />
                  </td>
                  <td className="p-1">
                    <Input inputSize="sm" inputMode="numeric" aria-label={`${FUNERAL_ROLE_LABELS[role]} cover stops at`}
                      {...register(`funeralRoles.${r}.coverStopAge`)} />
                    <Alert message={errors.funeralRoles?.[r]?.coverStopAge?.message} />
                  </td>
                  <td className="p-1">
                    {role === 'CHILD' ? (
                      <>
                        <Input inputSize="sm" inputMode="numeric" aria-label="Child cover stops at, as a student"
                          {...register(`funeralRoles.${r}.studentStopAge`)} />
                        <Alert message={errors.funeralRoles?.[r]?.studentStopAge?.message} />
                      </>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <FormField label="Highest priced age" error={errors.funeralMaxPricedAge?.message}>
          <Input inputSize="sm" inputMode="numeric" {...register('funeralMaxPricedAge')} />
        </FormField>
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Claims</legend>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Waiting period (months)" error={errors.funeralWaitingMonths?.message}>
            <Input inputSize="sm" inputMode="numeric" placeholder="Empty for none" {...register('funeralWaitingMonths')} />
          </FormField>
          <div className="pt-6">
            <CheckboxField label="An accidental death has no waiting period" {...register('funeralAccidentWaives')} />
          </div>
          <FormField label="When a dependant dies, pay" error={errors.funeralPayee?.message}>
            <Select inputSize="sm" {...register('funeralPayee')}>
              <option value="">Choose…</option>
              <option value="MAIN_MEMBER">The main member</option>
              <option value="MAIN_MEMBER_BENEFICIARY">A beneficiary the main member nominated</option>
            </Select>
          </FormField>
          <FormField label="When the main member dies" error={errors.funeralOnMainMemberDeath?.message}>
            <Select inputSize="sm" {...register('funeralOnMainMemberDeath')}>
              <option value="">Choose…</option>
              <option value="POLICY_ENDS">The policy ends</option>
              <option value="SPOUSE_TAKES_OVER">The spouse takes over the policy</option>
            </Select>
          </FormField>
        </div>
        <CheckboxField label="Keep the family covered free to the next premium date after the main member dies"
          {...register('funeralFreeCover')} />
      </fieldset>
    </div>
  );
}
