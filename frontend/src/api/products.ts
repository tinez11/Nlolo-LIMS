import { get, post } from '@/lib/http';
import type {
  CreateProductRequest,
  ProductCategory,
  ProductSnapshot,
  ProductSummary,
  ProductVersionSpec,
} from './types';

/**
 * Products read/write surface. `GET /products` is a bare unpaged array (catalog
 * browsing, not a paged search) -- the whole catalog arrives in one response.
 *
 * `GET /products` only ever returns products with `status: ACTIVE`
 * (`ProductApiImpl.listActiveProducts` filters on it server-side, confirmed by
 * reading the method) -- a newly created product starts `DRAFT` and is
 * genuinely invisible everywhere in this app until a version is published.
 * There is also no `GET /products/{id}` at all: the list and the
 * active-snapshot lookup are the only two reads that exist.
 */

export function listProducts(category?: ProductCategory): Promise<ProductSummary[]> {
  return get<ProductSummary[]>('/products', category ? { params: { category } } : undefined);
}

/**
 * Resolves a product to the version active as of today -- what
 * `POST /policies/manual-issue` actually needs (`productVersionId`), which
 * `GET /products` itself does not expose.
 */
export function getActiveSnapshot(productId: string): Promise<ProductSnapshot> {
  return get<ProductSnapshot>(`/products/${encodeURIComponent(productId)}/active-snapshot`);
}

/** `POST /products` -- staff-only. No Idempotency-Key on this endpoint either
 *  (matches manual policy issuance); a duplicate productCode 409s. */
export function createProduct(request: CreateProductRequest): Promise<ProductSummary> {
  return post<ProductSummary>('/products', request);
}

/**
 * `POST /products/{id}/versions` -- staff-only, and the ONLY way a product ever
 * becomes visible through `GET /products`: publishing flips its status from
 * DRAFT to ACTIVE. Returns 201 with no response body (verified against a real
 * call), so callers must reload the product list to see the effect.
 */
export function publishVersion(productId: string, spec: ProductVersionSpec): Promise<void> {
  return post<void>(`/products/${encodeURIComponent(productId)}/versions`, spec);
}
