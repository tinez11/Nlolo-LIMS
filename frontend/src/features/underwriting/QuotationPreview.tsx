import { Panel } from '@/components/Panel';
import { ErrorPanel } from '@/components/states';
import { toApiError } from '@/lib/apiError';
import { formatMonths } from '@/lib/dates';
import { PREMIUM_FREQUENCY_LABELS } from '@/api/types';
import { DEFAULT_OCCUPATION_CLASS, useQuotation, type QuotationInputs } from './useQuotation';

/**
 * A money figure the SERVER sent as a JSON number.
 *
 * <p>The quote endpoint predates the common Money schema and answers in numbers, so this formats one for display.
 * It is formatting, not arithmetic: nothing here adds, scales or rounds a figure the server computed.
 */
function money(amount: number | undefined, currency: string | undefined): string {
  if (amount === undefined) return '—';
  return `${currency ?? 'TZS'} ${new Intl.NumberFormat('en-GB', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount)}`;
}

function Row({ label, value, note }: { label: string; value: string; note?: string | undefined }) {
  return (
    <div className="flex items-baseline gap-4 px-4 py-2">
      <span className="min-w-0 flex-1 text-sm">{label}</span>
      <span className="shrink-0 text-right text-sm whitespace-nowrap">{value}</span>
      {note && <span className="w-40 shrink-0 text-right text-xs text-muted-foreground">{note}</span>}
    </div>
  );
}

/**
 * The quotation for the case being opened, shown UNDER the form and before any case exists (2026-10-16).
 *
 * <p><b>Why here.</b> An underwriter used to decide without ever seeing a price: the case page showed the proposal,
 * the applicant and the product, and no premium at all. The figures were always computable -- the rate table prices
 * at issue -- but nothing showed them before the case was created.
 *
 * <p><b>It creates nothing.</b> The quote is a read; opening this panel records no quotation, no case, no invoice
 * and no accounting entry. It is a simulation of what would be charged, recalculated as the form changes.
 *
 * <p><b>Coverage term, premium-paying term and payment frequency are three separate rows</b>, because they are
 * three different things and the platform has been read as conflating them. A five-year policy stays a five-year
 * policy whether it is paid monthly or yearly.
 */
export function QuotationPreview({
  inputs,
  premiumPayingTermMonths,
  commencementDate,
}: {
  inputs: QuotationInputs;
  premiumPayingTermMonths: string;
  commencementDate: string;
}) {
  const quotation = useQuotation(inputs);

  return (
    <Panel
      title="Quotation"
      subtitle="What this life would be charged on the product above. Nothing is created by looking at it."
    >
      {quotation.state === 'incomplete' && (
        <p className="px-4 pb-4 text-xs text-muted-foreground">
          A quotation needs {quotation.missing.join(', ')}. Fill those in above and it appears here.
        </p>
      )}

      {quotation.state === 'loading' && (
        <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>
      )}

      {quotation.state === 'refused' && (
        <div className="px-4 pb-4">
          <ErrorPanel error={toApiError(quotation.error)} />
          <p className="pt-2 text-xs text-muted-foreground">
            This version cannot price this life. That is usually a rate table with no cell for the applicant&apos;s
            age, sex and smoker status, or no multiplier for their occupation class — not a fault in this screen.
          </p>
        </div>
      )}

      {quotation.state === 'quoted' && (
        <div className="divide-y divide-border">
          <Row
            label="Sum assured"
            value={money(Number(inputs.sumAssuredAmount), inputs.sumAssuredCurrency || 'TZS')}
          />
          <Row
            label="Premium"
            value={money(quotation.quote.instalmentAmount, quotation.quote.currency)}
            note={inputs.frequency ? PREMIUM_FREQUENCY_LABELS[inputs.frequency] : undefined}
          />
          {/* The server's own annual figure -- never an instalment multiplied here. */}
          <Row
            label="Annual equivalent"
            value={money(quotation.quote.annualAfterFrequencyLoading, quotation.quote.currency)}
            note={
              quotation.quote.instalmentsPerYear
                ? `${quotation.quote.instalmentsPerYear} a year`
                : 'charged once'
            }
          />

          <Row
            label="Coverage term"
            value={inputs.policyTermMonths ? formatMonths(Number(inputs.policyTermMonths)) : 'None stated'}
            note="how long cover runs"
          />
          <Row
            label="Premium-paying term"
            value={
              premiumPayingTermMonths
                ? formatMonths(Number(premiumPayingTermMonths))
                : inputs.policyTermMonths
                  ? 'The whole term'
                  : 'None stated'
            }
            note="how long premiums are paid"
          />
          <Row
            label="Payment frequency"
            value={inputs.frequency ? PREMIUM_FREQUENCY_LABELS[inputs.frequency] : '—'}
            note="how often, not how long"
          />
          {commencementDate && <Row label="Proposed commencement" value={commencementDate} />}

          {/* The derivation, so an underwriter can see WHY the premium is what it is rather than trusting it. */}
          <div className="px-4 py-2">
            <p className="text-xs font-medium text-muted-foreground">How it was priced</p>
            <dl className="pt-1 text-xs text-muted-foreground">
              <div className="flex justify-between gap-4">
                <dt>Age at entry</dt>
                <dd>
                  {quotation.quote.ageAtEntry ?? '—'}
                  {quotation.quote.ageFrom !== undefined && quotation.quote.ageTo !== undefined
                    ? ` (band ${quotation.quote.ageFrom}–${quotation.quote.ageTo})`
                    : ''}
                </dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt>Rate per mille, a year</dt>
                <dd>{quotation.quote.ratePerMille ?? '—'}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt>Annual base</dt>
                <dd>{money(quotation.quote.annualBase, quotation.quote.currency)}</dd>
              </div>
              {(quotation.quote.appliedFactors ?? []).map((factor) => (
                <div key={`${factor.factorType}-${factor.band}`} className="flex justify-between gap-4">
                  <dt>
                    {factor.factorType} {factor.band}
                  </dt>
                  <dd>× {factor.multiplier}</dd>
                </div>
              ))}
              {quotation.quote.frequencyLoadingPercent !== undefined &&
                quotation.quote.frequencyLoadingPercent !== 0 && (
                  <div className="flex justify-between gap-4">
                    <dt>Frequency loading</dt>
                    <dd>+{quotation.quote.frequencyLoadingPercent}%</dd>
                  </div>
                )}
            </dl>
            {quotation.assumedOccupationClass && (
              <p className="pt-1 text-xs text-muted-foreground">
                Priced as occupation class {DEFAULT_OCCUPATION_CLASS}: the applicant&apos;s record names none.
              </p>
            )}
            <p className="pt-1 text-xs text-subtle-foreground">
              A quotation on today&apos;s rates, not a contractual premium. The premium is fixed when the policy is
              issued, from the version active then.
            </p>
          </div>
        </div>
      )}
    </Panel>
  );
}
