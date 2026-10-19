import { Panel } from '@/components/Panel';
import { ErrorPanel } from '@/components/states';
import { toApiError } from '@/lib/apiError';
import { formatMonths } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { PREMIUM_FREQUENCY_LABELS } from '@/api/types';
import { DEFAULT_OCCUPATION_CLASS, useQuotation, type QuotationInputs } from './useQuotation';
import { useSavingsQuotation } from './useSavingsQuotation';
import { DownloadQuotationButton } from './DownloadQuotationButton';
import type { QuotationDocumentRequest } from '@/api/types';
import { useFuneralQuotation, type FuneralDependantInput, type FuneralQuotationInputs } from './useFuneralQuotation';
import { InlineError } from '@/components/InlineError';
import { FUNERAL_ROLE_LABELS, type FuneralRoleName } from '@/features/products/funeralSchema';

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

function Row({ label, value, suffix }: { label: string; value: string; suffix?: string | undefined }) {
  return (
    <div className="flex items-baseline gap-4 px-4 py-2">
      <span className="min-w-0 flex-1 text-sm">{label}</span>
      <span className="shrink-0 text-right text-sm whitespace-nowrap">
        {value}
        {suffix && <span className="pl-1.5 text-xs text-muted-foreground">{suffix}</span>}
      </span>
    </div>
  );
}

/**
 * How a product is priced -- which decides what this panel can even ask for.
 *
 * <p>The premium-quote endpoint prices a sum assured off a base rate table, and that is ALL it can price. Nine of
 * the platform's fourteen portfolios are not priced that way at all, so every one of them used to answer the panel
 * with a red 422 ("carries no base rate table, so it cannot be priced") on a product working exactly as designed.
 * A quotation panel that only ever works for term and endowment is not a quotation panel.
 */
type PricingBasis = 'PREMIUM' | 'SAVINGS' | 'FUNERAL' | 'OWN_SECTION' | 'PER_SCHEME';

