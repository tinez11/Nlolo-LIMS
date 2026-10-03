import { useEffect, useState } from 'react';
import { Controller, type Control, type FieldErrors, type UseFormRegister } from 'react-hook-form';
import { quoteAnnuity } from '@/api/annuity';
import type { AnnuityQuoteView, AnnuityTermsView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PartyPicker } from '@/components/PartyPicker';
import { Select } from '@/components/ui/input';
import type { OpenCaseFormInput, OpenCaseFormValues } from '@/features/underwriting/openCaseForm';
import { formatMoney } from '@/lib/money';

const FREQUENCY_LABEL: Record<string, string> = {
  MONTHLY: 'Monthly',
  QUARTERLY: 'Quarterly',
  SEMI_ANNUAL: 'Every six months',
  ANNUAL: 'Annually',
};

type Quote = { key: string; quote?: AnnuityQuoteView; error?: unknown };

/**
 * What an annuity applicant chooses on the proposal (product step 5): a form the version offers,
 * how often the income is paid, and -- on a joint-life form -- the joint life. Below it, a live
 * quote: the income the purchase price buys today. A quote, not a promise -- the income is locked
 * from the rate table in force on the day the premium is collected.
 */
export function AnnuityPurchaseFields({
  terms,
  productVersionId,
  formCode,
  frequency,
  jointLifePartyId,
  annuitantPartyId,
  purchasePrice,
  register,
  control,
  errors,
}: {
  terms: AnnuityTermsView;
  productVersionId: string;
  formCode: string;
  frequency: string;
  jointLifePartyId: string;
  annuitantPartyId: string;
  purchasePrice: string;
  register: UseFormRegister<OpenCaseFormInput>;
  control: Control<OpenCaseFormInput, unknown, OpenCaseFormValues>;
  errors: FieldErrors<OpenCaseFormInput>;
}) {
  const form = terms.forms.find((f) => f.formCode === formCode);
  const joint = form?.joint ?? false;
  const price = Number(purchasePrice);
  const ready =
    !!form && frequency !== '' && annuitantPartyId !== '' && Number.isFinite(price) && price > 0 && (!joint || jointLifePartyId !== '');
  const key = ready ? [productVersionId, formCode, frequency, purchasePrice, annuitantPartyId, joint ? jointLifePartyId : ''].join('|') : '';

  const [quote, setQuote] = useState<Quote | null>(null);
  useEffect(() => {
    if (!key) return;
    let live = true;
    // Debounced: the price is typed a digit at a time, and each keystroke is not a question.
    const timer = setTimeout(() => {
      quoteAnnuity({
        productVersionId,
        formCode,
        frequency: frequency as AnnuityQuoteView['frequency'],
        purchasePrice: price,
        annuitantPartyId,
        jointLifePartyId: joint ? jointLifePartyId : null,
      }).then(
        (q) => live && setQuote({ key, quote: q }),
        (error: unknown) => live && setQuote({ key, error }),
      );
    }, 400);
    return () => {
      live = false;
      clearTimeout(timer);
    };
    // key carries every input the quote depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  const current = quote && quote.key === key ? quote : null;

  return (
    <fieldset className="space-y-4 rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-medium">Annuity</legend>
      <p className="text-xs text-muted-foreground">
        The sum assured above is the purchase price, paid as one single premium. There is no term: the income is paid
        for life, and the policy cannot be surrendered once the free-look period has passed.
      </p>
      <div className="grid grid-cols-2 gap-4">
        <FormField label="Annuity form" error={errors.annuityFormCode?.message}>
          <Select {...register('annuityFormCode')}>
            <option value="">Choose a form</option>
            {terms.forms.map((f) => (
              <option key={f.formCode} value={f.formCode}>
                {f.formCode} — {f.guaranteeYears ? `${f.guaranteeYears}-yr guarantee` : 'life only'}
                {f.joint ? `, joint ${f.survivorPercent}%` : ''}
                {Number(f.escalationPercent) > 0 ? `, +${f.escalationPercent}%/yr` : ''}
                {f.capitalProtected ? ', capital protected' : ''}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label="Income paid" error={errors.annuityFrequency?.message}>
          <Select {...register('annuityFrequency')}>
            <option value="">Choose a frequency</option>
            {terms.frequencies.map((f) => (
              <option key={f.frequency} value={f.frequency}>
                {FREQUENCY_LABEL[f.frequency] ?? f.frequency}
              </option>
            ))}
          </Select>
        </FormField>
      </div>
      {joint && (
        <FormField label="Joint life" error={errors.annuityJointLifePartyId?.message}>
          <Controller
            control={control}
            name="annuityJointLifePartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the joint life by name"
              />
            )}
          />
        </FormField>
      )}
      <div aria-live="polite" className="rounded-md bg-surface-muted px-3 py-2 text-xs">
        {!ready ? (
          <span className="text-muted-foreground">
            Choose the applicant, a form, a frequency{joint ? ', the joint life' : ''} and a purchase price to see the income
            it buys.
          </span>
        ) : !current ? (
          <span className="text-muted-foreground">Pricing…</span>
        ) : current.error ? (
          <InlineError error={current.error as Parameters<typeof InlineError>[0]["error"]} />
        ) : current.quote ? (
          <span>
            Quote today: <strong>{formatMoney(current.quote.instalment)}</strong>{' '}
            {(FREQUENCY_LABEL[current.quote.frequency] ?? current.quote.frequency).toLowerCase()} (
            {formatMoney(current.quote.annualIncome)} a year), at {current.quote.annualRatePerMille} per 1,000 for age{' '}
            {current.quote.annuitantAge}
            {current.quote.jointAge != null ? ` with a joint life of ${current.quote.jointAge}` : ''}. Locked when the
            premium is collected, at that day&apos;s rate.
          </span>
        ) : null}
      </div>
    </fieldset>
  );
}
