import { useEffect, useState } from 'react';
import { getProduct } from '@/api/products';
import { cn } from '@/lib/cn';

/**
 * Resolves a product id to the product's name for a read-only reference.
 *
 * Why this exists: an underwriter deciding a case, or a service agent reading a
 * policy, is told WHICH PRODUCT by a uuid. The underwriting queue rendered a
 * whole column of them; the case detail rail printed one under the label
 * "Product" with a note admitting no lookup existed. Neither supports the
 * decision the screen is for -- a term life case and a unit-linked case are not
 * assessed the same way, and the id does not say which one this is.
 *
 * The name is shown with the product CODE beside it, because the code is what
 * staff use between themselves ("that's on DEMO-TERM-01") and it is short enough
 * to carry for free. The id stays on the `title`, since it is still what someone
 * pastes into a support thread.
 *
 * Falls back to the raw id while loading and if the lookup fails, exactly like
 * {@link PartyName}: a 404 from `GET /products/{id}` is not proof of absence,
 * and the id is the honest thing to show when the name is not known.
 */

/**
 * Module-level cache, shared by every instance. A 20-row queue is frequently 20
 * rows of the same three products, and re-renders must not re-request.
 * `undefined` means never looked up; `null` means looked up and unresolvable,
 * which is cached too so an id nobody can read is not retried once per row.
 */
const PRODUCTS = new Map<string, { name: string; code: string | null } | null>();
const PENDING = new Map<string, Promise<unknown>>();

function resolveProduct(productId: string) {
  const cached = PRODUCTS.get(productId);
  if (cached !== undefined) return Promise.resolve(cached);

  const inFlight = PENDING.get(productId);
  if (inFlight) return inFlight;

  const request = getProduct(productId)
    .then((product) => {
      const resolved = product.productName
        ? { name: product.productName, code: product.productCode ?? null }
        : null;
      PRODUCTS.set(productId, resolved);
      return resolved;
    })
    .catch(() => {
      PRODUCTS.set(productId, null);
      return null;
    })
    .finally(() => {
      PENDING.delete(productId);
    });

  PENDING.set(productId, request);
  return request;
}

/**
 * Exported for tests, which must not inherit another test's cached lookups --
 * the cache is module state. Same hook, and the same reason, as
 * `__clearPartyNameCache`.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function __clearProductNameCache() {
  PRODUCTS.clear();
  PENDING.clear();
}

export function ProductName({
  productId,
  className,
  withCode = true,
}: {
  productId: string;
  className?: string;
  /** Show the product code beside the name. Off where space is tight. */
  withCode?: boolean;
}) {
  // Read the cache during render, not in an effect, so a product another row
  // already resolved paints on the first frame with no flash of the raw id.
  const cached = PRODUCTS.get(productId);
  const [, bumpAfterResolve] = useState(0);

  useEffect(() => {
    if (PRODUCTS.has(productId)) return;
    let cancelled = false;
    void resolveProduct(productId).then(() => {
      if (!cancelled) bumpAfterResolve((n) => n + 1);
    });
    return () => {
      cancelled = true;
    };
  }, [productId]);

  if (!cached) {
    return <span className={cn('font-mono text-xs', className)}>{productId}</span>;
  }

  return (
    <span className={cn('text-sm', className)} title={productId}>
      {cached.name}
      {withCode && cached.code && (
        <span className="ml-1.5 font-mono text-xs text-subtle-foreground">{cached.code}</span>
      )}
    </span>
  );
}
