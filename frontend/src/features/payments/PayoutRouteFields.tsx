import { useId } from 'react';
import { useWatch, type FieldValues, type Path, type UseFormReturn } from 'react-hook-form';
import { FormField } from '@/components/FormField';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { PAYOUT_METHODS, TANZANIAN_BANKS, type PayoutMethod, type PayoutRouteValues } from './payoutRoute';

/**
 * The "how is it paid" half of any payout form (2026-10-28): the method as a segmented switch -- the pill pair
 * DESIGN.md names for a two- or three-way choice -- and below it only the fields that method needs. A bank transfer
 * asks for the bank, branch, account name and account number; mobile money for the number; a cheque for the name it
 * is made out to.
 */
export function PayoutRouteFields<T extends FieldValues & PayoutRouteValues>({
  form,
  numberHint,
}: {
  form: UseFormReturn<T>;
  /** Under the mobile-money number, where the form allows it blank -- what a blank number means there. */
  numberHint?: string;
}) {
  const banksId = useId();
  const method = useWatch({ control: form.control, name: 'method' as Path<T> }) as PayoutMethod;
  const errors = form.formState.errors as Partial<Record<keyof PayoutRouteValues, { message?: string }>>;
  const chosen = PAYOUT_METHODS.find((m) => m.value === method);
  const reg = (name: keyof PayoutRouteValues) => form.register(name as Path<T>);

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <p className="text-xs font-medium" id={`${banksId}-how`}>
          How is it paid?
        </p>
        <div role="radiogroup" aria-labelledby={`${banksId}-how`} className="inline-flex flex-wrap rounded-lg bg-control p-0.5">
          {PAYOUT_METHODS.map((m) => (
            <button
              key={m.value}
              type="button"
              role="radio"
              aria-checked={method === m.value}
              onClick={() =>
                form.setValue('method' as Path<T>, m.value as T[Path<T>], { shouldDirty: true, shouldValidate: false })
              }
              className={cn(
                'rounded-md px-3 py-1 text-sm transition-colors',
                method === m.value ? 'bg-surface font-medium text-foreground shadow-raise' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {m.label}
            </button>
          ))}
        </div>
        {chosen && <p className="text-xs text-muted-foreground">{chosen.hint}</p>}
      </div>

      {method === 'MOBILE_MONEY' && (
        <FormField label="Mobile-money number" hint={numberHint} error={errors.payeeRef?.message}>
          <Input {...reg('payeeRef')} placeholder="+255712345678" inputMode="tel" autoComplete="off" />
        </FormField>
      )}
      {method === 'CHEQUE' && (
        <FormField label="Cheque made out to" error={errors.payeeRef?.message}>
          <Input {...reg('payeeRef')} placeholder="Full name, as the bank will read it" autoComplete="off" />
        </FormField>
      )}
      {method === 'EFT' && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Bank" error={errors.payeeBank?.message}>
            <Input {...reg('payeeBank')} list={`${banksId}-banks`} placeholder="CRDB Bank" autoComplete="off" />
          </FormField>
          <datalist id={`${banksId}-banks`}>
            {TANZANIAN_BANKS.map((b) => (
              <option key={b} value={b} />
            ))}
          </datalist>
          <FormField label="Branch (optional)" error={errors.payeeBranch?.message}>
            <Input {...reg('payeeBranch')} placeholder="Kariakoo" autoComplete="off" />
          </FormField>
          <FormField label="Account name" error={errors.payeeAccountName?.message}>
            <Input {...reg('payeeAccountName')} placeholder="As the bank holds it" autoComplete="off" />
          </FormField>
          <FormField label="Account number" error={errors.payeeAccountNumber?.message}>
            <Input {...reg('payeeAccountNumber')} className="font-mono" inputMode="numeric" autoComplete="off" />
          </FormField>
        </div>
      )}
    </div>
  );
}
