import { Panel } from '@/components/Panel';
import { toApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { formatDate, formatMonths } from '@/lib/dates';
import { formatMoney, type Money } from '@/lib/money';
import { quoteAnnuity } from '@/api/annuity';
import type { AnnuityQuoteView } from '@/api/types';
import type { QuoteResult } from './quoteResult';
import { PREMIUM_FREQUENCY_LABELS, type SavingsQuoteView } from '@/api/types';
import { DEFAULT_OCCUPATION_CLASS, useQuotation, type QuotationInputs } from './useQuotation';
import { useSavingsQuotation } from './useSavingsQuotation';
import { DownloadQuotationButton } from './DownloadQuotationButton';
import { downloadQuotation } from '@/api/documents';
import { useFuneralQuotation, type FuneralDependantInput, type FuneralQuotationInputs } from './useFuneralQuotation';
import { InlineError } from '@/components/InlineError';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';
import { pricingBasis, type PricingBasis, type ProspectInputs } from './pricingBasis';
import { quotationRequest } from './quotationRequest';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { QuoteState } from './pricingBasis';

/** Tells a listening screen where the server's answer stands, each time it moves. */
function useReport(state: QuoteState, report: ((state: QuoteState) => void) | undefined) {
  useEffect(() => {
    report?.(state);
  }, [state, report]);
}

/** Hands the server's answer up, or null while there is none -- once per answer, not once per render. */
function useResult(result: QuoteResult | null, onResult: ((r: QuoteResult | null) => void) | undefined) {
  useEffect(() => {
    onResult?.(result);
  }, [result, onResult]);
}

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

/**
 * One fact: its name on the left, its figure at the RIGHT EDGE.
 *
 * <p>The figure ends the row rather than sitting in the middle of it, so every number on the panel lines up in one
 * column and can be read down. A {@code suffix} rides inside that same cell -- the premium's frequency, which
 * belongs to the figure rather than being a column of its own.
 */
/** A funeral figure: the quote endpoint answers in JSON numbers, always in the product's own TZS. */
const tzs = (amount: number) => formatMoney({ amount: String(amount), currencyCode: 'TZS' });

function Row({
  label,
  value,
  suffix,
  danger,
}: {
  label: string;
  value: string;
  suffix?: string | undefined;
  /** This figure could not be priced -- the amount or term named is refused -- not merely not asked for yet. */
  danger?: boolean | undefined;
}) {
  return (
    <div className="flex items-baseline gap-4 px-4 py-2">
      <span className="min-w-0 flex-1 text-sm">{label}</span>
      <span
        className={
          'shrink-0 text-right text-sm whitespace-nowrap' + (danger ? ' text-status-danger-fg font-medium' : '')
        }
      >
        {value}
        {suffix && <span className="pl-1.5 text-xs text-muted-foreground">{suffix}</span>}
      </span>
    </div>
  );
}


/**
 * The one figure a quotation is for, on the accent band (2026-10-22): the premium a life pays, what a deposit is worth
 * at maturity, what a family pays each time. Its row is dropped from the list below rather than repeated, so the figure
 * appears once on the panel. Set in the body face with tabular figures, as every number in the console is.
 */
function Headline({
  label,
  value,
  suffix,
  caption,
  valueLabel,
  sentence,
  split,
}: {
  label: string;
  value: string;
  suffix?: string | undefined;
  caption?: string | undefined;
  /** An accessible name for the figure alone, where a test or a reader finds it by what it is. */
  valueLabel?: string | undefined;
  /** The answer in one plain sentence, for a customer looking on (2026-10-23). */
  sentence?: string | undefined;
  /**
   * What the figure is made of, as one bar with each part named and its amount beside it (2026-10-23). The parts are
   * the server's figures; the bar only gives each its share of the width.
   */
  split?: { label: string; amount: Money; tone: 'paper' | 'teal' | 'amber' }[] | undefined;
}) {
  const parts = (split ?? []).filter((p) => Number(p.amount.amount) > 0);
  const whole = parts.reduce((a, p) => a + Number(p.amount.amount), 0) || 1;
  const TONE = { paper: 'bg-white', teal: 'bg-status-active-bg', amber: 'bg-chart-3' } as const;
  return (
    <div className="hero-scope mx-4 mb-2 rounded-xl bg-hero px-5 py-4">
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className="mt-1 flex flex-wrap items-baseline gap-x-2">
        <span className="text-display tabular-nums" aria-label={valueLabel}>
          {value}
        </span>
        {suffix && <span className="text-sm text-muted-foreground">{suffix}</span>}
      </p>
      {caption && <p className="mt-1 text-xs text-subtle-foreground">{caption}</p>}
      {sentence && <p className="mt-1 text-sm text-muted-foreground">{sentence}</p>}
      {parts.length > 1 && (
        <div className="mt-4 space-y-2.5 border-t border-white/25 pt-3">
          {/* The bar only where every part is big enough to see; a 2% sliver reads as a broken bar, not a share. */}
          {parts.every((p) => Number(p.amount.amount) / whole >= 0.04) && (
          <div className="flex h-2.5 gap-0.5 overflow-hidden rounded-full" aria-hidden>
            {parts.map((p, i) => (
              <span
                key={p.label}
                className={cn('chart-grow-x block h-full', TONE[p.tone])}
                style={{ flexGrow: Number(p.amount.amount) / whole, animationDelay: `${i * 80}ms` }}
              />
            ))}
          </div>
          )}
          <dl className="flex flex-wrap gap-x-6 gap-y-1.5 text-xs text-muted-foreground">
            {parts.map((p) => (
              <div key={p.label}>
                <dt className="flex items-center gap-1.5">
                  <i aria-hidden className={cn('inline-block size-2 rounded-sm', TONE[p.tone])} />
                  {p.label}
                </dt>
                <dd className="text-sm font-bold text-foreground tabular-nums">{formatMoney(p.amount)}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </div>
  );
}

/** What prices this one, named so a staff member can go and look at it. */
const OWN_SECTION_BY_PORTFOLIO: Record<string, string> = {
  IANN: 'The Annuity fields above price this purchase: the form chosen, the annuitant’s age and the version’s annuity rate decide the income.',
  DANN: 'A deferred annuity accumulates contributions now and buys the income at vesting, so there is no premium to rate today — the retirement age above sets when that happens.',
  PEN: 'A pension accumulates contributions now and buys the income at retirement, so there is no premium to rate today — the retirement age above sets when that happens.',
  ULIP: 'The unit-linked fields above hold this version’s premium limits and fund split. A unit-linked premium is chosen rather than rated: it buys units at the next price.',
};

export function QuotationPreview({
  inputs,
  premiumPayingTermMonths,
  commencementDate,
  portfolioCode,
  productVersionId,
  funeral,
  applicantPartyId = '',
  prospect,
  onQuoteState,
  termAccount,
  forCustomer = false,
  onResult,
  annuity,
}: {
  /**
   * Handed the server's answer as it arrives, or null while there is none (2026-10-23), so the calculator can draw it
   * over time and price alternatives beside it.
   */
  onResult?: ((result: QuoteResult | null) => void) | undefined;
  /**
   * An immediate annuity, quoted here on the calculator (2026-10-23): the form, how often it pays, and the annuitant --
   * a client's id, or a prospect's date of birth and sex. The purchase price is the form's amount.
   */
  annuity?: {
    formCode: string;
    frequency: string;
    annuitantPartyId: string;
    dateOfBirth: string | null;
    sex: string | null;
  } | undefined;
  /**
   * Shown with a customer looking on (the agents' calculator): how the premium was derived -- age band, rate per mille,
   * factors -- folds away behind its heading. On a staff form it stays open, because an underwriter reads it.
   */
  forCustomer?: boolean | undefined;
  /**
   * A term account's own inputs (2026-10-21): the rate the case names, and what the customer wants at maturity. With a
   * target the panel shows the contribution that reaches it, and `onUseContribution` puts that figure in the form.
   */
  termAccount?: { ratePercent: string; targetAmount: string; onUseContribution?: ((amount: string) => void) | undefined;
    onUseMaturity?: ((amount: string) => void) | undefined } | undefined;
  /**
   * Told where the server's answer stands (2026-10-20), for a screen that acts on it -- the agents' calculator offers
   * "Proceed" only once the server has priced what the customer would agree to, never on a refusal.
   */
  onQuoteState?: ((state: QuoteState) => void) | undefined;
  /**
   * Somebody not registered yet (2026-10-20, the agents' quotation calculator): the quotation PDF carries these
   * inputs instead of a party id, and is marked as a prospect's. Ignored once there is an applicant.
   */
  prospect?: ProspectInputs | undefined;
  inputs: QuotationInputs;
  premiumPayingTermMonths: string;
  commencementDate: string;
  /** The product's IFRS 17 portfolio, which is also the sharpest statement of how it is priced. */
  portfolioCode?: string | null | undefined;
  productVersionId: string;
  /** The proposer, whose name the downloadable quotation carries. Blank until one is chosen. */
  applicantPartyId?: string | undefined;
  /** A funeral plan's own inputs: the plan and the family entered above, which is what prices it. */
  funeral?:
    | {
        planCode: string;
        mainMember: { name: string; dateOfBirth: string | null } | null;
        dependants: FuneralDependantInput[];
      }
    | undefined;
}) {
  const basis: PricingBasis = pricingBasis(portfolioCode);
  // Where the answer stands, kept here as well as told to the screen: the PDF is offered only on a priced quotation
  // (2026-10-22). It used to be offered on a refused one too, under "the figures above" when there were none.
  const [quoteState, setQuoteState] = useState<QuoteState>('incomplete');
  const report = useCallback(
    (state: QuoteState) => {
      setQuoteState(state);
      onQuoteState?.(state);
    },
    [onQuoteState],
  );

  return (
    <Panel
      title="Quotation"
      // "Charged" is wrong for a savings product: that money is paid IN, and calling it a charge is the kind of
      // small lie a customer notices. Every basis ends on the same promise -- looking creates nothing.
      subtitle={
        basis === 'SAVINGS'
          ? 'What this money would earn on the chosen product. Nothing is created by looking at it.'
          : 'What this life would be charged on the chosen product. Nothing is created by looking at it.'
      }
    >
      {basis === 'PREMIUM' && (
        <PremiumQuotation
          inputs={inputs}
          premiumPayingTermMonths={premiumPayingTermMonths}
          commencementDate={commencementDate}
          report={report}
          derivationOpen={!forCustomer}
          echoInputs={!forCustomer}
          rich={forCustomer}
          onResult={onResult}
        />
      )}

      {basis === 'SAVINGS' && (
        <SavingsQuotation
          report={report}
          productId={inputs.productId}
          productVersionId={productVersionId}
          amount={inputs.sumAssuredAmount}
          currency={inputs.sumAssuredCurrency || 'TZS'}
          termMonths={inputs.policyTermMonths}
          commencementDate={commencementDate}
          termAccount={termAccount ? { ...termAccount, frequency: inputs.frequency ?? '' } : undefined}
          rich={forCustomer}
          onResult={onResult}
        />
      )}

      {basis === 'FUNERAL' && (
        <FuneralQuotation
          report={report}
          inputs={{
            productId: inputs.productId,
            productVersionId,
            planCode: funeral?.planCode ?? '',
            frequency: inputs.frequency,
            commencement: commencementDate,
            mainMember: funeral?.mainMember ?? null,
            dependants: funeral?.dependants ?? [],
          }}
          rich={forCustomer}
          onResult={onResult}
        />
      )}

      {basis === 'OWN_SECTION' && portfolioCode === 'IANN' && annuity && (
        <AnnuityQuotation
          report={report}
          onResult={onResult}
          productVersionId={productVersionId}
          purchasePrice={inputs.sumAssuredAmount}
          annuity={annuity}
        />
      )}

      {basis === 'OWN_SECTION' && !(portfolioCode === 'IANN' && annuity) && (
        <p className="px-4 pb-4 text-xs text-muted-foreground">
          {(portfolioCode && OWN_SECTION_BY_PORTFOLIO[portfolioCode]) ??
            'This product is priced by its own fields above on this form.'}
        </p>
      )}

      {basis === 'PER_SCHEME' && (
        <p className="px-4 pb-4 text-xs text-muted-foreground">
          This cover is priced per scheme, not per life: the premium is the scheme contract&apos;s rate applied to the
          lives or the balances it covers, so there is no individual premium to quote here.
        </p>
      )}

      {/* Only where this panel prices the product itself: an annuity or a unit-linked policy is priced by its own
          fields, and a scheme by its contract, so there is no quotation here to hand over. */}
      {(basis === 'PREMIUM' || basis === 'SAVINGS' || basis === 'FUNERAL') &&
        (() => {
          const request = quotationRequest(basis, {
            inputs,
            premiumPayingTermMonths,
            commencementDate,
            productVersionId,
            applicantPartyId,
            prospect,
            funeral,
            ratePercent: termAccount?.ratePercent,
          });
          return (
            <DownloadQuotationButton
              download={request && quoteState === 'quoted' ? () => downloadQuotation(request) : null}
              fileName={`quotation-${productVersionId.slice(0, 8)}.pdf`}
              {...(!applicantPartyId && prospect
                ? { description: 'The figures above, for the prospect to take away and study.' }
                : {})}
            />
          );
        })()}
    </Panel>
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
function PremiumQuotation({
  inputs,
  premiumPayingTermMonths,
  commencementDate,
  report,
  derivationOpen,
  echoInputs,
  rich = false,
  onResult,
}: {
  inputs: QuotationInputs;
  premiumPayingTermMonths: string;
  commencementDate: string;
  report?: ((state: QuoteState) => void) | undefined;
  rich?: boolean | undefined;
  onResult?: ((result: QuoteResult | null) => void) | undefined;
  derivationOpen: boolean;
  /**
   * The rows that only say back what the form says -- coverage term, premium-paying term, frequency, commencement. Kept
   * on a staff form, where the panel is read on its own; left out beside the calculator's inputs, where they are on
   * screen already and pushed the agent's next step below the fold.
   */
  echoInputs: boolean;
}) {
  const quotation = useQuotation(inputs);
  useReport(quotation.state, report);
  const quote = quotation.state === 'quoted' ? quotation.quote : null;
  const result = useMemo<QuoteResult | null>(
    () => (quote
      ? {
          kind: 'premium',
          quote,
          sumAssured: inputs.sumAssuredAmount,
          currency: inputs.sumAssuredCurrency || 'TZS',
          termMonths: inputs.policyTermMonths ? Number(inputs.policyTermMonths) : null,
        }
      : null),
    [quote, inputs.sumAssuredAmount, inputs.sumAssuredCurrency, inputs.policyTermMonths],
  );
  useResult(result, onResult);
  const cover = money(Number(inputs.sumAssuredAmount), inputs.sumAssuredCurrency || 'TZS');

  return (
    <>
      {quotation.state === 'incomplete' && (
        <p className="px-4 pb-4 text-xs text-muted-foreground">
          A quotation needs {quotation.missing.join(', ')}. Fill those in and it appears here.
        </p>
      )}

      {quotation.state === 'loading' && (
        <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>
      )}

      {/* The inline strip every other refusal on this panel uses -- the funeral one always did -- rather than the
          full-page error block, whose heading is the HTTP status ("Unprocessable Entity") in front of a customer. */}
      {quotation.state === 'refused' && (
        <div className="px-4 pb-4">
          <InlineError sticky error={toApiError(quotation.error)} lead="Not priced" />
          <p className="pt-2 text-xs text-muted-foreground">
            This product cannot price this person as entered; the message above says why. Check the date of birth,
            sex and smoker status first. If they are right, the product is not priced for this person.
          </p>
        </div>
      )}

      {quotation.state === 'quoted' && (
        <>
        {/* The ONE note kept: the frequency belongs to the premium figure, because the number means nothing
            without it. Every other row had a note explaining what its label already said. */}
        <Headline
          label="Premium"
          value={money(quotation.quote.instalmentAmount, quotation.quote.currency)}
          suffix={inputs.frequency ? PREMIUM_FREQUENCY_LABELS[inputs.frequency].toLowerCase() : undefined}
          caption={
            inputs.policyTermMonths
              ? `For ${cover} of cover over ${formatMonths(Number(inputs.policyTermMonths))}`
              : `For ${cover} of cover`
          }
          sentence={rich
            ? inputs.policyTermMonths
              ? `Pays ${cover} to the family if the life insured dies within ${formatMonths(Number(inputs.policyTermMonths))}.`
              : `Pays ${cover} to the family when the life insured dies.`
            : undefined}
        />
        <div className="divide-y divide-border">
          <Row
            label="Sum assured"
            value={money(Number(inputs.sumAssuredAmount), inputs.sumAssuredCurrency || 'TZS')}
          />
          {/* The server's own annual figure -- never an instalment multiplied here. */}
          <Row
            label="Annual equivalent"
            value={money(quotation.quote.annualAfterFrequencyLoading, quotation.quote.currency)}
          />
          {/* The question a customer actually asks. Also the server's figure, and ABSENT rather than guessed where
              it cannot be said -- no term, a single premium, or a term the frequency does not divide. */}
          <Row
            label={
              quotation.quote.instalmentsOverTerm
                ? `Total over ${formatMonths(Number(inputs.policyTermMonths))}`
                : 'Total over the term'
            }
            value={
              quotation.quote.totalOverTerm === null || quotation.quote.totalOverTerm === undefined
                ? 'Not determinable'
                : money(quotation.quote.totalOverTerm, quotation.quote.currency)
            }
            suffix={
              quotation.quote.instalmentsOverTerm
                ? `${quotation.quote.instalmentsOverTerm} payments`
                : undefined
            }
          />

          {echoInputs && (
            <>
              <Row
                label="Coverage term"
                value={inputs.policyTermMonths ? formatMonths(Number(inputs.policyTermMonths)) : 'None stated'}
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
              />
              <Row
                label="Payment frequency"
                value={inputs.frequency ? PREMIUM_FREQUENCY_LABELS[inputs.frequency] : '—'}
              />
              {commencementDate && <Row label="Proposed commencement" value={commencementDate} />}
            </>
          )}

          {/* The derivation, so an underwriter can see WHY the premium is what it is rather than trusting it. Folded
              away in front of a customer, who needs the price and not the rate table; one click opens it. */}
          <details className="group px-4 py-2" open={derivationOpen}>
            <summary className="cursor-pointer rounded-sm text-xs font-medium text-muted-foreground hover:text-foreground">
              How it was priced
            </summary>
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
            {/* Only where occupation actually priced it: with no box to fill in (2026-10-20) every quote "assumes" a
                class, and saying so on products that do not rate occupation would be noise about nothing. */}
            {quotation.assumedOccupationClass
              && (quotation.quote.appliedFactors ?? []).some((f) => f.factorType === 'OCCUPATION_CLASS') && (
              <p className="pt-1 text-xs text-muted-foreground">
                Priced as occupation class {DEFAULT_OCCUPATION_CLASS}: the applicant&apos;s record names none.
              </p>
            )}
          </details>
        </div>
        </>
      )}
    </>
  );
}

/**
 * A family priced on one funeral plan, in the Quotation panel with every other product's price (2026-10-16).
 *
 * <p>These figures used to sit under the family fields, which is the one place a reader looking for the price would
 * not think to look once every other product answered here. The request is the same one {@code FuneralLivesFields}
 * made; only where it is shown has changed.
 *
 * <p>A line per life, because a funeral premium IS per life: the plan's grid prices each role and age separately,
 * and a family total with no lines behind it cannot be checked.
 */
function FuneralQuotation({ inputs, report, rich = false, onResult }: {
  inputs: FuneralQuotationInputs;
  report?: ((state: QuoteState) => void) | undefined;
  rich?: boolean | undefined;
  onResult?: ((result: QuoteResult | null) => void) | undefined;
}) {
  const quotation = useFuneralQuotation(inputs);
  useReport(quotation.state, report);
  const answered = quotation.state === 'quoted' ? quotation.quote : null;
  const result = useMemo<QuoteResult | null>(() => (answered ? { kind: 'funeral', quote: answered } : null), [answered]);
  useResult(result, onResult);

  if (quotation.state === 'incomplete') {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        A quotation needs {quotation.missing.join(', ')}. Fill those in and it appears here.
      </p>
    );
  }
  if (quotation.state === 'loading') {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>;
  }
  if (quotation.state === 'refused') {
    return (
      <div className="px-4 pb-4">
        <InlineError sticky error={quotation.error} lead="This family cannot be covered as entered:" />
      </div>
    );
  }

  const quote = quotation.quote;
  return (
    <>
    <Headline
      label={`Each ${quote.frequency.toLowerCase()} payment`}
      value={tzs(quote.instalment)}
      valueLabel="Instalment"
      caption={`${quote.lines.length} ${quote.lines.length === 1 ? 'life' : 'lives'} covered`}
      sentence={rich
        ? `Covers ${quote.lines.length === 1 ? 'one person' : `${quote.lines.length} people`}: each is paid out on their death, up to the cover shown for them.`
        : undefined}
    />
    <div className="px-4 pb-4">
      <div className="overflow-x-auto rounded-md border border-border">
        <table className="w-full text-sm">
          <caption className="sr-only">Family quote</caption>
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="p-2">Life</th>
              <th className="p-2">Role</th>
              <th className="p-2">Age</th>
              <th className="p-2 text-right">Benefit</th>
              <th className="p-2 text-right">Yearly premium</th>
            </tr>
          </thead>
          <tbody>
            {quote.lines.map((line, i) => (
              <tr key={i} className="border-t border-border">
                <td className="p-2">{line.name}</td>
                <td className="p-2">{FUNERAL_ROLE_LABELS[line.role as FuneralRoleName]}</td>
                <td className="p-2">{line.age}</td>
                <td className="p-2 text-right whitespace-nowrap">{tzs(line.benefit)}</td>
                <td className="p-2 text-right whitespace-nowrap">{tzs(line.yearlyPremium)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t border-border font-medium">
              <td className="p-2" colSpan={4}>
                Family total a year
              </td>
              <td className="p-2 text-right whitespace-nowrap">{tzs(quote.totalYearlyPremium)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
    </>
  );
}

/**
 * A savings product quoted on its OWN basis: a deposit's maturity, or an account's rate (2026-10-16).
 *
 * <p>These products have no sum assured and no premium rate, so the form's amount field is the money paid IN. A
 * deposit answers with the rate for its band -- for the term, not for a year -- its interest and what it will be
 * worth on the day it matures. An account answers with the rate it earns, because there is no maturity to quote:
 * it runs until it is closed.
 */
function SavingsQuotation({
  productId,
  productVersionId,
  amount,
  currency,
  termMonths,
  commencementDate,
  report,
  termAccount,
  rich = false,
  onResult,
}: {
  productId: string;
  productVersionId: string;
  amount: string;
  currency: string;
  termMonths: string;
  commencementDate: string;
  report?: ((state: QuoteState) => void) | undefined;
  rich?: boolean | undefined;
  onResult?: ((result: QuoteResult | null) => void) | undefined;
  termAccount?:
    | { ratePercent: string; frequency: string; targetAmount: string; onUseContribution?: ((amount: string) => void) | undefined;
        onUseMaturity?: ((amount: string) => void) | undefined }
    | undefined;
}) {
  const quotation = useSavingsQuotation({
    productId,
    productVersionId,
    amount,
    currency,
    termMonths,
    asOf: commencementDate,
    termAccount,
  });
  // A deposit priced with a refusal attached -- a term or amount it does not take -- cannot be agreed to: the server
  // refuses to save it, so it is reported as refused, not quoted.
  useReport(
    quotation.state === 'quoted' && quotation.quote.refusal ? 'refused' : quotation.state,
    report,
  );
  const answered = quotation.state === 'quoted' && !quotation.quote.refusal ? quotation.quote : null;
  const result = useMemo<QuoteResult | null>(() => (answered ? { kind: 'savings', quote: answered } : null), [answered]);
  useResult(result, onResult);

  if (quotation.state === 'incomplete') {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        A quotation needs {quotation.missing.join(', ')}. Fill those in and it appears here.
      </p>
    );
  }
  if (quotation.state === 'loading') {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>;
  }
  if (quotation.state === 'refused') {
    return (
      <div className="px-4 pb-4">
        <InlineError sticky error={toApiError(quotation.error)} lead="Not priced" />
      </div>
    );
  }

  const q = quotation.quote;
  const deposit = q.kind === 'DEPOSIT';
  if (q.kind === 'TERM_ACCOUNT') {
    return <TermAccountQuotation quote={q} rich={rich} onUseContribution={termAccount?.onUseContribution} onUseMaturity={termAccount?.onUseMaturity} />;
  }

  // What the customer came to hear: a deposit's worth on the day it matures, an account's rate. Its row is then left
  // out below, so the figure is said once.
  const maturityLeads = deposit && !!q.maturityValue;
  const rateLeads = !deposit && !!q.ratePercent;
  return (
    <>
    {maturityLeads && (
      <Headline
        label="Value at maturity"
        value={formatMoney(q.maturityValue!)}
        caption={q.maturityDate ? `On ${q.maturityDate}` : undefined}
        sentence={rich && q.amount && q.interest
          ? `A deposit of ${formatMoney(q.amount)} earns ${formatMoney(q.interest)} over the term.`
          : undefined}
        split={rich && q.amount && q.interest
          ? [
              { label: 'Deposit', amount: q.amount, tone: 'paper' },
              { label: 'Interest', amount: q.interest, tone: 'teal' },
            ]
          : undefined}
      />
    )}
    {rateLeads && <Headline label="Interest rate" value={`${q.ratePercent}%`} suffix="a year" />}
    <div className="divide-y divide-border">
      <Row label={deposit ? 'Deposit' : 'Opening balance'} value={formatMoney(q.amount)} />
      {!rateLeads && (
        <Row
          label="Interest rate"
          value={q.ratePercent ? `${q.ratePercent}%` : '—'}
          // A deposit's rate is for the WHOLE term, not a year: 6% over 6 months is 6%, not 3%. Saying which is the
          // difference between a correct figure and one a customer will dispute.
          suffix={q.ratePercent ? (deposit ? 'for the term' : 'a year') : undefined}
        />
      )}
      {deposit && (
        <>
          {/* Red only when the amount or term named is actually REFUSED (q.refusal says why, below) -- not merely
              because no term has been chosen yet, which is the ordinary state while the form is still being filled
              in and reads the same "Not determinable" but is not a problem. */}
          <Row
            label="Interest"
            value={q.interest ? formatMoney(q.interest) : 'Not determinable'}
            danger={!q.interest && !!q.refusal}
          />
          {!maturityLeads && (
            <Row
              label="Value at maturity"
              value={q.maturityValue ? formatMoney(q.maturityValue) : 'Not determinable'}
              danger={!q.maturityValue && !!q.refusal}
            />
          )}
          <Row
            label="Term"
            value={termMonths ? formatMonths(Number(termMonths)) : 'Not chosen'}
            suffix={
              q.offeredTermMonths.length > 0
                ? `offered: ${q.offeredTermMonths.map((m) => formatMonths(m)).join(', ')}`
                : undefined
            }
          />
          {q.maturityDate && <Row label="Matures on" value={q.maturityDate} />}
          {q.smallestDeposit && <Row label="Smallest deposit taken" value={formatMoney(q.smallestDeposit)} />}
        </>
      )}
      {!deposit && (
        <>
          {q.minimumBalance && <Row label="Minimum balance" value={formatMoney(q.minimumBalance)} />}
          <div className="px-4 py-2">
            <p className="text-xs text-muted-foreground">
              An account has no premium and no maturity value: it is funded by deposits, earns interest on its
              balance at the declared rate, and runs until it is closed.
            </p>
          </div>
        </>
      )}
      {q.refusal && (
        <div className="px-4 py-2">
          <p className="text-xs text-muted-foreground">{q.refusal}</p>
        </div>
      )}
    </div>
    </>
  );
}

/**
 * A term account (2026-10-21), projected to maturity at the rate the case names: what is paid in, what it earns, and
 * what it pays out on the maturity date -- every figure the server's. Quoted for a target, it leads with the
 * contribution that reaches it, and offers to use that figure on the form.
 */
function TermAccountQuotation({
  quote: q,
  onUseContribution,
  onUseMaturity,
  rich = false,
}: {
  quote: SavingsQuoteView;
  rich?: boolean | undefined;
  onUseContribution?: ((amount: string) => void) | undefined;
  onUseMaturity?: ((amount: string) => void) | undefined;
}) {
  const t = q.termAccount;
  // Either way round (the user, 2026-10-22): quoted for a target, the contribution that reaches it goes into the form;
  // quoted for a contribution, what it pays at maturity does.
  const solved = t?.targetAmount && !q.refusal ? q.amount?.amount : undefined;
  const projected = t && !t.targetAmount && !q.refusal ? q.maturityValue?.amount : undefined;
  useEffect(() => {
    if (solved) onUseContribution?.(solved);
  }, [solved, onUseContribution]);
  useEffect(() => {
    if (projected) onUseMaturity?.(projected);
  }, [projected, onUseMaturity]);
  if (q.refusal || !t) {
    return (
      <div className="px-4 pb-4">
        <p role="alert" className="text-sm text-status-danger-fg">{q.refusal ?? 'This term account cannot be quoted as entered.'}</p>
        {t && (
          <p className="pt-1 text-xs text-muted-foreground">
            This product offers a rate between {t.minRatePercent}% and {t.maxRatePercent}% a year.
          </p>
        )}
      </div>
    );
  }
  const frequency = t.frequency ? PREMIUM_FREQUENCY_LABELS[t.frequency].toLowerCase() : undefined;
  // Quoted for a target, the contribution that reaches it is the answer; otherwise, what it pays out.
  const contributionLeads = !!t.targetAmount && !!q.amount;
  const payoutLeads = !contributionLeads && !!q.maturityValue;
  // For a customer: the answer in a sentence, and what the payout is made of -- the server's three totals.
  const years = q.termMonths != null ? formatMonths(q.termMonths) : 'the term';
  // One short line that says only what the big figure does not: the other half of the answer. The breakdown below it
  // carries the amounts, so the line never repeats them (the user, 2026-10-23: "hard to read").
  const sentence = !rich || !q.amount || !q.maturityValue
    ? undefined
    : contributionLeads
      ? `Pays out ${formatMoney(q.maturityValue)} at maturity.`
      : `From ${formatMoney(q.amount)}${frequency ? ` ${frequency}` : ''} for ${years}.`;
  const split = rich && t.paidIn && q.interest
    ? [
        { label: 'Paid in', amount: t.paidIn, tone: 'paper' as const },
        { label: 'Interest earned', amount: q.interest, tone: 'teal' as const },
        ...(t.charges && Number(t.charges.amount) > 0 ? [{ label: 'Charges', amount: t.charges, tone: 'amber' as const }] : []),
      ]
    : undefined;
  return (
    <>
    {contributionLeads && (
      <Headline
        label="Contribution needed"
        value={formatMoney(q.amount!)}
        suffix={frequency}
        caption={onUseContribution && !rich ? 'Filled in as the contribution above.' : undefined}
        sentence={sentence}
        split={split}
      />
    )}
    {payoutLeads && (
      <Headline
        label="Paid out at maturity"
        value={formatMoney(q.maturityValue!)}
        caption={q.maturityDate && !rich ? `On ${q.maturityDate}` : undefined}
        sentence={sentence}
        split={split}
      />
    )}
    <div className="divide-y divide-border">
      {t.targetAmount && <Row label="Wanted at maturity" value={formatMoney(t.targetAmount)} />}
      {t.targetAmount && !q.amount && <Row label="Contribution needed" value="—" />}
      {!t.targetAmount && q.amount && <Row label="Contribution" value={formatMoney(q.amount)} suffix={frequency} />}
      <Row label="Interest rate" value={`${q.ratePercent}%`} suffix="a year, fixed for the term" />
      <Row label="Paid in" value={t.paidIn ? formatMoney(t.paidIn) : '—'} suffix={`${t.contributions} payments`} />
      {t.charges && Number(t.charges.amount) > 0 && <Row label="Charges over the term" value={formatMoney(t.charges)} />}
      <Row label="Interest to maturity" value={q.interest ? formatMoney(q.interest) : '—'} />
      {!payoutLeads && (
        <Row label="Paid out at maturity" value={q.maturityValue ? formatMoney(q.maturityValue) : '—'} />
      )}
      {q.termMonths != null && <Row label="Term" value={formatMonths(q.termMonths)} />}
      {q.maturityDate && <Row label="Matures on" value={formatDate(q.maturityDate)} />}
      <div className="px-4 py-2">
        <p className="text-xs text-muted-foreground">
          Nothing can be withdrawn or surrendered before it matures; then the whole balance is paid out. The figure at
          maturity assumes every contribution is paid on its due date.
        </p>
      </div>
    </div>
    </>
  );
}

const ANNUITY_FREQUENCY: Record<string, string> = {
  MONTHLY: 'a month',
  QUARTERLY: 'a quarter',
  SEMI_ANNUAL: 'every six months',
  ANNUAL: 'a year',
};

/**
 * An immediate annuity on the calculator (2026-10-23): the income a purchase price buys, priced by the version's own
 * grid for a client's recorded age and sex, or a prospect's as stated. A quote, not a promise -- the income is locked
 * at the rate in force the day the price is paid.
 */
function AnnuityQuotation({ productVersionId, purchasePrice, annuity, report, onResult }: {
  productVersionId: string;
  purchasePrice: string;
  annuity: { formCode: string; frequency: string; annuitantPartyId: string; dateOfBirth: string | null; sex: string | null };
  report?: ((state: QuoteState) => void) | undefined;
  onResult?: ((result: QuoteResult | null) => void) | undefined;
}) {
  const price = Number(purchasePrice);
  const missing: string[] = [];
  if (!(price > 0)) missing.push('a purchase price in digits');
  if (!annuity.formCode) missing.push('an annuity form');
  if (!annuity.frequency) missing.push('how often the income is paid');
  if (!annuity.annuitantPartyId && !annuity.dateOfBirth) missing.push("the annuitant's date of birth");
  const key = missing.length === 0
    ? [productVersionId, annuity.formCode, annuity.frequency, purchasePrice, annuity.annuitantPartyId,
        annuity.dateOfBirth ?? '', annuity.sex ?? ''].join('|')
    : '';

  const [loaded, setLoaded] = useState<{ key: string; quote?: AnnuityQuoteView; error?: unknown } | null>(null);
  useEffect(() => {
    if (!key) return undefined;
    let live = true;
    // Debounced: the price is typed a digit at a time, and each keystroke is not a question.
    const timer = setTimeout(() => {
      quoteAnnuity({
        productVersionId,
        formCode: annuity.formCode,
        frequency: annuity.frequency as AnnuityQuoteView['frequency'],
        purchasePrice: price,
        ...(annuity.annuitantPartyId
          ? { annuitantPartyId: annuity.annuitantPartyId }
          : { annuitantDateOfBirth: annuity.dateOfBirth, annuitantSex: (annuity.sex || null) as 'FEMALE' | 'MALE' | null }),
      }).then(
        (quote) => live && setLoaded({ key, quote }),
        (error: unknown) => live && setLoaded({ key, error }),
      );
    }, 400);
    return () => {
      live = false;
      clearTimeout(timer);
    };
    // key carries every input the quote depends on
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const current = loaded && loaded.key === key ? loaded : null;
  const state: QuoteState = !key ? 'incomplete' : !current ? 'loading' : current.error !== undefined ? 'refused' : 'quoted';
  useReport(state, report);
  const answered = state === 'quoted' ? current?.quote ?? null : null;
  const result = useMemo<QuoteResult | null>(
    () => (answered ? { kind: 'annuity', quote: answered, purchasePrice } : null),
    [answered, purchasePrice],
  );
  useResult(result, onResult);

  if (state === 'incomplete') {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        A quotation needs {missing.join(', ')}. Fill those in and it appears here.
      </p>
    );
  }
  if (state === 'loading') return <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>;
  if (state === 'refused' || !answered) {
    return (
      <div className="px-4 pb-4">
        <InlineError sticky error={toApiError(current?.error)} lead="Not priced" />
      </div>
    );
  }
  const q = answered;
  const every = ANNUITY_FREQUENCY[q.frequency] ?? q.frequency.toLowerCase();
  const breakEven = q.schedule.find((s) => s.year === q.breakEvenYear);
  return (
    <>
      <Headline
        label="Income paid"
        value={formatMoney(q.instalment)}
        suffix={every}
        caption={`For life, from age ${q.annuitantAge}`}
        sentence={`A purchase price of ${formatMoney(q.purchasePrice ?? { amount: purchasePrice, currencyCode: 'TZS' })} pays `
          + `${formatMoney(q.instalment)} ${every} for as long as the annuitant lives: ${formatMoney(q.annualIncome)} a year.`
          + (breakEven ? ` By age ${breakEven.age} it has paid back more than its price.` : '')}
      />
      <div className="divide-y divide-border">
        <Row label="Purchase price" value={formatMoney(q.purchasePrice ?? { amount: purchasePrice, currencyCode: 'TZS' })} />
        <Row label="Income a year" value={formatMoney(q.annualIncome)} />
        <Row label="Rate" value={`${q.annualRatePerMille} per 1,000`} suffix={`age ${q.annuitantAge}${q.rateSex ? `, ${q.rateSex.toLowerCase()}` : ''}`} />
        {breakEven && <Row label="Paid back by" value={`Year ${breakEven.year}`} suffix={`age ${breakEven.age}`} />}
        <div className="px-4 py-2">
          <p className="text-xs text-muted-foreground">
            The income is locked at the rate in force on the day the price is paid. It cannot be surrendered once the
            free-look period has passed.
          </p>
        </div>
      </div>
    </>
  );
}
