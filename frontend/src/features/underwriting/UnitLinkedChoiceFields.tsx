import { useWatch, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import type { UnitLinkedTermsView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { Input } from '@/components/ui/input';
import { formatMoney } from '@/lib/money';
import type { OpenCaseFormInput, OpenCaseFormValues } from './openCaseForm';

const PERIODS_PER_YEAR: Record<string, number> = { MONTHLY: 12, QUARTERLY: 4, ANNUALLY: 1, SINGLE: 1 };

const DEATH_RULE: Record<string, string> = {
  HIGHER_OF: 'the higher of the sum assured and the fund value',
  SUM_ASSURED_PLUS_FUND: 'the sum assured plus the fund value',
};

/**
 * What a unit-linked case is sold (product step 6): the premium the customer chose, how each premium is
 * split across the version's funds, and -- in the sum assured above -- the cover they chose. The minimum
 * premium for the frequency and the sum-assured range are the version's own, shown as the customer types
 * so the 422 the server would give is visible before it is met.
 */
export function UnitLinkedChoiceFields({
  terms,
  register,
  control,
  errors,
  currency,
}: {
  terms: UnitLinkedTermsView;
  register: UseFormRegister<OpenCaseFormInput>;
  control: Control<OpenCaseFormInput, unknown, OpenCaseFormValues>;
  errors: FieldErrors<OpenCaseFormInput>;
  currency: string;
}) {
  const split = useWatch({ control, name: 'ulSplit' }) ?? [];
  const premium = useWatch({ control, name: 'ulPremium' }) ?? '';
  const frequency = useWatch({ control, name: 'premiumFrequency' }) ?? '';
  const total = split.reduce((sum, row) => sum + (row.percent === '' ? 0 : Number(row.percent) || 0), 0);

  const minimum = terms.premiumMinimums.find((m) => m.frequency === frequency);
  const offered = terms.premiumMinimums.map((m) => m.frequency);
  const annual = Number(premium) > 0 && frequency in PERIODS_PER_YEAR ? Number(premium) * PERIODS_PER_YEAR[frequency] : null;
  const low = annual !== null ? annual * terms.sumAssuredMultipleMin : null;
  const high = annual !== null ? annual * terms.sumAssuredMultipleMax : null;
  const money = (n: number) => formatMoney({ amount: n.toFixed(2), currencyCode: currency });

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <div>
        <p className="text-xs font-medium text-muted-foreground">Unit-linked choice</p>
        <p className="mt-0.5 text-xs text-subtle-foreground">
          Each premium buys units at the first price approved after it is received. Death pays {DEATH_RULE[terms.deathRule]}.
          Takes {offered.join(', ').toLowerCase()} premiums.
        </p>
      </div>

      <FormField label={`Premium per payment (${currency})`} error={errors.ulPremium?.message}>
        <Input inputMode="decimal" placeholder="100000.00" {...register('ulPremium')} />
      </FormField>
      {frequency !== '' && !minimum && (
        <p role="alert" className="text-xs text-status-danger-fg">This product does not take {frequency} premiums</p>
      )}
      {minimum && Number(premium) > 0 && Number(premium) < minimum.amount && (
        <p role="alert" className="text-xs text-status-danger-fg">
          The {frequency} premium is at least {money(minimum.amount)}
        </p>
      )}
      {low !== null && high !== null && (
        <p className="text-xs text-subtle-foreground">
          The sum assured may be from {money(low)} to {money(high)} ({terms.sumAssuredMultipleMin}x to {terms.sumAssuredMultipleMax}x the{' '}
          {frequency === 'SINGLE' ? 'single premium' : 'annual premium'}).
        </p>
      )}

      <fieldset className="space-y-2">
        <legend className="text-xs font-medium text-muted-foreground">How each premium is split</legend>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {split.map((row, i) => (
            <FormField key={row.fundCode} label={`${row.fundCode} (%)`} error={errors.ulSplit?.[i]?.percent?.message}>
              <Input inputMode="numeric" placeholder="0" {...register(`ulSplit.${i}.percent`)} />
            </FormField>
          ))}
        </div>
        <p className={total === 100 ? 'text-xs text-subtle-foreground' : 'text-xs text-status-warning-fg'}>
          Split totals {total}%{total === 100 ? '.' : '; it must total 100%.'}
        </p>
        {(errors.ulSplit?.message ?? errors.ulSplit?.root?.message) && (
          <p role="alert" className="text-xs text-status-danger-fg">{errors.ulSplit?.message ?? errors.ulSplit?.root?.message}</p>
        )}
      </fieldset>
    </div>
  );
}
