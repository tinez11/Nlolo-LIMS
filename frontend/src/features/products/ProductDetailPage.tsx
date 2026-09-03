import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import type { BaseRate, VersionRatingView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectProductSnapshot,
  selectVersionRating,
  useProductStore,
} from '@/store/productStore';
import { PublishVersionForm } from './PublishVersionForm';
import { Panel } from '@/components/Panel';

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
  const [publishOpen, setPublishOpen] = useState(false);

  const list = useProductStore((s) => s.list);
  const loadList = useProductStore((s) => s.loadList);
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

  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  useEffect(() => {
    if (productId && versionId) void loadRating(productId, versionId);
  }, [productId, versionId, loadRating]);

  const product = (list.data ?? []).find((p) => p.productId === productId);

  if (isInitialLoad(list)) {
    return <LoadingBlock label="Loading product" />;
  }

  if (list.status === 'error' && list.error && list.data === null) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={list.error} onRetry={() => void loadList()} />
      </div>
    );
  }

  if (!product) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
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
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={product.productName ?? productId}
        description={
          product.productCode ? <span className="font-mono text-xs">{product.productCode}</span> : undefined
        }
        actions={product.status && <StatusBadge kind="product" value={product.status} />}
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
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
        </div>

        <div className="space-y-5">
          <Panel title="Product">
            <dl className="px-4 pb-2">
              <Field label="Category" value={product.category?.replace(/_/g, ' ') ?? '—'} />
              <Field label="Default currency" value={product.defaultCurrency ?? '—'} />
            </dl>
          </Panel>

          <Panel title="Active version" subtitle="As of today">
            {isInitialLoad(snapshot) && (
              <p className="px-4 pb-4 text-xs text-muted-foreground">Loading…</p>
            )}
            {snapshot.status === 'error' && snapshot.error && snapshot.data === null && (
              <ErrorPanel error={snapshot.error} onRetry={() => void loadSnapshot(productId)} />
            )}
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
        </div>
      </div>
    </>
  );
}

/**
 * Rates are `type: number` on the wire (BigDecimal server-side), so they are
 * shown exactly as sent -- `maximumFractionDigits` is high enough not to round,
 * and no fixed decimal count is imposed, because padding 2.5 to "2.500" would
 * assert a precision the actuary did not give.
 */
const RATE = new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 });

const BASE_RATE_COLUMNS: Column<BaseRate>[] = [
  {
    key: 'age',
    header: 'Age',
    // ageTo is INCLUSIVE (see BaseRate in openapi-product.yaml). Rendered as a
    // closed interval so nobody reads "18-25" as excluding 25.
    render: (r) => <span className="tabular-nums">{`${r.ageFrom}–${r.ageTo}`}</span>,
  },
  { key: 'sex', header: 'Sex', render: (r) => r.sex },
  {
    key: 'smokerStatus',
    header: 'Smoker',
    render: (r) => r.smokerStatus.replace(/_/g, ' ').toLowerCase(),
  },
  {
    key: 'ratePerMille',
    header: 'Rate / 1,000',
    align: 'right',
    render: (r) => <span className="tabular-nums">{RATE.format(r.ratePerMille)}</span>,
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
    return <p className="px-4 pb-4 pt-3 text-xs text-muted-foreground">Loading…</p>;
  }

  if (rating.status === 'error' && rating.error && rating.data === null) {
    return <ErrorPanel error={rating.error} onRetry={onRetry} />;
  }

  if (!rating.data) return null;

  const baseRates = rating.data.baseRates ?? [];
  const factors = rating.data.ratingFactors ?? [];
  const benefits = rating.data.benefitSchedule ?? [];

  return (
    <div className="space-y-4 pb-4">
      <section>
        <h3 className="px-4 pt-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          Base rates
        </h3>
        {baseRates.length === 0 ? (
          // NOT an empty table. Every version published before M13 has no rate
          // table at all, which is a different fact from "the table is empty" --
          // it means no premium can be quoted for this product, and a bare
          // "No rows" would leave a reader to guess that.
          <p className="mt-1 px-4 text-xs text-muted-foreground">
            This version is <strong className="font-medium text-foreground">unpriced</strong>. It was
            published without a base rate table, so nothing on this platform can quote a premium for
            it. A rate table has to be supplied at publish time, and this version had none.
          </p>
        ) : (
          <div className="mt-1 border-t border-border">
            <DataTable
              columns={BASE_RATE_COLUMNS}
              rows={baseRates}
              rowKey={(r) => `${r.ageFrom}-${r.ageTo}-${r.sex}-${r.smokerStatus}`}
              caption="Base rate table"
            />
          </div>
        )}
      </section>

      <FactorList
        title="Rating multipliers"
        empty="No multipliers on this version."
        rows={factors.map((f) => ({
          key: `${f.factorType}-${f.band}`,
          label: factorLabel(f),
          value: f.multiplier === undefined ? '—' : `× ${RATE.format(f.multiplier)}`,
        }))}
      />

      <FactorList
        title="Benefit schedule"
        empty="No benefits on this version."
        rows={benefits.map((b) => ({
          key: `${b.benefitType}-${b.calculationMethod}`,
          label: (b.benefitType ?? '—').replace(/_/g, ' ').toLowerCase(),
          value: b.calculationMethod ?? '—',
        }))}
      />
    </div>
  );
}

/**
 * The band is a LABEL the actuary typed; on an AGE row it is the age bounds the platform
 * actually resolves against, and until product V5 those did not exist at all -- which is
 * exactly how age went unrated here. Publishing now requires them, so show them: a screen
 * that renders only the band cannot tell a reader whether the range behind it is right,
 * or even present.
 */
function factorLabel(factor: RatingFactorRow): string {
  const base = `${(factor.factorType ?? '—').replace(/_/g, ' ').toLowerCase()} · ${factor.band ?? '—'}`;
  return factor.ageFrom === undefined || factor.ageTo === undefined
    ? base
    : `${base} (ages ${factor.ageFrom}–${factor.ageTo})`;
}

function FactorList({
  title,
  empty,
  rows,
}: {
  title: string;
  empty: string;
  rows: { key: string; label: string; value: string }[];
}) {
  return (
    <section className="px-4">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h3>
      {rows.length === 0 ? (
        <p className="mt-1 text-xs text-muted-foreground">{empty}</p>
      ) : (
        <ul className="mt-1.5 space-y-1">
          {rows.map((row) => (
            <li key={row.key} className="flex items-baseline justify-between gap-3 text-xs">
              <span className="capitalize">{row.label}</span>
              <span className="shrink-0 font-mono tabular-nums text-muted-foreground">{row.value}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to=".." relative="path">
        <ArrowLeft />
        All products
      </Link>
    </Button>
  );
}

