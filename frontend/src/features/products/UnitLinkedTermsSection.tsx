import { useEffect } from 'react';
import { Controller, useWatch, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { FormField } from '@/components/FormField';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input, Select, Textarea } from '@/components/ui/input';
import { useUnitLinkedStore } from '@/store/unitLinkedStore';
import type { PublishVersionFormInput, PublishVersionFormValues } from './publishVersionSchema';
import { UL_FREQUENCIES, UL_FREQUENCY_LABELS, parseAllocation, parseMortality } from './unitLinkedSchema';

function Alert({ message }: { message: string | undefined }) {
  return message ? <p role="alert" className="mt-1 text-xs text-status-danger-fg">{message}</p> : null;
}

/**
 * A UNIT_LINKED version's terms (product step 6): the register funds it offers, how much of each premium
 * buys units in each policy year, the monthly fee and the mortality table the cost of insurance comes
 * from, what death pays and when the policy lapses, and the premium and sum-assured bounds. Every rule the
 * unit engine applies is the version's own -- staff configure it; nothing is hard-coded. A unit-linked
 * version carries no rating table or base rates: its cost of insurance is its mortality table.
 */
export function UnitLinkedTermsSection({
  register,
  control,
  errors,
  currency,
}: {
  register: UseFormRegister<PublishVersionFormInput>;
  control: Control<PublishVersionFormInput, unknown, PublishVersionFormValues>;
  errors: FieldErrors<PublishVersionFormInput>;
  currency: string | undefined;
}) {
  const funds = useUnitLinkedStore((s) => s.funds);
  const loadFunds = useUnitLinkedStore((s) => s.loadFunds);
  useEffect(() => {
    void loadFunds();
  }, [loadFunds]);
  const offerable = (funds.data ?? []).filter((f) => f.status === 'OPEN' && (!currency || f.currency === currency));

  const allocationText = useWatch({ control, name: 'ulAllocationText' }) ?? '';
  const mortalityText = useWatch({ control, name: 'ulMortalityText' }) ?? '';
  const allocation = parseAllocation(allocationText);
  const mortality = parseMortality(mortalityText);

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <div>
        <p className="text-xs font-medium text-muted-foreground">Unit-linked terms</p>
        <p className="mt-0.5 text-xs text-subtle-foreground">
          Premiums buy units at the first price approved after they are received (forward pricing). Each month the policy
          fee and the cost of insurance are sold from the units. The fund management charge is already in the price.
        </p>
      </div>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Funds offered</legend>
        <Alert message={errors.ulFundCodes?.message ?? errors.ulFundCodes?.root?.message} />
        {funds.status === 'loading' && offerable.length === 0 ? (
          <p className="text-xs text-muted-foreground">Reading the fund register…</p>
        ) : offerable.length === 0 ? (
          <p className="text-xs text-muted-foreground">
            No open fund{currency ? ` in ${currency}` : ''} is in the register. Finance adds funds on the Funds screen.
          </p>
        ) : (
          <Controller
            control={control}
            name="ulFundCodes"
            render={({ field }) => (
              <div className="flex flex-wrap gap-4">
                {offerable.map((f) => {
                  const chosen = (field.value ?? []).includes(f.code);
                  return (
                    <CheckboxField
                      key={f.code}
                      label={`${f.code} · ${f.name}`}
                      checked={chosen}
                      onChange={(e) =>
                        field.onChange(
                          e.target.checked
                            ? [...(field.value ?? []), f.code]
                            : (field.value ?? []).filter((c: string) => c !== f.code),
                        )
                      }
                    />
                  );
                })}
              </div>
            )}
          />
        )}
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Allocation and charges</legend>
        <p className="text-xs text-subtle-foreground">
          One band per line: <code>fromYear,toYear,percent</code> — e.g. <code>1,1,60</code> then <code>2,,97.5</code>. The
          bands start at year 1 and the last is open-ended. What is not allocated is the allocation charge.
        </p>
        <FormField label="Allocation bands" error={errors.ulAllocationText?.message}>
          <Textarea rows={3} className="font-mono text-xs" {...register('ulAllocationText')} />
        </FormField>
        {!allocation.error && allocation.rows.length > 0 && (
          <p className="text-xs text-subtle-foreground">{allocation.rows.length} allocation bands read.</p>
        )}
        <FormField label={`Monthly policy fee${currency ? ` (${currency})` : ''}`} error={errors.ulPolicyFee?.message}>
          <Input inputSize="sm" inputMode="decimal" {...register('ulPolicyFee')} />
        </FormField>
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Cost of insurance</legend>
        <FormField label="Mortality basis" error={errors.ulMortalityBasis?.message}>
          <Select inputSize="sm" {...register('ulMortalityBasis')}>
            <option value="">Choose…</option>
            <option value="UNISEX">Unisex</option>
            <option value="BY_SEX">By sex</option>
          </Select>
        </FormField>
        <p className="text-xs text-subtle-foreground">
          One band per line: <code>ageFrom,ageTo,sex,ratePerMille</code> — e.g. <code>18,39,,1.2</code> on a unisex table or{' '}
          <code>18,39,FEMALE,1.0</code> by sex. The rate is a year's charge per 1,000 of the amount at risk; the table starts at
          the minimum entry age and its last band is open-ended.
        </p>
        <FormField label="Mortality table" error={errors.ulMortalityText?.message}>
          <Textarea rows={6} className="font-mono text-xs" {...register('ulMortalityText')} />
        </FormField>
        {!mortality.error && mortality.rows.length > 0 && (
          <p className="text-xs text-subtle-foreground">{mortality.rows.length} mortality rows read.</p>
        )}
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Death, lapse and surrender</legend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Death pays" error={errors.ulDeathRule?.message}>
            <Select inputSize="sm" {...register('ulDeathRule')}>
              <option value="">Choose…</option>
              <option value="HIGHER_OF">The higher of the sum assured and the fund value</option>
              <option value="SUM_ASSURED_PLUS_FUND">The sum assured plus the fund value</option>
            </Select>
          </FormField>
          <FormField label="The policy lapses" error={errors.ulLapseRule?.message}>
            <Select inputSize="sm" {...register('ulLapseRule')}>
              <option value="EXHAUSTION">When the units can no longer meet the charges</option>
              <option value="NON_PAYMENT">On non-payment of premium</option>
            </Select>
          </FormField>
          <FormField label="Minimum premium-paying years (optional)" error={errors.ulMinimumPremiumYears?.message}>
            <Input inputSize="sm" inputMode="numeric" {...register('ulMinimumPremiumYears')} />
          </FormField>
          <FormField label="Minimum years before surrender" error={errors.ulMinimumSurrenderYears?.message}>
            <Input inputSize="sm" inputMode="numeric" {...register('ulMinimumSurrenderYears')} />
          </FormField>
          <FormField label="Warn when the units cover fewer months of charges than" error={errors.ulLowFundMonths?.message}>
            <Input inputSize="sm" inputMode="numeric" {...register('ulLowFundMonths')} />
          </FormField>
        </div>
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Premiums and sum assured</legend>
        <p className="text-xs text-subtle-foreground">The least premium per frequency; leave a frequency empty if it is not offered.</p>
        <Alert message={errors.ulMinimums?.message ?? errors.ulMinimums?.root?.message} />
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {UL_FREQUENCIES.map((f, i) => (
            <FormField key={f} label={`${UL_FREQUENCY_LABELS[f]} minimum`} error={errors.ulMinimums?.[i]?.message}>
              <Input inputSize="sm" inputMode="decimal" {...register(`ulMinimums.${i}`)} />
            </FormField>
          ))}
        </div>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Sum assured, least multiple of the annual premium" error={errors.ulMultipleMin?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulMultipleMin')} />
          </FormField>
          <FormField label="Sum assured, greatest multiple of the annual premium" error={errors.ulMultipleMax?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulMultipleMax')} />
          </FormField>
        </div>
      </fieldset>

      <fieldset className="space-y-2 rounded-md border border-border p-3">
        <legend className="text-xs font-medium text-muted-foreground">Switches, withdrawals, top-ups and the surrender charge</legend>
        <p className="text-xs text-subtle-foreground">
          Optional. A feature is offered only when both of its fields are filled in; leave them all empty and the policy
          offers none of them. Fixed once the version is published.
        </p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Free switches per policy year" error={errors.ulFreeSwitches?.message}>
            <Input inputSize="sm" inputMode="numeric" {...register('ulFreeSwitches')} />
          </FormField>
          <FormField label={`Fee per extra switch${currency ? ` (${currency})` : ''}`} error={errors.ulSwitchFee?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulSwitchFee')} />
          </FormField>
          <FormField label="Minimum withdrawal" error={errors.ulMinWithdrawal?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulMinWithdrawal')} />
          </FormField>
          <FormField label="Minimum value left after a withdrawal" error={errors.ulMinRemaining?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulMinRemaining')} />
          </FormField>
          <FormField label="Top-up allocation (%)" error={errors.ulTopUpPercent?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulTopUpPercent')} />
          </FormField>
          <FormField label="Minimum top-up" error={errors.ulMinTopUp?.message}>
            <Input inputSize="sm" inputMode="decimal" {...register('ulMinTopUp')} />
          </FormField>
        </div>
        <CheckboxField label="A withdrawal reduces the sum assured" {...register('ulWithdrawalCutsCover')} />
        <p className="text-xs text-subtle-foreground">
          Surrender charge, one band per line: <code>fromYear,toYear,percent</code> — e.g. <code>1,1,10</code>,{' '}
          <code>2,5,5</code>, <code>6,,0</code>. It applies to surrenders, withdrawals and a lapse for non-payment; never to
          death, maturity, free-look or a fund that ran out.
        </p>
        <FormField label="Surrender charge bands" error={errors.ulSurrenderText?.message}>
          <Textarea rows={3} className="font-mono text-xs" {...register('ulSurrenderText')} />
        </FormField>
      </fieldset>
    </div>
  );
}
