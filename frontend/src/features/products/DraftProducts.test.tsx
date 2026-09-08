import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductSummary } from '@/api/types';
import { idle, success } from '@/store/createResourceSlice';
import { useProductStore } from '@/store/productStore';
import { ProductsPage } from './ProductsPage';

/**
 * The drafts panel on the products page.
 *
 * ## What this is defending
 *
 * Authoring a product is two phases -- create the definition, then publish a
 * version -- and only the second flips DRAFT to ACTIVE. `GET /products` returns
 * ACTIVE only, so abandoning the second phase left a product that appeared in no
 * list anywhere on the platform while `ux_product_code` went on holding its
 * code. The code was burned and the product could be neither seen, finished nor
 * removed. It surfaced as a 409 on the create form reading "a product with code
 * Education02 already exists for this tenant" against a catalogue that showed no
 * such product -- true, and impossible to act on.
 *
 * `GET /products/drafts` closes it. These tests hold the three rules that make it
 * safe and quiet: it shows what is unfinished, it stays silent when there is
 * nothing to finish, and a non-ADMIN never sees or requests it.
 */

let roles: string[] = [];

// `canAuthorProducts` reads realm_access.roles off the access token, so the
// identity is stubbed at the auth boundary rather than by forging a JWT.
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({
    user: roles.length
      ? { access_token: `x.${btoa(JSON.stringify({ realm_access: { roles } }))}.y` }
      : undefined,
  }),
}));

const activeProduct = {
  productId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  productCode: 'DEMO-TERM-01',
  productName: 'Demo Term Life',
  category: 'TERM_LIFE',
  status: 'ACTIVE',
  defaultCurrency: 'TZS',
} as ProductSummary;

const draft = {
  productId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  productCode: 'Education02',
  productName: 'Education Plan',
  category: 'EDUCATION_SAVINGS',
  status: 'DRAFT',
  defaultCurrency: 'TZS',
} as ProductSummary;

const loadDrafts = vi.fn(async () => {});

function renderPage(drafts: ProductSummary[] | null) {
  useProductStore.setState({
    list: success([activeProduct]),
    drafts: drafts === null ? idle() : success(drafts),
    snapshots: {},
    ratings: {},
    creating: idle(),
    publishing: {},
    loadList: vi.fn(async () => {}),
    loadDrafts,
    loadSnapshot: vi.fn(async () => {}),
    loadRating: vi.fn(async () => {}),
  });

  // Mounted on its real route, not rendered bare: the draft link is RELATIVE
  // (`to={productId}`), so without the route context it resolves against "/" and
  // this test would assert a path the app never produces.
  render(
    <MemoryRouter initialEntries={['/staff/products']}>
      <Routes>
        <Route path="/staff/products" element={<ProductsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('draft products', () => {
  beforeEach(() => {
    roles = [];
    loadDrafts.mockClear();
    useProductStore.setState({ drafts: idle() });
  });

  it('names an unpublished product, and says its code is held', () => {
    roles = ['ADMIN'];
    renderPage([draft]);

    expect(screen.getByText('1 product is created but not published')).toBeInTheDocument();
    // The name links to the product's own page -- where the publish form is, so the
    // abandoned task can be finished rather than only observed.
    expect(screen.getByRole('link', { name: 'Education Plan' })).toHaveAttribute(
      'href',
      '/staff/products/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    );
    // The code, because "already exists" is the message a reader arrives holding.
    expect(screen.getByText('Education02')).toBeInTheDocument();
    expect(screen.getByText(/already holds its product code/)).toBeInTheDocument();
  });

  it('counts in the plural without pretending one is several', () => {
    roles = ['ADMIN'];
    renderPage([draft, { ...draft, productId: 'cccccccc-cccc-cccc-cccc-cccccccccccc', productCode: 'Edu01' }]);
    expect(screen.getByText('2 products are created but not published')).toBeInTheDocument();
  });

  /** A permanent empty frame reading "no drafts" is noise on a catalogue page. */
  it('renders nothing at all when there is nothing unfinished', () => {
    roles = ['ADMIN'];
    renderPage([]);

    expect(screen.queryByText(/created but not published/)).not.toBeInTheDocument();
    // The catalogue itself is untouched by any of this.
    expect(screen.getByText('Demo Term Life')).toBeInTheDocument();
  });

  /**
   * The gate that matters. `GET /products/drafts` is ADMIN-only server-side, so a
   * non-admin rendering this panel would collect a 403 and park it in the store,
   * where the products page would then have an error to show for a list nobody
   * asked for. It must not even be requested.
   */
  it('is neither shown nor requested for a staff member who cannot author', () => {
    roles = ['FINANCE_OFFICER'];
    renderPage([draft]);

    expect(screen.queryByText(/created but not published/)).not.toBeInTheDocument();
    expect(screen.queryByText('Education02')).not.toBeInTheDocument();
    expect(loadDrafts).not.toHaveBeenCalled();
  });
});
