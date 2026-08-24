import { ArrowLeft } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/AppShell';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';
import { PublishVersionForm } from './PublishVersionForm';

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

  useEffect(() => {
    if (list.data === null) void loadList();
  }, [list.data, loadList]);

  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

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
                  label="Rating table"
                  value="Not readable"
                  note="No endpoint returns a published rating table or benefit schedule -- only what was entered at publish time is known."
                />
              </dl>
            )}
          </Panel>
        </div>
      </div>
    </>
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

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
