import { ArrowRight, Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canAuthorProducts, readIdentity } from '@/auth/claims';
import { PRODUCT_CATEGORIES, type ProductCategory, type ProductSummary } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, type Column } from '@/components/DataTable';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { portfolioLabel } from '@/lib/ifrs17';
import { humanizeStatus } from '@/lib/status';
import { cn } from '@/lib/cn';
import { isInitialLoad, isEmpty } from '@/store/createResourceSlice';
import { useProductStore } from '@/store/productStore';
import { ProductDrawer } from './ProductDrawer';
import { FilterChip } from '@/components/FilterChip';

type View = 'live' | 'drafts';

/**
 * Every product, live or still being set up (2026-10-22).
 *
 * `GET /products` returns ACTIVE products only, so a product created but never published used to appear in no list but
 * a warning box above this one -- while its code stayed taken. Drafts are now a view of their own beside the live
 * catalogue, each with the way back into its setup.
 */
export function ProductsPage() {
  const canAuthor = canAuthorProducts(readIdentity(useAuth().user?.access_token));
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const view: View = canAuthor && params.get('view') === 'drafts' ? 'drafts' : 'live';
  const categoryParam = params.get('category');
  const category: ProductCategory | undefined =
    categoryParam && (PRODUCT_CATEGORIES as readonly string[]).includes(categoryParam)
      ? (categoryParam as ProductCategory)
      : undefined;

  const list = useProductStore((s) => s.list);
  const loadList = useProductStore((s) => s.loadList);
  const drafts = useProductStore((s) => s.drafts);
  const loadDrafts = useProductStore((s) => s.loadDrafts);

  useEffect(() => {
    void loadList(category);
  }, [loadList, category]);
  useEffect(() => {
    // Drafts are ADMIN-gated server-side, and only an author can finish one.
    if (canAuthor) void loadDrafts();
  }, [canAuthor, loadDrafts]);

  function update(next: { category?: ProductCategory | undefined; view?: View }) {
    const merged = new URLSearchParams(params);
    if ('category' in next) {
      if (next.category) merged.set('category', next.category);
      else merged.delete('category');
    }
    if (next.view) {
      if (next.view === 'drafts') merged.set('view', 'drafts');
      else merged.delete('view');
    }
    setParams(merged);
  }

  const draftRows = (drafts.data ?? []).filter((p) => !category || p.category === category);
  const total = view === 'drafts' ? draftRows.length : (list.data?.length ?? null);
  const count: Stat = {
    label: view === 'drafts' ? 'drafts' : category ? `${humanizeStatus(category).toLowerCase()} products` : 'live products',
    value: total,
    pending: view === 'drafts' ? isInitialLoad(drafts) : isInitialLoad(list),
    hint: list.status === 'error' && total === null ? 'could not load' : 'in your tenant',
  };

  const columns: Column<ProductSummary>[] = [
    {
      key: 'productName',
      header: 'Product',
      render: (p) => <span className="font-medium">{p.productName ?? '—'}</span>,
    },
    {
      key: 'productCode',
      header: 'Code',
      secondary: true,
      render: (p) => <span className="font-mono text-sm whitespace-nowrap text-muted-foreground">{p.productCode ?? '—'}</span>,
    },
    {
      key: 'category',
      header: 'Category',
      secondary: true,
      render: (p) => <span className="text-muted-foreground">{p.category ? humanizeStatus(p.category) : '—'}</span>,
    },
    {
      // The portfolio says what a product IS -- a fixed deposit is an "Endowment" by category.
      key: 'portfolioCode',
      header: 'Type',
      secondary: true,
      render: (p) => <span className="whitespace-nowrap text-muted-foreground">{portfolioLabel(p.portfolioCode)}</span>,
    },
    view === 'drafts'
      ? {
          key: 'continue',
          header: '',
          align: 'right',
          render: (p) =>
            p.productId ? (
              <Link to={p.productId} className="inline-flex items-center gap-1 text-sm font-medium text-accent hover:underline">
                Continue setup <ArrowRight className="size-3.5" aria-hidden />
              </Link>
            ) : null,
        }
      : {
          key: 'status',
          header: 'Status',
          render: (p) => <StatusBadge kind="product" value={p.status} />,
        },
  ];

  function renderBody() {
    const source = view === 'drafts' ? drafts : list;
    if (isInitialLoad(source)) return <TableSkeleton columns={columns.length} />;
    if (source.status === 'error' && source.error && source.data === null) {
      return (
        <ErrorPanel
          error={source.error}
          onRetry={() => void (view === 'drafts' ? loadDrafts() : loadList(category))}
        />
      );
    }
    const rows = view === 'drafts' ? draftRows : (list.data ?? []);
    if (view === 'drafts' ? rows.length === 0 : isEmpty(list)) {
      return view === 'drafts' ? (
        <EmptyState title="No drafts" description="Every product created has been published." />
      ) : (
        <EmptyState
          title={category ? `No live ${humanizeStatus(category).toLowerCase()} products` : 'No live products'}
          description="A product goes live when its first version is published."
        />
      );
    }
    return (
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(p) => p.productId ?? JSON.stringify(p)}
        {...(view === 'live'
          ? {
              onRowActivate: (p: ProductSummary) => {
                if (p.productId) setPreviewing(p.productId);
              },
              isRowSelected: (p: ProductSummary) => p.productId === previewing,
            }
          : {})}
        caption={view === 'drafts' ? 'Draft products' : 'Products'}
      />
    );
  }

  const draftCount = drafts.data?.length ?? 0;

  return (
    <>
      <PageHeader
        title="Products"
        description="What you sell, and products still being set up."
        actions={
          canAuthor ? (
            <Button asChild variant="primary">
              <Link to="new">
                <Plus />
                New product
              </Link>
            </Button>
          ) : undefined
        }
        count={<CountLine {...count} />}
      />

      <div className="space-y-3 px-6 pb-6">
        {canAuthor && (
          <div className="inline-flex gap-1 rounded-xl bg-control p-1" role="group" aria-label="Show">
            {(['live', 'drafts'] as const).map((v) => (
              <button
                key={v}
                type="button"
                aria-pressed={view === v}
                onClick={() => update({ view: v })}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors pointer-coarse:min-h-11',
                  view === v ? 'bg-surface text-foreground shadow-raise' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {v === 'live' ? 'Live' : 'Drafts'}
                {v === 'drafts' && draftCount > 0 && (
                  <span className="rounded-full bg-status-warning-bg px-1.5 text-xs text-status-warning-fg">{draftCount}</span>
                )}
              </button>
            ))}
          </div>
        )}

        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={category === undefined} onClick={() => update({ category: undefined })} />
            {PRODUCT_CATEGORIES.map((value) => (
              <FilterChip
                key={value}
                label={humanizeStatus(value)}
                active={category === value}
                onClick={() => update({ category: value })}
              />
            ))}
          </div>
          {list.status === 'error' && list.error && list.data !== null && view === 'live' && (
            <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
              Showing older data — could not refresh.
              {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
            </p>
          )}
          {renderBody()}
        </div>
      </div>

      <ProductDrawer productId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}
