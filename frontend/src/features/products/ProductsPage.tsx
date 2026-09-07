import { Plus } from 'lucide-react';
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
import { isInitialLoad, isEmpty } from '@/store/createResourceSlice';
import { useProductStore } from '@/store/productStore';
import { ProductDrawer } from './ProductDrawer';
import { FilterChip } from '@/components/FilterChip';

/**
 * `GET /products` is a bare unpaged array (catalog browsing), so this gets the
 * no-pager table variant, unlike Policies/Claims. It ALSO only ever returns
 * `status: ACTIVE` products (`ProductApiImpl.listActiveProducts` filters on it
 * server-side) -- a newly created product starts DRAFT and is genuinely
 * invisible here until a version is published, which the page description says
 * plainly rather than letting a staff user discover it as "my product vanished".
 */
export function ProductsPage() {
  const canAuthor = canAuthorProducts(readIdentity(useAuth().user?.access_token));
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const categoryParam = params.get('category');
  const category: ProductCategory | undefined =
    categoryParam && (PRODUCT_CATEGORIES as readonly string[]).includes(categoryParam)
      ? (categoryParam as ProductCategory)
      : undefined;

  const list = useProductStore((s) => s.list);
  const loadList = useProductStore((s) => s.loadList);

  useEffect(() => {
    void loadList(category);
  }, [loadList, category]);

  function update(next: ProductCategory | undefined) {
    const merged = new URLSearchParams(params);
    if (next) merged.set('category', next);
    else merged.delete('category');
    setParams(merged);
  }

  const total = list.data?.length ?? null;
  const count: Stat = {
    label: category ? `${category.replace(/_/g, ' ').toLowerCase()} products` : 'active products',
    value: total,
    pending: isInitialLoad(list),
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
      render: (p) => <span className="font-mono text-xs">{p.productCode ?? '—'}</span>,
    },
    {
      key: 'category',
      header: 'Category',
      secondary: true,
      render: (p) => <span className="text-muted-foreground">{p.category?.replace(/_/g, ' ')}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (p) => <StatusBadge kind="product" value={p.status} />,
    },
    {
      key: 'defaultCurrency',
      header: 'Currency',
      align: 'right',
      render: (p) => p.defaultCurrency ?? '—',
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={() => void loadList(category)} />;
    }

    if (isEmpty(list)) {
      return (
        <EmptyState
          title={category ? `No active ${category.toLowerCase().replace(/_/g, ' ')} products` : 'No active products'}
          description="A product must have a published version before it appears here."
        />
      );
    }

    return (
      <>
        {list.status === 'error' && list.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={list.data ?? []}
          rowKey={(p) => p.productId ?? JSON.stringify(p)}
          onRowActivate={(p) => {
            if (p.productId) setPreviewing(p.productId);
          }}
          isRowSelected={(p) => p.productId === previewing}
          caption="Products"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Products"
        description="Active products in your tenant. A new product needs a published version before it shows up here."
        /* Authoring is ADMIN-only server-side; offering the button to anyone else would
           lead them through a whole form to a 403 at the end. The catalogue itself stays
           readable by every staff member, which is why only the ACTION is gated here. */
        actions={
          canAuthor ? (
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              New product
            </Link>
          </Button>
          ) : undefined
        }
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={category === undefined} onClick={() => update(undefined)} />
            {PRODUCT_CATEGORIES.map((value) => (
              <FilterChip
                key={value}
                label={value.replace(/_/g, ' ')}
                active={category === value}
                onClick={() => update(value)}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>

      <ProductDrawer productId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}

