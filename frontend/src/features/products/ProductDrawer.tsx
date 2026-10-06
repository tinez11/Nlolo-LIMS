import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { portfolioLabel } from '@/lib/ifrs17';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectProductSnapshot, useProductStore } from '@/store/productStore';

/**
 * The preview half of drawer-previews-page-acts. Read-only: publishing a new
 * version is a real mutation and lives on the full detail page instead.
 *
 * Unlike Policies/Claims there is no `GET /products/{id}` at all -- the product
 * row comes from the already-loaded list (found by id), and only the active
 * SNAPSHOT (rating table excluded -- no read endpoint returns it) is fetched
 * separately.
 */
export function ProductDrawer({
  productId,
  onClose,
}: {
  productId: string | null;
  onClose: () => void;
}) {
  const list = useProductStore((s) => s.list);
  const snapshot = useProductStore(selectProductSnapshot(productId ?? ''));
  const loadSnapshot = useProductStore((s) => s.loadSnapshot);

  useEffect(() => {
    if (productId) void loadSnapshot(productId);
  }, [productId, loadSnapshot]);

  const product = (list.data ?? []).find((p) => p.productId === productId);

  return (
    <Sheet
      open={productId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Product preview">
        <SheetHeader
          title={product?.productName ?? 'Product'}
          subtitle={product?.productCode}
          action={product?.status ? <StatusBadge kind="product" value={product.status} /> : undefined}
        />

        <SheetBody>
          {!product && isInitialLoad(list) && <LoadingBlock />}
          {!product && list.status === 'success' && (
            <p className="text-xs text-muted-foreground">
              Not found in the currently loaded product list.
            </p>
          )}

          {product && (
            <dl className="space-y-0">
              <Field label="Category" value={product.category?.replace(/_/g, ' ') ?? '—'} />
              <Field label="Default currency" value={product.defaultCurrency ?? '—'} />

              {isInitialLoad(snapshot) && (
                <p className="py-2 text-xs text-muted-foreground">Loading active version…</p>
              )}
              {snapshot.status === 'error' && snapshot.error && snapshot.data === null && (
                <ErrorPanel
                  error={snapshot.error}
                  onRetry={() => (productId ? void loadSnapshot(productId) : undefined)}
                />
              )}
              {snapshot.data && (
                <>
                  <Field label="IFRS 17 portfolio" value={portfolioLabel(snapshot.data.portfolioCode)} />
                  <Field label="Grace period" value={`${snapshot.data.gracePeriodDays ?? '—'} days`} />
                </>
              )}
            </dl>
          )}
        </SheetBody>

        {productId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../products/${encodeURIComponent(productId)}`} relative="path">
                Full detail
                <ArrowRight />
              </Link>
            </Button>
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  );
}
