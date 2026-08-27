import { get, post } from '@/lib/http';
import type {
  CreateProductRequest,
  ProductCategory,
  ProductSnapshot,
  ProductSummary,
  ProductVersionSpec,
  VersionRatingView,
} from './types';

/**
 * Products read/write surface. `GET /products` is a bare unpaged array (catalog
 * browsing, not a paged search) -- the whole catalog arrives in one response.
 *
 * `GET /products` only ever returns products with `status: ACTIVE`
 * (`ProductApiImpl.listActiveProducts` filters on it server-side, confirmed by
 * reading the method) -- a newly created product starts `DRAFT` and is
 * genuinely invisible everywhere in this app until a version is published.
 * There is also no `GET /products/{id}` at all: the list, the active-snapshot
 * lookup and one version's rating basis are the only three reads that exist.
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

/**
 * `GET /products/{productId}/versions/{versionId}/rating` -- the base rate table,
 * rating multipliers and benefit schedule a version was published with.
 *
 * Deliberately NOT folded into the snapshot: Underwriting and Billing read that
 * on the issuance and billing path and would then carry rate tables they never
 * use, and it could not be narrowed to staff without breaking them. This one is
 * staff-only, so it lives on its own endpoint.
 *
 * It needs a versionId, which only the snapshot exposes -- there is no endpoint
 * that lists a product's versions, so the only version reachable from this
 * console is the one active today.
 */
export function getVersionRating(productId: string, versionId: string): Promise<VersionRatingView> {
  return get<VersionRatingView>(
    `/products/${encodeURIComponent(productId)}/versions/${encodeURIComponent(versionId)}/rating`,
  );
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
