import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { RateDeclarationsPanel } from './RateDeclarationsPanel';
import { showsRates } from './rateDeclarationForm';
import { BonusDeclarationsPanel } from '@/features/bonuses/BonusDeclarationsPanel';
import { showsBonuses } from '@/features/bonuses/bonusDeclarationForm';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { portfolioLabel } from '@/lib/ifrs17';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useProductStore } from '@/store/productStore';
import { DraftPortfolioField } from './DraftPortfolioField';
import { OnlineListingPanel } from './OnlineListingPanel';
import { ProductVersionsPanel } from './ProductVersionsPanel';
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
  // Bumped on a publish here, so the versions list reloads and shows the new one as current.
  const [published, setPublished] = useState(0);

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
            Either this product does not exist, or it has no published version yet — a DRAFT
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
                  onPublished={() => { setPublishOpen(false); setPublished((n) => n + 1); }}
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
            title="Versions"
            subtitle="Every version published, newest first; Current is what a sale today is priced on"
          >
            <ProductVersionsPanel productId={productId} category={product.category} refreshKey={published} />
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
            {canAuthor && product.status === 'DRAFT' && product.productId ? (
              <DraftPortfolioField productId={product.productId} current={product.portfolioCode}
                onChanged={() => void loadDrafts()} />
            ) : (
              <Field label="IFRS 17 portfolio" value={portfolioLabel(product.portfolioCode)} />
            )}
          </dl>
        </Panel>

        {product.status === 'ACTIVE' && (
          <Panel title="Customer portal" subtitle="Whether customers can see, price and ask for it online">
            <OnlineListingPanel productId={productId} canEdit={canAuthor} />
          </Panel>
        )}

        {showsRates(product.category, identity) && (
          <Panel title="Declared interest rates" subtitle="Credited on every account, never below its own version's guarantee">
            <RateDeclarationsPanel productId={productId} />
          </Panel>
        )}

        {showsBonuses(product.category, identity) && (
          <Panel title="Bonus declarations" subtitle="Attached to every with-profits policy on this product eligible on the valuation date">
            <BonusDeclarationsPanel productId={productId} />
          </Panel>
        )}
      </>
    );
  }
}