const BASIS_BY_PORTFOLIO: Record<string, PricingBasis> = {
  // Priced from a sum assured and the version's base rate table.
  TERM: 'PREMIUM',
  WL: 'PREMIUM',
  END: 'PREMIUM',
  MB: 'PREMIUM',
  PAR: 'PREMIUM',
  // Priced from money paid in and a rate: a deposit's grid, or an account's declared rate.
  SAV: 'SAVINGS',
  DEP: 'SAVINGS',
  // Priced from the plan's own premium grid, a line per life -- quoted here, from the family entered above.
  FUN: 'FUNERAL',
  // Priced by its own fields on THIS form, which ask for what its own quoter needs -- an annuity form, a fund
  // split. Repeating their figures here would be two prices for one thing on one screen.
  IANN: 'OWN_SECTION',
  DANN: 'OWN_SECTION',
  PEN: 'OWN_SECTION',
  ULIP: 'OWN_SECTION',
  // Priced per scheme, not per life: the rate belongs to the scheme contract.
  CRL: 'PER_SCHEME',
  GRPL: 'PER_SCHEME',
};

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
}: {
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
  // An unknown or absent portfolio keeps the original behaviour: ask for a premium and show whatever comes back.
  const basis: PricingBasis = (portfolioCode && BASIS_BY_PORTFOLIO[portfolioCode]) || 'PREMIUM';

  return (
    <Panel
      title="Quotation"
      // "Charged" is wrong for a savings product: that money is paid IN, and calling it a charge is the kind of
      // small lie a customer notices. Every basis ends on the same promise -- looking creates nothing.
      subtitle={
        basis === 'SAVINGS'
          ? 'What this money would earn on the product above. Nothing is created by looking at it.'
          : 'What this life would be charged on the product above. Nothing is created by looking at it.'
      }
    >
      {basis === 'PREMIUM' && (
        <PremiumQuotation
          inputs={inputs}
          premiumPayingTermMonths={premiumPayingTermMonths}
          commencementDate={commencementDate}
        />
      )}

      {basis === 'SAVINGS' && (
        <SavingsQuotation
          productId={inputs.productId}
          productVersionId={productVersionId}
          amount={inputs.sumAssuredAmount}
          currency={inputs.sumAssuredCurrency || 'TZS'}
          termMonths={inputs.policyTermMonths}
          commencementDate={commencementDate}
        />
      )}

      {basis === 'FUNERAL' && (
        <FuneralQuotation
          inputs={{
            productId: inputs.productId,
            productVersionId,
            planCode: funeral?.planCode ?? '',
            frequency: inputs.frequency,
            commencement: commencementDate,
            mainMember: funeral?.mainMember ?? null,
            dependants: funeral?.dependants ?? [],
          }}
        />
      )}

      {basis === 'OWN_SECTION' && (
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
      {(basis === 'PREMIUM' || basis === 'SAVINGS' || basis === 'FUNERAL') && (
        <DownloadQuotationButton
          request={quotationRequest(basis, {
            inputs,
            premiumPayingTermMonths,
            commencementDate,
            productVersionId,
            applicantPartyId,
            funeral,
          })}
        />
      )}
    </Panel>
  );
}

/**
 * The document's INPUTS, as the form holds them -- never a figure from the screen.
 *
 * <p>Null until there is enough to ask: a product, an applicant, and what that basis cannot be quoted without. The
 * server re-checks all of it; this only keeps the button from sending a request that is certain to be refused.
 */
function quotationRequest(
  basis: PricingBasis,
  form: {
    inputs: QuotationInputs;
    premiumPayingTermMonths: string;
    commencementDate: string;
    productVersionId: string;
    applicantPartyId: string;
    funeral?: { planCode: string; mainMember: { name: string; dateOfBirth: string | null } | null; dependants: FuneralDependantInput[] } | undefined;
  },
): QuotationDocumentRequest | null {
  const { inputs, productVersionId, applicantPartyId } = form;
  if (!inputs.productId || !productVersionId || !applicantPartyId) return null;
  const base = {
    productId: inputs.productId,
    productVersionId,
    applicantPartyId,
    currency: inputs.sumAssuredCurrency || 'TZS',
    commencement: form.commencementDate || null,
  };

  if (basis === 'FUNERAL') {
    const main = form.funeral?.mainMember;
    if (!form.funeral?.planCode || !inputs.frequency || !main?.dateOfBirth) return null;
    return {
      ...base,
      frequency: inputs.frequency,
      funeralPlanCode: form.funeral.planCode,
      lives: [
        { role: 'MAIN_MEMBER', name: main.name, dateOfBirth: main.dateOfBirth, student: false },
        ...form.funeral.dependants.map((d) => ({
          role: d.role as 'SPOUSE' | 'CHILD' | 'PARENT' | 'EXTENDED',
          name: d.fullName,
          dateOfBirth: d.dateOfBirth || null,
          student: d.student,
        })),
      ],
    };
  }

  if (!inputs.sumAssuredAmount || Number(inputs.sumAssuredAmount) <= 0) return null;
  if (basis === 'PREMIUM' && !inputs.frequency) return null;
  return {
    ...base,
    sumAssured: Number(inputs.sumAssuredAmount),
    frequency: inputs.frequency || null,
    policyTermMonths: inputs.policyTermMonths ? Number(inputs.policyTermMonths) : null,
    premiumPayingTermMonths: form.premiumPayingTermMonths ? Number(form.premiumPayingTermMonths) : null,
  };
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
}: {
  inputs: QuotationInputs;
  premiumPayingTermMonths: string;
  commencementDate: string;
}) {
  const quotation = useQuotation(inputs);

  return (
    <>
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
          {/* The ONE note kept: the frequency belongs to the premium figure, because the number means nothing
              without it. Every other row had a note explaining what its label already said. */}
          <Row
            label="Premium"
            value={money(quotation.quote.instalmentAmount, quotation.quote.currency)}
            suffix={inputs.frequency ? PREMIUM_FREQUENCY_LABELS[inputs.frequency].toLowerCase() : undefined}
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
          </div>
        </div>
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
function FuneralQuotation({ inputs }: { inputs: FuneralQuotationInputs }) {
  const quotation = useFuneralQuotation(inputs);

  if (quotation.state === 'incomplete') {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        A quotation needs {quotation.missing.join(', ')}. Fill those in above and it appears here.
      </p>
    );
  }
  if (quotation.state === 'loading') {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>;
  }
  if (quotation.state === 'refused') {
    return (
      <div className="px-4 pb-4">
        <InlineError error={quotation.error} lead="This family cannot be covered as entered:" />
      </div>
    );
  }

  const quote = quotation.quote;
  return (
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
            <tr className="font-medium">
              <td className="p-2" colSpan={4}>
                Each {quote.frequency.toLowerCase()} payment
              </td>
              <td className="p-2 text-right whitespace-nowrap" aria-label="Instalment">
                {tzs(quote.instalment)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
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
}: {
  productId: string;
  productVersionId: string;
  amount: string;
  currency: string;
  termMonths: string;
  commencementDate: string;
}) {
  const quotation = useSavingsQuotation({
    productId,
    productVersionId,
    amount,
    currency,
    termMonths,
    asOf: commencementDate,
  });

  if (quotation.state === 'incomplete') {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        A quotation needs {quotation.missing.join(', ')}. Fill those in above and it appears here.
      </p>
    );
  }
  if (quotation.state === 'loading') {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Pricing…</p>;
  }
  if (quotation.state === 'refused') {
    return (
      <div className="px-4 pb-4">
        <ErrorPanel error={toApiError(quotation.error)} />
      </div>
    );
  }

  const q = quotation.quote;
  const deposit = q.kind === 'DEPOSIT';

  return (
    <div className="divide-y divide-border">
      <Row label={deposit ? 'Deposit' : 'Opening balance'} value={formatMoney(q.amount)} />
      <Row
        label={deposit ? 'Interest rate' : 'Interest rate'}
        value={q.ratePercent ? `${q.ratePercent}%` : '—'}
        // A deposit's rate is for the WHOLE term, not a year: 6% over 6 months is 6%, not 3%. Saying which is the
        // difference between a correct figure and one a customer will dispute.
        suffix={q.ratePercent ? (deposit ? 'for the term' : 'a year') : undefined}
      />
      {deposit && (
        <>
          <Row label="Interest" value={q.interest ? formatMoney(q.interest) : 'Not determinable'} />
          <Row
            label="Value at maturity"
            value={q.maturityValue ? formatMoney(q.maturityValue) : 'Not determinable'}
          />
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
  );
}
