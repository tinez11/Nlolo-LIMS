import { useState } from 'react';
import {
  useWatch,
  type Control,
  type FieldErrors,
  type UseFormGetValues,
  type UseFormRegister,
  type UseFormSetValue,
} from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import {
  FUNERAL_ROLE_LABELS,
  FUNERAL_ROLES,
  bandsFor,
  premiumsFromRows,
  pricedRoles,
  type FuneralFields,
  type FuneralRoleName,
  type FuneralRoleValues,
} from './funeralSchema';
import type { PublishVersionFormInput, PublishVersionFormValues } from './publishVersionSchema';

/**
 * A funeral version's premium table as a grid (2026-10-08): the plans down the side, each priced role's age
 * bands across, one yearly premium per box. Built from what the form already says -- the plans, which roles each
 * covers, and the roles' entry and cover ages -- so the only thing typed is a price, and where a role's prices
 * change with age. Rows from a spreadsheet can still be pasted to fill it.
 */
export function PremiumGrid({ register, control, errors, setValue, getValues }: {
  register: UseFormRegister<PublishVersionFormInput>;
  control: Control<PublishVersionFormInput, unknown, PublishVersionFormValues>;
  errors: FieldErrors<PublishVersionFormInput>;
  setValue: UseFormSetValue<PublishVersionFormInput>;
  getValues: UseFormGetValues<PublishVersionFormInput>;
}) {
  const plans = useWatch({ control, name: 'funeralPlans' }) ?? [];
  const roles = useWatch({ control, name: 'funeralRoles' }) ?? [];
  const splits = useWatch({ control, name: 'funeralBandSplits' }) ?? [];
  const maxPricedAge = useWatch({ control, name: 'funeralMaxPricedAge' }) ?? '';
  const [pasting, setPasting] = useState(false);
  const [pasted, setPasted] = useState('');
  const [pasteError, setPasteError] = useState<string | null>(null);

  const priced = pricedRoles({ funeralPlans: plans, funeralRoles: roles });
  const columns = priced.map((r) => ({
    r,
    role: FUNERAL_ROLES[r] as FuneralRoleName,
    ...bandsFor(roles[r] as FuneralRoleValues, splits[r] ?? '', maxPricedAge),
  }));
  const planLabel = (code: string, i: number) => code.trim() || `Plan ${i + 1}`;

  function fillFromRows() {
    const result = premiumsFromRows(pasted, getValues() as unknown as FuneralFields);
    if (result.error) {
      setPasteError(result.error);
      return;
    }
    result.splits.forEach((s, r) => setValue(`funeralBandSplits.${r}`, s));
    result.premiums.forEach((p, i) => setValue(`funeralPlans.${i}.premiums`, p, { shouldValidate: false }));
    setPasteError(null);
    setPasting(false);
    setPasted('');
  }

  if (plans.length === 0 || priced.length === 0) {
    return (
      <p className="text-xs text-subtle-foreground">
        Add a plan and the benefits it pays above; the grid builds itself from the roles each plan covers.
      </p>
    );
  }

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <p className="text-xs text-subtle-foreground">
          Where does a role’s price change with age? Type the ages a new band starts at — e.g. <code>41, 56</code> — or
          leave it empty for one price at every age. Bands run from the youngest entry age to the last age the role is
          priced to, set under “Who may be covered”.
        </p>
        <div className="flex flex-wrap gap-3">
          {columns.map(({ r, role, error }) => (
            <label key={role} className="block w-44 text-xs">
              <span className="mb-1 block font-medium text-muted-foreground">{FUNERAL_ROLE_LABELS[role]}: new band at ages</span>
              <Input inputSize="sm" placeholder="One price" aria-invalid={error ? true : undefined}
                {...register(`funeralBandSplits.${r}`)} />
              {(error ?? errors.funeralBandSplits?.[r]?.message) && (
                <span role="alert" className="mt-1 block text-status-danger-fg">{error ?? errors.funeralBandSplits?.[r]?.message}</span>
              )}
            </label>
          ))}
        </div>
      </div>

      <div className="overflow-x-auto">
        <table className="text-xs">
          <caption className="sr-only">Yearly premium per plan, role and age band</caption>
          <thead>
            <tr className="text-left text-muted-foreground">
              <th rowSpan={2} className="p-1 align-bottom">Plan</th>
              {columns.map(({ role, bands }) => (
                <th key={role} colSpan={Math.max(bands.length, 1)} className="border-l border-border p-1 text-center">
                  {FUNERAL_ROLE_LABELS[role]}
                </th>
              ))}
            </tr>
            <tr className="text-muted-foreground">
              {columns.flatMap(({ role, bands }) => (bands.length === 0
                ? [<th key={role} className="border-l border-border p-1 font-normal">set entry ages</th>]
                : bands.map((b, k) => (
                  <th key={b.key} className={`p-1 font-normal ${k === 0 ? 'border-l border-border' : ''}`}>
                    ages {b.from}–{b.to}
                  </th>
                ))))}
            </tr>
          </thead>
          <tbody>
            {plans.map((plan, i) => (
              <tr key={i} className="border-t border-border align-top">
                <th scope="row" className="p-1 text-left font-medium">{planLabel(plan.planCode, i)}</th>
                {columns.flatMap(({ r, role, bands }) => {
                  if (bands.length === 0) return [<td key={role} className="border-l border-border p-1" />];
                  const covered = (plan.benefits[r] ?? '') !== '';
                  return bands.map((b, k) => {
                    const message = errors.funeralPlans?.[i]?.premiums?.[b.key]?.message;
                    return (
                      <td key={b.key} className={`p-1 ${k === 0 ? 'border-l border-border' : ''}`}>
                        {covered ? (
                          <>
                            <Input inputSize="sm" inputMode="decimal" className="w-24"
                              aria-label={`${planLabel(plan.planCode, i)} ${FUNERAL_ROLE_LABELS[role]} ages ${b.from}–${b.to} yearly premium`}
                              aria-invalid={message ? true : undefined}
                              {...register(`funeralPlans.${i}.premiums.${b.key}`)} />
                            {message && (
                              <span className="mt-0.5 block w-24 text-status-danger-fg">
                                {message.endsWith('enter the yearly premium') ? 'Required' : 'Not valid'}
                              </span>
                            )}
                          </>
                        ) : (
                          <span className="block w-24 px-2 py-1 text-subtle-foreground" title="This plan does not cover this role">—</span>
                        )}
                      </td>
                    );
                  });
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-subtle-foreground">
        Yearly premium per life. A dependant’s may be 0 — included in the main member’s, for a flat family price; the
        main member’s may not. “—”: the plan does not cover that role.
      </p>
      <FirstPremiumError errors={errors} />

      {pasting ? (
        <div className="space-y-1.5 rounded-md border border-border p-2">
          <label className="block text-xs">
            <span className="mb-1 block font-medium text-muted-foreground">Premium rows</span>
            <Textarea rows={6} className="font-mono text-xs" value={pasted} onChange={(e) => setPasted(e.target.value)}
              placeholder={'plan,role,ageFrom,ageTo,yearlyPremium\nA,MAIN_MEMBER,18,65,100000'} />
          </label>
          <p className="text-xs text-subtle-foreground">
            One row per line, columns separated by commas or tabs (copied cells from a spreadsheet work). Replaces the
            grid’s prices.
          </p>
          {pasteError && <p role="alert" className="text-xs text-status-danger-fg">{pasteError}</p>}
          <div className="flex gap-2">
            <Button type="button" size="sm" onClick={fillFromRows}>Fill the grid</Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => { setPasting(false); setPasteError(null); }}>Cancel</Button>
          </div>
        </div>
      ) : (
        <Button type="button" size="sm" variant="ghost" className="-ml-2" onClick={() => setPasting(true)}>
          Paste rows from a spreadsheet
        </Button>
      )}
    </div>
  );
}

/** The first missing or wrong price in words, under the grid: a box says "Required"; this says which and why. */
function FirstPremiumError({ errors }: { errors: FieldErrors<PublishVersionFormInput> }) {
  const plansErrors = errors.funeralPlans;
  if (!Array.isArray(plansErrors)) return null;
  for (const planErrors of plansErrors) {
    const premiums = planErrors?.premiums as Record<string, { message?: string } | undefined> | undefined;
    const first = premiums && Object.values(premiums).find((e) => e?.message);
    if (first?.message) return <p role="alert" className="text-xs text-status-danger-fg">{first.message}</p>;
  }
  return null;
}
