import { get, post, put } from '@/lib/http';
import type { components as ProductComponents } from '@/types/api/product';

/** How the customer portal offers a product (2026-10-08, the customer portal design step 5). */
export type OnlineListingView = ProductComponents['schemas']['OnlineListing'];

export function getOnlineListing(productId: string): Promise<OnlineListingView> {
  return get<OnlineListingView>(`/products/${encodeURIComponent(productId)}/online-listing`);
}

/** ADMIN: offer it online or not, and say what it is. */
export function describeOnline(
  productId: string,
  listing: { available: boolean; summary: string | null; benefits: string[] },
): Promise<OnlineListingView> {
  return put<OnlineListingView>(`/products/${encodeURIComponent(productId)}/online-listing`, listing);
}
import type {
  CreateProductRequest,
  ProductCategory,
  ProductSnapshot,
  ProductSummary,
  ProductVersionSpec,
  ProductVersionSummaryView,
  VersionRatingView,
} from './types';

/**
 * Products read/write surface. `GET /products` is a bare unpaged array (catalog
 * browsing, not a paged search) -- the whole catalog arrives in one response.
 *
 * `GET /products` only ever returns products with `status: ACTIVE`
 * (`ProductApiImpl.listActiveProducts` filters on it server-side, confirmed by
 * reading the method) -- a newly created product starts `DRAFT`, and until
 * `GET /products/drafts` existed it was invisible everywhere in this app until a
 * version was published. `GET /products/{id}` now closes the last gap: before it,
 * no read on this platform turned a product id into a product name.
 */

export function listProducts(category?: ProductCategory): Promise<ProductSummary[]> {
  return get<ProductSummary[]>('/products', category ? { params: { category } } : undefined);
}

/**
 * `GET /products/drafts` -- products created but never published.
 *
 * Authoring is two phases and only the second makes a product ACTIVE, so
 * abandoning it left a DRAFT that appeared in no list while the database went on
 * holding its product code: the code was burned and the product could be neither
 * seen nor finished. Reported from this console as "a product with code
 * Education02 already exists but is not on the list".
 *
 * **ADMIN-only**, and a separate endpoint rather than a `status` parameter on the
 * catalogue: that one answers to the customers and agents realms, and an
 * unlaunched product is not something a policyholder or a tied agent enumerates.
 * Callers must gate on `canAuthorProducts` or expect a 403.
 *
 * No category parameter: a draft is an unfinished task, not a catalogue entry.
 */
export function listDraftProducts(): Promise<ProductSummary[]> {
  return get<ProductSummary[]>('/products/drafts');
}

/**
 * `GET /products/{productId}` -- one product, by the id a record already carries.
 *
 * The lookup that turns a product id into a product NAME. Until it existed, a
 * screen holding an id could reach the snapshot (pricing and eligibility, no name)
 * or the whole catalogue (names, keyed by nothing) -- and the catalogue holds
 * ACTIVE only, so a retired product resolved to nothing at all while the policies
 * and cases referencing it were still on screen.
 *
 * Any status, and open to every realm, exactly like the catalogue: what the
 * ADMIN gate on `/products/drafts` protects is enumerating unlaunched products,
 * which resolving one id the caller already holds does not do.
 */
export function getProduct(productId: string): Promise<ProductSummary> {
  return get<ProductSummary>(`/products/${encodeURIComponent(productId)}`);
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
 * `GET /products/{productId}/versions` -- staff. Every published version, the most recently published first, the
 * one a sale today is priced on marked `current` (2026-10-08).
 */
export function listProductVersions(productId: string): Promise<ProductVersionSummaryView[]> {
  return get<ProductVersionSummaryView[]>(`/products/${encodeURIComponent(productId)}/versions`);
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
 * It takes any version's id, from {@link listProductVersions} or the snapshot.
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
