import { get } from '@/lib/http';
import type { ProductSnapshot, ProductSummary } from './types';

/**
 * Products read surface. `GET /products` is a bare unpaged array (catalog
 * browsing, not a paged search) -- the whole catalog arrives in one response.
 * Used here only to populate the product picker on manual policy issuance;
 * there is no product management UI in this slice.
 */

export function listProducts(): Promise<ProductSummary[]> {
  return get<ProductSummary[]>('/products');
}

/**
 * Resolves a product to the version active as of today -- what
 * `POST /policies/manual-issue` actually needs (`productVersionId`), which
 * `GET /products` itself does not expose.
 */
export function getActiveSnapshot(productId: string): Promise<ProductSnapshot> {
  return get<ProductSnapshot>(`/products/${encodeURIComponent(productId)}/active-snapshot`);
}
