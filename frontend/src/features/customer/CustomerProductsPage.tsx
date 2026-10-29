import { ChevronRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { applyForProduct, getCustomerProducts, type CustomerProduct } from '@/api/portal';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { NEED_ICON } from './needIcons';
import { isNeed, NEEDS, type Need } from './selfService';
import { useWords } from './words';

/**
 * Get cover (2026-10-29, the user's choice of "start from what you need"): a tile for each need a product online meets,
 * then the products that meet it, each priced one question at a time. What an adviser prices -- annuities, unit-linked,
 * education and the savings accounts -- is listed apart, with a way to ask. Nothing here is an offer.
 */
export function CustomerProductsPage() {
  const w = useWords().needs;
  const [products, setProducts] = useState<CustomerProduct[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);
  const [need, setNeed] = useState<Need | null>(null);

  useEffect(() => {
    let live = true;
    getCustomerProducts().then(
      (p) => { if (live) { setProducts(p); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  const online = (products ?? []).filter((p) => p.online && isNeed(p.kind));
  const needs = NEEDS.filter((n) => online.some((p) => p.kind === n));
  const chosen = need ?? (needs.length === 1 ? needs[0]! : null);
  const adviser = (products ?? []).filter((p) => !p.online);

  return (
    <>
      <PageHeader title={w.title} description={w.lead} />
      <div className="w-full max-w-3xl space-y-6 px-4 pb-10 text-base sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !products ? (
          <LoadingBlock label="Loading products" />
        ) : products.length === 0 ? (
          <EmptyState title={w.nothing} description={w.nothingLead} />
        ) : (
          <>
            {needs.length > 0 && (
              <div role="radiogroup" aria-label={w.title} className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                {needs.map((n) => {
                  const Icon = NEED_ICON[n];
                  const on = chosen === n;
                  return (
                    <button key={n} type="button" role="radio" aria-checked={on} onClick={() => setNeed(n)}
                      className={cn('flex items-start sm:min-h-28 gap-4 rounded-2xl border bg-surface p-4 text-left shadow-card transition-colors',
                        on ? 'border-2 border-accent shadow-[0_0_0_4px_color-mix(in_oklab,var(--accent)_14%,transparent)]' : 'border-border hover:bg-hover')}>
                      <span className={cn('grid size-12 shrink-0 place-items-center rounded-xl', on ? 'bg-accent text-accent-foreground' : 'bg-selected text-accent')} aria-hidden>
                        <Icon className="size-6" />
                      </span>
                      <span className="min-w-0">
                        <span className="block text-title">{w[n][0]}</span>
                        <span className="block text-sm text-muted-foreground">{w[n][1]}</span>
                      </span>
                    </button>
                  );
                })}
              </div>
            )}

            {chosen && (
              <section aria-label={w[chosen][0]} className="space-y-3">
                <h2 className="text-display">{online.filter((p) => p.kind === chosen).length > 1 ? w.choose : w[chosen][0]}</h2>
                {online.filter((p) => p.kind === chosen).map((p) => <ProductCard key={p.productId} product={p} />)}
              </section>
            )}

            {adviser.length > 0 && (
              <Panel title={w.adviserTitle} subtitle={w.adviserLead}>
                <ul className="divide-y divide-border">
                  {adviser.map((p) => <AdviserRow key={p.productId} product={p} />)}
                </ul>
              </Panel>
            )}
          </>
        )}
      </div>
    </>
  );
}

function ProductCard({ product }: { product: CustomerProduct }) {
  const w = useWords().needs;
  return (
    <Link to={`/customers/products/${encodeURIComponent(product.productId)}`}
      className="flex items-center gap-4 rounded-2xl border border-border bg-surface p-4 shadow-card hover:bg-hover">
      <span className="min-w-0 flex-1 space-y-1">
        <span className="block text-title">{product.productName}</span>
        {product.summary && <span className="block text-sm text-muted-foreground">{product.summary}</span>}
        {product.benefits.length > 0 && (
          <span className="block text-sm text-muted-foreground">{product.benefits.join(' · ')}</span>
        )}
      </span>
      <span className="inline-flex shrink-0 items-center gap-1 font-semibold text-accent">
        {w.see}<ChevronRight className="size-4" aria-hidden />
      </span>
    </Link>
  );
}

/** Asked for with no price: the application tells an adviser to call. */
function AdviserRow({ product }: { product: CustomerProduct }) {
  const w = useWords().needs;
  const [state, setState] = useState<'idle' | 'busy' | 'done'>('idle');
  const [error, setError] = useState<ApiError | null>(null);

  async function ask() {
    setState('busy');
    setError(null);
    try {
      await applyForProduct({ productId: product.productId });
      setState('done');
    } catch (e) {
      const err = toApiError(e);
      // Already asked: that is the answer the customer wanted to give.
      if (err.status === 409) { setState('done'); return; }
      setError(err);
      setState('idle');
    }
  }

  return (
    <li className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
      <span className="min-w-0">
        <span className="block font-medium">{product.productName}</span>
        {product.summary && <span className="block text-sm text-muted-foreground">{product.summary}</span>}
        {error && <InlineError error={error} />}
      </span>
      {state === 'done' ? (
        <span className="text-sm font-medium text-status-success-fg" role="status">{w.asked}</span>
      ) : (
        <Button variant="outline" pending={state === 'busy'} onClick={() => void ask()}>{w.ask}</Button>
      )}
    </li>
  );
}
