import { useEffect, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { RateDeclarationsPanel } from './RateDeclarationsPanel';
import { showsRates } from './rateDeclarationForm';
import type { BaseRate, VersionRatingView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { humanizeStatus } from '@/lib/status';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectProductSnapshot,
  selectVersionRating,
  useProductStore,
} from '@/store/productStore';
import { PublishVersionForm } from './PublishVersionForm';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';

/**
 * The "acts" half of drawer-previews-page-acts. Unlike Policies/Claims there is
 * no `GET /products/{id}` on this platform at all -- the product record comes
 * from the already-loaded list, found by id, so this page ensures the list is
 * loaded rather than fetching a single resource. A direct link to a product
 * genuinely absent from the current tenant's ACTIVE list (e.g. a DRAFT product
 * with no published version) has nothing to show, which the empty state below
 * says plainly rather than spinning forever.
 */
export function ProductDetailPage() {
  const { productId = '' } = useParams();
  const identity = readIdentity(useAuth().user?.access_token);
  const canAuthor = canAuthorProducts(identity);
  const [publishOpen, setPublishOpen] = useState(false);

  const list = useProductStore((s) => s.list);
  const loadList = useProductStore((s) => s.loadList);
  const drafts = useProductStore((s) => s.drafts);
  const loadDrafts = useProductStore((s) => s.loadDrafts);
  const snapshot = useProductStore(selectProductSnapshot(productId));
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  // The rating endpoint is addressed by versionId, and the snapshot is the only
  // thing that resolves one -- so this load is chained behind it, not parallel.
  const versionId = snapshot.data?.productVersionId ?? null;
  const rating = useProductStore(selectVersionRating(versionId));
  const loadRating = useProductStore((s) => s.loadRating);

  useEffect(() => {
    if (list.data === null) void loadList();
  }, [list.data, loadList]);

  /*
    The DRAFT list too, and only for an ADMIN.

    A product is in `list` only once ACTIVE, so this page could not render a
    product whose publish step was abandoned -- which is the one product where
    this page is the whole point, because the publish form lives on it. Without
    this it showed the not-found state, so a draft could be neither seen nor
    finished anywhere on the platform.

    Gated on `canAuthor` because `GET /products/drafts` is ADMIN-only: a
    non-admin would collect a 403 in the store for a list they cannot use, and a
    non-admin has nothing to do on a draft anyway -- publishing is ADMIN too.
  */
  useEffect(() => {
    if (canAuthor && drafts.data === null) void loadDrafts();
  }, [canAuthor, drafts.data, loadDrafts]);

  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  useEffect(() => {
    if (productId && versionId) void loadRating(productId, versionId);
  }, [productId, versionId, loadRating]);

  // The catalogue first, then the drafts: an ACTIVE product is the common case,
  // and a product cannot be in both.
  const product =
    (list.data ?? []).find((p) => p.productId === productId) ??
    (drafts.data ?? []).find((p) => p.productId === productId);

  // Both lists have to settle before "not found" can be true, or a direct link to
  // a draft would flash the not-found state while the drafts request was still in
  // flight and then correct itself.
  if (isInitialLoad(list) || (canAuthor && isInitialLoad(drafts))) {
    return <LoadingBlock label="Loading product" />;
  }

  if (list.status === 'error' && list.error && list.data === null) {
    return (
      <>
        {/* The bar renders on the error path too, so a record that fails to load keeps its
            heading and its way out instead of leaving a bare panel. */}
        <PageHeader breadcrumb={[{ label: 'Products', to: '/staff/products' }]} title="Product" />
        <div className="px-6 pt-6">
          <ErrorPanel error={list.error} onRetry={() => void loadList()} />
        </div>
      </>
    );
  }

  if (!product) {
    return (
      <div className="px-6 pt-6">
        <div className="mt-4 rounded-lg border border-border bg-surface px-6 py-10 text-center">
          <p className="text-sm font-medium">Not found among active products</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Either this product does not exist, or it has no published version yet -- a DRAFT
            product is invisible everywhere in this console until one is published.
          </p>
        </div>
      </div>
    );
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Products', to: '/staff/products' }]}
        title={product.productName ?? productId}
        description={
          product.productCode ? <span className="font-mono text-xs">{product.productCode}</span> : undefined
        }
        actions={product.status && <StatusBadge kind="product" value={product.status} />}
      />

      {/* No `emphasis` on the publish panel despite it leading: authoring a product
          version is rare actuarial set-up, and the rating basis below is what most
          visits are actually here to read. */}
      <DetailLayout record={renderRecord()}>
        {/* Publishing prices the product and puts it in force -- ADMIN-only server-side, so
            the panel is absent rather than present-and-refusing for everyone else. The rating
            basis below stays readable, which is what most visits are here for anyway. */}
        {canAuthor && (
        <Panel title="Publish a new version">
            {publishOpen ? (
              <div className="p-4">
                <PublishVersionForm
                  productId={productId}
                  category={product.category ?? 'TERM_LIFE'}
                  onPublished={() => setPublishOpen(false)}
                />
              </div>
            ) : (
              <div className="p-4">
                <Button size="sm" onClick={() => setPublishOpen(true)}>
                  Publish new version
                </Button>
              </div>
            )}
          </Panel>
        )}

          <Panel
            title="Rating basis"
            subtitle="The version active today — no endpoint lists a product's other versions"
          >
            <RatingBasis
              rating={rating}
              versionId={versionId}
              onRetry={() => versionId && void loadRating(productId, versionId)}
            />
          </Panel>
      </DetailLayout>
    </>
  );

  function renderRecord() {
    // Unreachable -- the guard above already returned the not-found state. It is here
    // because narrowing does not survive into a hoisted function, which the compiler
    // must assume could be called at any time.
    if (!product) return null;

    return (
      <>
        <Panel title="Product">
          <dl className="px-4 pb-2">
            <Field label="Category" value={product.category?.replace(/_/g, ' ') ?? '—'} />
            <Field label="Default currency" value={product.defaultCurrency ?? '—'} />
          </dl>
        </Panel>

        {showsRates(product.category, identity) && (
          <Panel title="Declared interest rates" subtitle="Credited on every account, never below its own version's guarantee">
            <RateDeclarationsPanel productId={productId} />
          </Panel>
        )}

        <Panel title="Active version" subtitle="As of today">
          {isInitialLoad(snapshot) && (
            <p className="px-4 pb-4 text-xs text-muted-foreground">Loading…</p>
          )}
          {/*
            "No version in force today" is a FACT about the product, not a failure to
            load it, and it gets said rather than reported.

            The endpoint answers this with a 404, and a bare 404 on this platform has to
            hedge about whether the caller's role is at fault -- one can be a disguised
            denial. That hedge was catastrophic here: a product whose only version was
            effective the next day told a staff member "this record does not exist, or it
            is not available to your role", straight after they clicked it in the
            catalogue. Both halves false, and the second sent them to ask about
            permissions.

            `NO_ACTIVE_PRODUCT_VERSION` is the server saying it is not a denial and not an
            absence, so this branch renders the server's own sentence -- which names the
            date asked about and when the next version starts -- with no Try again, because
            retrying the same date will do the same thing.
          */}
          {snapshot.status === 'error' &&
            snapshot.error &&
            snapshot.data === null &&
            (snapshot.error.errorCode === 'NO_ACTIVE_PRODUCT_VERSION' ? (
              <p className="px-4 pt-1 pb-4 text-xs text-muted-foreground">
                {snapshot.error.detail ?? 'No version of this product is in force today.'}
              </p>
            ) : (
              <ErrorPanel error={snapshot.error} onRetry={() => void loadSnapshot(productId)} />
            ))}
          {snapshot.data && (
            <dl className="px-4 pb-2">
              <Field label="IFRS model" value={snapshot.data.ifrsMeasurementModel ?? '—'} />
              <Field label="Effective" value={snapshot.data.effectiveDate ?? '—'} />
              <Field
                label="Grace period"
                value={`${snapshot.data.gracePeriodDays ?? '—'} days`}
              />
              <Field
                label="Base rate cells"
                value={
                  rating.data
                    ? String((rating.data.baseRates ?? []).length)
                    : rating.status === 'error'
                      ? '—'
                      : 'Loading…'
                }
                // Only when it is actually zero. Carrying the warning on a
                // priced version would train readers to ignore it.
                {...(rating.data && (rating.data.baseRates ?? []).length === 0
                  ? { note: 'No cells means unpriced — no premium can be quoted.' }
                  : {})}
              />
            </dl>
          )}
        </Panel>
      </>
    );
  }
}

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

function RatingBasis({
  rating,
  versionId,
  onRetry,
}: {
  rating: RatingResource;
  versionId: string | null;
  onRetry: () => void;
}) {
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

      <section>
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
      </section>
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
function SectionHeading({ children }: { children: ReactNode }) {
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
function FactorSection({
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


