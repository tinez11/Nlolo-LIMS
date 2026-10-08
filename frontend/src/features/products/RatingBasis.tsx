import type { ReactNode } from 'react';
import type { BaseRate, VersionRatingView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { humanizeStatus } from '@/lib/status';
import { isInitialLoad } from '@/store/createResourceSlice';
import type { selectVersionRating } from '@/store/productStore';
import { pricedOtherwise } from './versionText';

/**
 * Rates are `type: number` on the wire (BigDecimal server-side), so they are
 * shown exactly as sent -- `maximumFractionDigits` is high enough not to round,
 * and no fixed decimal count is imposed, because padding 2.5 to "2.500" would
 * assert a precision the actuary did not give.
 */
/**
 * Rates render at a FIXED four decimals, and that is the whole point.
 *
 * This was `maximumFractionDigits: 6` with no minimum, so every cell printed
 * however many decimals it happened to need: 0.62, 1.488, 0.837, 2.0088, 2.1,
 * 5.04. The decimal points did not line up in any two adjacent rows, which
 * makes a rate table impossible to read down -- and reading it down is the only
 * reason it is a table. The Tabular Rule exists for exactly this column.
 *
 * Four, not two and not six, because four is the column's real precision:
 * `product.base_rate_table.rate_per_mille` is `numeric(10,4)`. Trailing zeros
 * are therefore true rather than decorative -- 0.6200 says the stored value has
 * four decimals and two of them are zero, where 0.62 leaves a reader guessing
 * whether the rest was rounded away.
 */
const RATE = new Intl.NumberFormat(undefined, {
  minimumFractionDigits: 4,
  maximumFractionDigits: 4,
});

/** Multipliers carry their own precision; three decimals separates 1.075 from 1.08. */
const MULTIPLIER = new Intl.NumberFormat(undefined, {
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
});

/**
 * Sum assured bounds, grouped. A band running to 5000000 is a number a reader has to count
 * digits on; 5,000,000 is one they can read. Whole amounts only -- the bounds are
 * `numeric(18,2)` but a rating band is written in round money, and the cents would be noise.
 */
const AMOUNT = new Intl.NumberFormat(undefined, {
  maximumFractionDigits: 0,
});

/**
 * Unlike a rating band, a flat benefit is a sum somebody is paid, so its cents are not noise.
 */
const BENEFIT_AMOUNT = new Intl.NumberFormat(undefined, {
  maximumFractionDigits: 2,
});

/**
 * What a benefit pays, as an amount rather than as the name of a mechanism.
 *
 * The method alone said "Percentage of sum assured" and stopped there -- the one number a
 * reviewer opens this panel for was on the wire and not on the screen. This is the amount a
 * claim against the benefit is settled at, so it reads as the answer.
 *
 * No currency on the flat amount: a version carries none. The sum is paid in the currency of
 * the policy it is claimed on, and printing the product's default here would assert a currency
 * this row does not have.
 */
function benefitAmount(benefit: NonNullable<VersionRatingView['benefitSchedule']>[number]): string {
  switch (benefit.calculationMethod) {
    case 'SUM_ASSURED':
      return 'Full sum assured';
    case 'PERCENTAGE_OF_SUM_ASSURED':
      return benefit.percent == null ? '—' : `${BENEFIT_AMOUNT.format(benefit.percent)}% of sum assured`;
    case 'FLAT_AMOUNT':
      return benefit.flatAmount == null ? '—' : `Flat ${BENEFIT_AMOUNT.format(benefit.flatAmount)}`;
    default:
      // Versions published before V13 normalised to SUM_ASSURED, so this is unreachable
      // through the platform -- it exists because the generated type makes the field optional.
      return benefit.calculationMethod ? humanizeStatus(benefit.calculationMethod) : '—';
  }
}

const BASE_RATE_COLUMNS: Column<BaseRate>[] = [
  {
    key: 'age',
    header: 'Age',
    // ageTo is INCLUSIVE (see BaseRate in openapi-product.yaml). Rendered as a
    // closed interval so nobody reads "18-25" as excluding 25.
    render: (r) => `${r.ageFrom}–${r.ageTo}`,
  },
  // Both of these were humanised differently -- `sex` was printed raw, so the
  // table read "FEMALE" beside "non smoker" in adjacent columns. One humaniser
  // for both, and it is the one the rest of the console already uses.
  { key: 'sex', header: 'Sex', render: (r) => humanizeStatus(r.sex) },
  {
    key: 'smokerStatus',
    header: 'Smoker',
    render: (r) => humanizeStatus(r.smokerStatus),
  },
  {
    key: 'ratePerMille',
    header: 'Rate / 1,000',
    align: 'right',
    render: (r) => RATE.format(r.ratePerMille),
  },
];

type RatingResource = ReturnType<ReturnType<typeof selectVersionRating>>;
type RatingFactorRow = NonNullable<VersionRatingView['ratingFactors']>[number];

export function RatingBasis({
  rating,
  versionId,
  onRetry,
  category,
}: {
  rating: RatingResource;
  versionId: string | null;
  onRetry: () => void;
  category?: string | null | undefined;
}) {
  const otherwise = pricedOtherwise(category);
  // No versionId means the snapshot has not resolved one; the panel above it is
  // already reporting why, so this says nothing rather than showing a spinner
  // that would never stop.
  if (!versionId) {
    return (
      <p className="px-4 pb-4 pt-3 text-xs text-muted-foreground">
        No version is active today, so there is no rating basis to read.
      </p>
    );
  }

  if (isInitialLoad(rating)) {
    // Was a bare <p>Loading…</p>, which a screen reader never announced -- there
    // was no live region, so the panel simply went quiet and then had content.
    // `LoadingBlock` is the console's shared surface and carries role="status".
    return <LoadingBlock label="Loading rating basis" />;
  }

  if (rating.status === 'error' && rating.error && rating.data === null) {
    return <ErrorPanel error={rating.error} onRetry={onRetry} />;
  }

  if (!rating.data) return null;

  const baseRates = rating.data.baseRates ?? [];
  const factors = rating.data.ratingFactors ?? [];
  const benefits = rating.data.benefitSchedule ?? [];

  // What paying in instalments costs. Part of the rating basis an actuary reviews, so it reads
  // here beside the multipliers rather than only on a quote. Zero rows are dropped: a loading of
  // nothing is what every version published before V11 carries, and printing "+ 0%" twice would
  // dress that up as an answer.
  const loadingRows = (
    [
      ['Monthly', rating.data.frequencyLoading?.monthlyPercent],
      ['Quarterly', rating.data.frequencyLoading?.quarterlyPercent],
    ] as const
  )
    .filter(([, percent]) => Number(percent ?? 0) > 0)
    .map(([label, percent]) => ({
      key: label,
      label,
      note: null,
      value: `+ ${percent}%`,
    }));

  /*
    Bounded sections first, the unbounded grid last.

    The rate table is five bands wide by sex by smoker status -- twenty rows on
    this product and it grows with every band an actuary adds. It used to lead
    this panel, which pushed the multipliers and the benefit schedule (eight
    lines between them) so far below the fold that a reader scrolling for them
    passed twenty rows of rate cells first. That is DetailLayout's own rule --
    bounded panels before unbounded ones -- applied inside a panel.
  */
  return (
    <div className="pb-4">
      {otherwise ? (
        <p className="max-w-prose px-4 pt-3 text-xs text-muted-foreground">{otherwise}</p>
      ) : (
        <>
          <FactorSection
            title="Rating multipliers"
            empty="No multipliers on this version."
            rows={factors.map((f) => ({
              key: `${f.factorType}-${f.band}`,
              label: factorLabel(f),
              note: [f.band, factorRange(f)].filter(Boolean).join(' · ') || null,
              value: f.multiplier == null ? '—' : `× ${MULTIPLIER.format(f.multiplier)}`,
            }))}
          />

          <FactorSection
            title="Benefit schedule"
            empty="No benefits on this version."
            rows={benefits.map((b) => ({
              key: `${b.benefitType}-${b.calculationMethod}`,
              label: b.benefitType ? humanizeStatus(b.benefitType) : '—',
              note: null,
              value: benefitAmount(b),
            }))}
          />
        </>
      )}

      {/*
        Shown only when something is actually loaded. Two rows reading "+ 0%" would take up
        the same space as a real answer while saying nothing, and every version published
        before V11 is unloaded -- so on most products this section is simply absent, which is
        the honest rendering of "this product charges every frequency the same".
      */}
      {loadingRows.length > 0 && (
        <FactorSection
          title="Instalment loading"
          empty="This version charges every frequency the same."
          rows={loadingRows}
        />
      )}

      {/*
        Rendered ALWAYS, unlike the instalment loading above. An absent filing is a fact worth
        stating rather than a blank to skip past: it means the version predates the requirement,
        and the empty copy says so instead of leaving a reader to guess.
      */}
      <FactorSection
        title="TIRA filing"
        empty="No filing on record — this version was published before filings were required."
        rows={
          rating.data.tiraFiling
            ? [
                {
                  key: 'reference',
                  label: 'Reference',
                  note: null,
                  value: rating.data.tiraFiling.reference ?? '—',
                },
                {
                  key: 'approved',
                  label: 'Approved',
                  note: null,
                  value: rating.data.tiraFiling.approvalDate ?? '—',
                },
              ]
            : []
        }
      />

      {!otherwise && <section>
        <SectionHeading>Base rates</SectionHeading>
        {baseRates.length === 0 ? (
          // NOT an empty table. Every version published before M13 has no rate
          // table at all, which is a different fact from "the table is empty" --
          // it means no premium can be quoted for this product, and a bare
          // "No rows" would leave a reader to guess that.
          //
          // `max-w-prose` because this is the one paragraph of real prose in the
          // panel, and at the panel's full width it set a ~140-character measure.
          <p className="max-w-prose px-4 text-xs text-muted-foreground">
            This version is <strong className="font-medium text-foreground">unpriced</strong>. It was
            published without a base rate table, so nothing on this platform can quote a premium for
            it. A rate table has to be supplied at publish time, and this version had none.
          </p>
        ) : (
          <div className="border-t border-border">
            <DataTable
              columns={BASE_RATE_COLUMNS}
              rows={baseRates}
              rowKey={(r) => `${r.ageFrom}-${r.ageTo}-${r.sex}-${r.smokerStatus}`}
              caption="Base rate table"
            />
          </div>
        )}
      </section>}
    </div>
  );
}

/**
 * The Eyebrow tier, to spec: 11px, weight 500, +0.03em, Subtle Ink.
 *
 * These were 12px/600 in Muted Ink, which put them a half-step under the panel's
 * own title and directly above a table header row set in the same grey -- so
 * "BASE RATES" and "Age / Sex / Smoker" competed instead of nesting. `pt-5 pb-2`
 * rather than the old `pt-3 mt-1`: more room above a heading than below it, so
 * the heading groups with what follows it rather than floating between sections.
 */
export function SectionHeading({ children }: { children: ReactNode }) {
  return (
    <h3 className="px-4 pt-5 pb-2 text-xs font-medium tracking-[0.03em] text-subtle-foreground uppercase">
      {children}
    </h3>
  );
}

/**
 * The band is a LABEL the actuary typed; on an AGE row it is the age bounds the platform
 * actually resolves against, and until product V5 those did not exist at all -- which is
 * exactly how age went unrated here. Publishing now requires them, so show them: a screen
 * that renders only the band cannot tell a reader whether the range behind it is right,
 * or even present.
 *
 * Two bugs lived in the previous version of this, both visible on screen:
 *
 * 1. It guarded on `=== undefined`, but `openapi-product.yaml` declares these as
 *    `type: [integer, "null"]`. The wire sends null, the guard missed it, and the
 *    console printed "(ages null–null)" -- which CSS `capitalize` then dressed up as
 *    "(Ages Null–Null)", so it read like a deliberate label rather than absent data.
 *    An absent value is an em dash on this platform, never a literal.
 * 2. It appended the age range to EVERY factor type. A sum-assured band was rendered
 *    with an age range beside it, which is not a fact about that row -- the bounds
 *    only mean anything on an AGE factor.
 *
 * SUM_ASSURED_BAND now reads the same way, and for the identical reason one factor type
 * over (product V9): its band text was matched by exact string against LOW/MEDIUM/HIGH
 * hardcoded in underwriting, so a real product's band matched nothing and the multiplier
 * beside it reached no premium. The bounds are what it rates on now, so the bounds are what
 * a reviewer has to be able to see -- including their absence, which on such a row means it
 * still rates nobody.
 */
function factorRange(factor: RatingFactorRow): string | null {
  if (factor.factorType === 'AGE') {
    // `!= null` on purpose: catches both null and undefined, which is the whole fix.
    if (factor.ageFrom == null || factor.ageTo == null) {
      // An AGE factor with no bounds is the state that let age go unrated before V5.
      // Saying so is more useful than saying nothing.
      return 'no age bounds';
    }
    return `ages ${factor.ageFrom}–${factor.ageTo}`;
  }
  if (factor.factorType === 'SUM_ASSURED_BAND') {
    if (factor.sumAssuredFrom == null || factor.sumAssuredTo == null) {
      // A version published before V9, or a neutral row. Either way this row resolves for
      // no sum assured at all, which is worth saying on a screen whose whole job is to show
      // an actuary what their product actually does.
      return 'no amount bounds';
    }
    return `${AMOUNT.format(factor.sumAssuredFrom)}–${AMOUNT.format(factor.sumAssuredTo)}`;
  }
  return null;
}

function factorLabel(factor: RatingFactorRow): string {
  return factor.factorType ? humanizeStatus(factor.factorType) : '—';
}

/**
 * A `<dl>` of label/value pairs, not a `<ul>` of two spans.
 *
 * Three things were wrong with the list this replaces, and all three showed:
 *
 * 1. `justify-between` across the full work column put "Age · 18-30" at the left
 *    edge and "× 1" a thousand pixels away at the right, with nothing between
 *    them. Nobody can carry a value that far. The pairs are capped at 28rem now,
 *    so a label and its multiplier stay in one glance -- the same reason
 *    DetailLayout's Field rows live in a 320px rail.
 * 2. The LABEL was full-strength ink and the VALUE was muted, which is the
 *    emphasis backwards: the multiplier is the answer somebody opened this panel
 *    to read. Value takes the content tier, label takes Muted Ink.
 * 3. The value was `font-mono`. A multiplier is a figure, not a machine
 *    identifier, and the global tabular figures already make the column align --
 *    mono here was costume. Mono on this platform is for trace ids.
 *
 * `capitalize` is also gone: it Title-Cased every word, so `SUM_ASSURED_BAND`
 * arrived as "Sum Assured Band" in a console that writes sentence case
 * everywhere else. `humanizeStatus` gives "Sum assured band".
 */
export function FactorSection({
  title,
  empty,
  rows,
}: {
  title: string;
  empty: string;
  rows: { key: string; label: string; note: string | null; value: string }[];
}) {
  return (
    <section>
      <SectionHeading>{title}</SectionHeading>
      {rows.length === 0 ? (
        <p className="px-4 text-xs text-muted-foreground">{empty}</p>
      ) : (
        <dl className="max-w-md px-4">
          {rows.map((row) => (
            <div
              key={row.key}
              className="flex items-baseline justify-between gap-4 border-b border-border py-2 last:border-0"
            >
              <dt className="min-w-0 text-xs text-muted-foreground">
                {row.label}
                {row.note && (
                  // The band the actuary typed, and the age bounds where they
                  // apply. Secondary to the factor type, so it sits under it
                  // rather than competing on the same line.
                  <span className="block text-xs text-subtle-foreground">{row.note}</span>
                )}
              </dt>
              <dd className="shrink-0 text-sm">{row.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}


